package com.brastoy.brascan.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.brastoy.brascan.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Atualização direta do GitHub Releases: o workflow publica `version.json` + APK a cada push na main.
 * Usa o link fixo releases/latest/download (sem a API, então sem limite de 60 consultas/hora).
 *
 * Regras aprendidas no celular, no emulador Android 15 e numa revisão adversarial (16/10 e 01/11/2026):
 * - TODO o trabalho (consulta, download, sessão) roda no escopo próprio do Updater, nunca numa coroutine da tela:
 *   sair do app no meio do download cancelava a coroutine e a versão ficava presa em "não instalou";
 * - o APK baixado fica guardado por versão e é conferido pelo SHA-256; logo depois do download a sessão do
 *   PackageInstaller já é preparada (APK copiado) e o id fica salvo: na hora de instalar só falta o commit (ms);
 * - instalar FECHA o app. A versão pronta só é instalada sozinha quando o APP INTEIRO sai da frente
 *   (ProcessLifecycleOwner, ignora rotação/recriação), e não enquanto há um fluxo externo em andamento (câmera do
 *   sistema, galeria, compartilhar, navegador: [externalFlow]) nem uma tela de câmera ao vivo aberta ([setBusy]).
 *   Antes do commit o projeto atual é salvo na hora ([beforeInstall]);
 * - o Android 14/15 BLOQUEIA abrir tela a partir de PendingIntent/receiver com o app fora da frente (BAL_BLOCK).
 *   O resultado chega no [InstallReceiver], que só guarda o pedido de confirmação ("Instalar?"); quem abre é a
 *   MainActivity, na frente ([openConfirm]). Se um commit de fundo pedir confirmação, o aparelho passa a ser tratado
 *   como "pede confirmação" (não promete mais instalar sozinho);
 * - resultado de sessão velha (abandonada) é ignorado pelo id da sessão;
 * - falha de DOWNLOAD é tentada de novo sozinha (com espera crescente); falha de INSTALAÇÃO espera o botão e
 *   oferece o instalador do próprio Android (MIUI e afins às vezes recusam a sessão);
 * - a consulta de fundo (30 s) é silenciosa: não troca o que está na tela por "verificando".
 */
object Updater {
    data class Remote(val code: Int, val name: String, val apk: String, val sha256: String, val notes: String) {
        fun toJson() = JSONObject().put("code", code).put("name", name).put("apk", apk).put("sha", sha256).put("notes", notes).toString()
        companion object {
            fun fromJson(s: String?) = s?.let { runCatching { val j = JSONObject(it); Remote(j.getInt("code"), j.getString("name"), j.getString("apk"), j.optString("sha"), j.optString("notes")) }.getOrNull() }
        }
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val at: Long) : State
        /** Versão nova e a instalação automática desligada (ou a instalação dela já falhou): espera o botão. */
        data class Available(val remote: Remote) : State
        data class Downloading(val remote: Remote, val progress: Float) : State
        /** Baixada, conferida e com a sessão preparada. [silent]: instala sozinha quando o usuário sair do app. */
        data class Ready(val remote: Remote, val silent: Boolean) : State
        data class NeedsPermission(val remote: Remote) : State
        data class Installing(val remote: Remote) : State
        /** O sistema pediu confirmação: a tela "Instalar?" abre pela MainActivity (toque no aviso). */
        data class NeedsConfirm(val remote: Remote) : State
        /**
         * [remote] != null: dá para tentar de novo com o APK já baixado. [download]: falhou ao baixar (tenta de novo
         * sozinho). [fallback]: a sessão falhou; dá para tentar pelo instalador do Android.
         */
        data class Failed(val message: String, val remote: Remote? = null, val download: Boolean = false, val fallback: Boolean = false) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val lastCheck = MutableStateFlow(0L)
    /** Pedido de confirmação do sistema guardado pelo [InstallReceiver]. */
    @Volatile var confirmIntent: Intent? = null
        private set
    /** Salva o trabalho do usuário antes de instalar (instalar mata o processo). Definido pela MainActivity. */
    @Volatile var beforeInstall: (() -> Unit)? = null

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var app: Context? = null
    @Volatile private var lastRemote: Remote? = null
    @Volatile private var userAskedAt = 0L
    @Volatile private var inForeground = true
    @Volatile private var externalSince = 0L
    private val busy = AtomicInteger(0)
    private var watchdog: Job? = null
    @Volatile private var downloadFailures = 0
    @Volatile private var downloadRetryAt = 0L

    private val base get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoInstall(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto", on).apply()
        // desligado: a versão já pronta não instala mais sozinha, só pelo botão
        (state.value as? State.Ready)?.let { if (!on) state.value = State.Available(it.remote) else state.value = it.copy(silent = canUpdateSilently(ctx)) }
    }
    /** Versão cuja INSTALAÇÃO falhou (persistente): não é tentada de novo sozinha. */
    private fun failedCode(ctx: Context) = prefs(ctx).getInt("attempted", -1)
    private fun setFailedCode(ctx: Context, code: Int) = prefs(ctx).edit().putInt("attempted", code).apply()

    /**
     * Android 12+: um app que atualiza a SI MESMO (com UPDATE_PACKAGES_WITHOUT_USER_ACTION) não precisa de confirmação,
     * qualquer que seja o instalador registrado (testado no emulador Android 15). Fabricantes podem pedir mesmo assim:
     * depois do primeiro pedido de confirmação num commit de fundo, este aparelho passa a ser "pede confirmação".
     */
    fun canUpdateSilently(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 && !prefs(ctx).getBoolean("needsConfirm", false)

    /** Uma vez, na abertura: observa o app inteiro entrar e sair da frente. */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { inForeground = true; externalSince = 0L }
                Lifecycle.Event.ON_STOP -> { inForeground = false; installIfAway() }
                else -> Unit
            }
        })
    }

    /** Vai abrir câmera/galeria/compartilhar/navegador: não instalar até o usuário voltar ao app. */
    fun externalFlow() { externalSince = System.currentTimeMillis() }
    /** Tela que não pode ser interrompida (câmera ao vivo do scanner/contagem) aberta ou fechada. */
    fun setBusy(on: Boolean) { if (on) busy.incrementAndGet() else busy.updateAndGet { maxOf(0, it - 1) } }

    // ---------- chamadas da interface (não bloqueiam; o trabalho segue mesmo se a tela sair) ----------

    /** Consulta (a cada 30 s, em silêncio) ou "Verificar agora" ([force]: mostra o andamento e instala já). */
    fun check(ctx: Context, force: Boolean = false) { val a = ctx.applicationContext; scope.launch { tick(a, force) } }

    /** Botões "Atualizar", "Instalar agora", "Tentar de novo". */
    fun install(ctx: Context) {
        val a = ctx.applicationContext
        val s = state.value
        if (s is State.Downloading || s is State.Installing || s is State.Checking) return // toque repetido
        userAskedAt = System.currentTimeMillis()
        scope.launch {
            val r = lock.withLock { lastRemote ?: (state.value as? State.Ready)?.remote }
            if (r == null) tick(a, force = true) else lock.withLock { prepare(a, r, installNow = true) }
        }
    }

    // ---------- núcleo ----------

    private suspend fun tick(ctx: Context, force: Boolean) = lock.withLock {
        val s = state.value
        if (s is State.Downloading || s is State.Installing) return@withLock
        // "Verificar agora" com algo já na tela para a versão nova não esconde o aviso
        val loud = s is State.Idle || (force && s !is State.Ready && s !is State.NeedsConfirm && s !is State.Available)
        if (loud) state.value = State.Checking
        val remote = try { fetch() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (state.value is State.Checking) state.value = State.Failed("Sem conexão com o GitHub (${e.javaClass.simpleName})")
            return@withLock
        }
        lastCheck.value = System.currentTimeMillis()
        if (remote == null || remote.code <= BuildConfig.VERSION_CODE) {
            state.value = State.UpToDate(lastCheck.value); confirmIntent = null; return@withLock
        }
        lastRemote = remote
        val cur = state.value
        val shown = when (cur) {
            is State.Ready -> cur.remote; is State.NeedsConfirm -> cur.remote; is State.Available -> cur.remote
            is State.NeedsPermission -> cur.remote; is State.Failed -> cur.remote; else -> null
        }?.code == remote.code
        when {
            force -> { userAskedAt = System.currentTimeMillis(); prepare(ctx, remote, installNow = true) }
            shown && cur is State.NeedsPermission -> prepare(ctx, remote, installNow = false) // voltou das configurações
            shown && cur is State.Failed && cur.download -> if (System.currentTimeMillis() >= downloadRetryAt) prepare(ctx, remote, installNow = false)
            shown -> if (cur is State.Checking) state.value = State.Available(remote)
            autoInstall(ctx) && failedCode(ctx) != remote.code -> prepare(ctx, remote, installNow = false)
            else -> state.value = State.Available(remote)
        }
    }

    private fun fetch(): Remote? {
        val c = open("$base/version.json?t=${System.currentTimeMillis()}")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            return Remote(j.getInt("versionCode"), j.getString("versionName"), j.optString("apk", "$base/brascan.apk"), j.optString("sha256"), j.optString("notes"))
        } finally { c.disconnect() }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000; useCaches = false; instanceFollowRedirects = true
        setRequestProperty("Cache-Control", "no-cache"); setRequestProperty("User-Agent", "BraScan/${BuildConfig.VERSION_NAME}")
    }

    /** Baixa (se preciso), prepara a sessão e instala já ([installNow]) ou deixa pronta para quando o usuário sair. */
    private fun prepare(ctx: Context, r: Remote, installNow: Boolean) {
        // Sem a permissão não conta como tentativa: ao voltar das configurações continua sozinho.
        if (!ctx.packageManager.canRequestPackageInstalls()) { state.value = State.NeedsPermission(r); return }
        val apk = try { download(ctx, r) } catch (e: Exception) {
            downloadFailures++
            downloadRetryAt = System.currentTimeMillis() + minOf(30L, 1L shl minOf(downloadFailures, 5)) * 60_000L
            state.value = State.Failed("Não baixou (${e.message ?: e.javaClass.simpleName}). Tento de novo sozinho daqui a pouco", r, download = true)
            return
        }
        downloadFailures = 0
        val id = try { stage(ctx, r, apk) } catch (e: Exception) { state.value = State.Failed("Não preparou a instalação: ${e.message}", r, fallback = true); return }
        if (installNow) commit(ctx, r, id, byUser = true)
        else {
            state.value = State.Ready(r, canUpdateSilently(ctx) && autoInstall(ctx))
            installIfAwayLocked(ctx) // ficou pronta com o app já fora da frente: o ON_STOP já passou (a trava já é nossa)
        }
    }

    private fun cacheFile(ctx: Context, r: Remote) = File(File(ctx.cacheDir, "update").apply { mkdirs() }, "brascan-${r.code}.apk")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(ctx: Context, r: Remote): File {
        val file = cacheFile(ctx, r)
        // já baixado e íntegro: reaproveita (reinício do app, instalação cancelada, "tentar de novo")
        if (file.exists() && file.length() > 0 && (r.sha256.isBlank() || sha256(file).equals(r.sha256, ignoreCase = true))) return file
        file.parentFile?.listFiles()?.forEach { if (it != file) it.delete() } // só a versão atual fica no cache
        val tmp = File(file.path + ".part")
        val c = open(r.apk)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
            val md = MessageDigest.getInstance("SHA-256")
            state.value = State.Downloading(r, 0f)
            c.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) state.value = State.Downloading(r, done.toFloat() / total)
                }
            } }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256.isNotBlank() && !hash.equals(r.sha256, ignoreCase = true)) { tmp.delete(); error("arquivo corrompido, SHA-256 não confere") }
            if (!tmp.renameTo(file)) error("não deu para guardar o APK")
            return file
        } finally { c.disconnect() }
    }

    /** Cria a sessão com o APK já copiado (sobrevive à morte do processo); devolve o id. */
    private fun stage(ctx: Context, r: Remote, apk: File): Int {
        val installer = ctx.packageManager.packageInstaller
        val p = prefs(ctx)
        val old = p.getInt("session", -1)
        val info = if (old >= 0 && p.getInt("sessionCode", -1) == r.code) installer.getSessionInfo(old) else null
        if (info != null && (Build.VERSION.SDK_INT < 29 || !info.isSealed)) return old // preparada antes e ainda não enviada
        // sessões velhas (outra versão, cancelada no meio) atrapalham a nova
        for (s in installer.mySessions) runCatching { installer.abandonSession(s.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+: o BraScan atualizando a si mesmo não precisa de toque
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            // Android 14+: o BraScan passa a ser o "dono" das atualizações do próprio app
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("brascan.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
        }
        p.edit().putInt("session", id).putInt("sessionCode", r.code).putString("pending", r.toJson()).apply()
        return id
    }

    /** Envia a sessão preparada (rápido). [byUser]: toque do usuário (a confirmação pode abrir sozinha). */
    private fun commit(ctx: Context, r: Remote, id: Int, byUser: Boolean) {
        runCatching { beforeInstall?.invoke() } // o projeto é salvo antes: instalar mata o processo
        confirmIntent = null
        prefs(ctx).edit().putBoolean("byUser", byUser).commit()
        state.value = State.Installing(r)
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            // resultado num receiver: broadcast não sofre o bloqueio de abrir tela com o app fora da frente
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
            ctx.packageManager.packageInstaller.openSession(id).use { it.commit(pi.intentSender) }
        } catch (e: Exception) {
            prefs(ctx).edit().remove("session").apply()
            state.value = State.Failed("Falha ao instalar: ${e.message}", r, fallback = true); return
        }
        if (byUser) userAskedAt = System.currentTimeMillis()
        // se o sistema não responder (sessão perdida), não fica "Instalando…" para sempre
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(120_000)
            if (state.value == State.Installing(r)) state.value = State.Failed("A instalação não respondeu", r, fallback = true)
        }
    }

    /** App fora da frente (ON_STOP do processo, thread principal): instala a versão pronta se nada estiver em andamento. */
    private fun installIfAway() {
        val ctx = app ?: return
        if (state.value !is State.Ready) return
        if (!lock.tryLock()) return // outra rodada mexendo: ela mesma confere ao terminar (installIfAwayLocked)
        try { installIfAwayLocked(ctx) } finally { lock.unlock() }
    }

    /** Com a trava já tomada. */
    private fun installIfAwayLocked(ctx: Context) {
        val s = state.value as? State.Ready ?: return
        if (inForeground || !s.silent || !autoInstall(ctx) || externalSince > 0 || busy.get() > 0) return
        val p = prefs(ctx)
        val id = p.getInt("session", -1).takeIf { it >= 0 && p.getInt("sessionCode", -1) == s.remote.code } ?: return
        commit(ctx, s.remote, id, byUser = false)
    }

    /** Chamado pelo [InstallReceiver] com o resultado da sessão (pode chegar num processo recém-criado). */
    fun onInstallStatus(ctx: Context, sessionId: Int, status: Int, message: String?, confirm: Intent?) {
        val p = prefs(ctx)
        val current = p.getInt("session", -1)
        if (sessionId >= 0 && current >= 0 && sessionId != current) return // resultado de uma sessão abandonada
        val r = lastRemote?.takeIf { it.code == p.getInt("sessionCode", -1) } ?: Remote.fromJson(p.getString("pending", null)) ?: lastRemote
        watchdog?.cancel()
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> p.edit().remove("session").apply() // o processo é reiniciado pelo sistema
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmIntent = confirm
                // um commit de fundo que pede confirmação: este aparelho não instala sozinho; não prometer mais
                if (!p.getBoolean("byUser", false)) p.edit().putBoolean("needsConfirm", true).apply()
                state.value = if (confirm != null && r != null) State.NeedsConfirm(r) else State.Failed("O Android pediu confirmação mas não mandou a tela", r, fallback = true)
            }
            else -> {
                p.edit().remove("session").apply()
                r?.let { setFailedCode(ctx, it.code) }
                val detail = message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: " (código $status)"
                state.value = State.Failed(when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Assinatura diferente: desinstale a versão antiga uma vez e instale pelo link do GitHub$detail"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "O Android bloqueou a instalação$detail"
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada$detail"
                    else -> "Instalação falhou$detail"
                }, r, fallback = status != PackageInstaller.STATUS_FAILURE_CONFLICT && status != PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
            }
        }
    }

    /** A confirmação chegou logo depois de um toque do usuário (≤ 2 min do commit): pode abrir sem novo toque. */
    fun confirmRequestedByUser() = System.currentTimeMillis() - userAskedAt < 120_000

    /** A MainActivity (na frente) abre a confirmação do sistema; devolve false se não havia o que abrir. */
    fun openConfirm(activity: Activity): Boolean {
        val i = confirmIntent ?: return false
        userAskedAt = 0L
        return runCatching { activity.startActivity(i); true }
            .getOrElse { state.value = State.Failed("Não consegui abrir a confirmação (${it.message})", lastRemote, fallback = true); false }
    }

    /**
     * Caminho alternativo: o instalador do próprio Android abre o APK já baixado e conferido (quando a sessão é
     * recusada, como em alguns Xiaomi). Devolve false se o APK não está no cache.
     */
    fun installWithSystem(activity: Activity, r: Remote): Boolean {
        val f = cacheFile(activity, r).takeIf { it.exists() } ?: return false
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        externalFlow()
        return runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); true
        }.getOrDefault(false)
    }

    /** Nome da versão nova se o app acabou de ser atualizado (uma vez por versão), para avisar na tela. */
    fun justUpdated(ctx: Context): String? {
        val p = prefs(ctx)
        val seen = p.getInt("seen", -1)
        if (seen == BuildConfig.VERSION_CODE) return null
        p.edit().putInt("seen", BuildConfig.VERSION_CODE).remove("session").apply()
        return if (seen in 0 until BuildConfig.VERSION_CODE) BuildConfig.VERSION_NAME else null
    }

    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun releasesPage() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
}
