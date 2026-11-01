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
import com.brastoy.brascan.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualização direta do GitHub Releases: o workflow publica `version.json` + APK a cada push na main.
 * Usa o link fixo releases/latest/download (sem a API, então sem limite de 60 consultas/hora).
 *
 * Regras aprendidas (16/10 e 01/11/2026):
 * - o APK baixado fica guardado por versão e é conferido pelo SHA-256: não baixa de novo o que já baixou;
 * - uma versão que falhou ao instalar não é tentada de novo sozinha (só pelo botão), e isso sobrevive a reinícios;
 * - o Android 14/15 BLOQUEIA abrir uma tela a partir de PendingIntent/receiver quando o app não está visível
 *   (logcat: "Background activity launch blocked", BAL_BLOCK). Por isso o resultado da instalação chega num
 *   BroadcastReceiver ([InstallReceiver]) que só GUARDA o pedido de confirmação ("Instalar?"), e quem abre a
 *   confirmação é a MainActivity, na frente ([openConfirm]). Abrir do receiver dava "INSTALL_FAILED_ABORTED:
 *   Permission denied"; abrir por PendingIntent de Activity também é bloqueado com o app fora da tela;
 * - instalar fecha o app. Quando a atualização é silenciosa (o BraScan já é o instalador do próprio app), ela
 *   espera o usuário sair do app ([onBackground]) ou tocar em "Instalar agora": não expulsa ninguém no meio do uso;
 * - a consulta de fundo (30 s) é silenciosa: não troca o que está na tela por "verificando" (antes o aviso sumia
 *   e voltava a cada 30 s e a tela pulava).
 */
object Updater {
    data class Remote(val code: Int, val name: String, val apk: String, val sha256: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val at: Long) : State
        /** Versão nova e a instalação automática desligada (ou já tentada antes): espera o botão. */
        data class Available(val remote: Remote) : State
        data class Downloading(val remote: Remote, val progress: Float) : State
        /** Baixada e conferida. [silent]: instala sem perguntar assim que o usuário sair do app. */
        data class Ready(val remote: Remote, val silent: Boolean) : State
        data class NeedsPermission(val remote: Remote) : State
        data class Installing(val remote: Remote) : State
        /** O sistema pediu confirmação: a tela "Instalar?" abre pela MainActivity (toque no aviso). */
        data class NeedsConfirm(val remote: Remote) : State
        /** [remote] != null: dá para tentar de novo com o APK já baixado. */
        data class Failed(val message: String, val remote: Remote? = null) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val lastCheck = MutableStateFlow(0L)
    /** Pedido de confirmação do sistema guardado pelo [InstallReceiver]. */
    @Volatile var confirmIntent: Intent? = null
        private set
    /** Quando o usuário tocou em instalar: a confirmação que chegar logo depois abre sozinha (é resposta ao toque). */
    @Volatile var userAskedAt = 0L
        private set
    private val lock = Mutex()
    @Volatile private var lastRemote: Remote? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null

    private val base get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()
    /** Última versão cuja instalação foi tentada (persistente: reiniciar o app não reinicia o download). */
    private fun attempted(ctx: Context) = prefs(ctx).getInt("attempted", -1)
    private fun setAttempted(ctx: Context, code: Int) = prefs(ctx).edit().putInt("attempted", code).apply()

    /**
     * Android 12+: um app que atualiza a SI MESMO (com UPDATE_PACKAGES_WITHOUT_USER_ACTION) não precisa de confirmação,
     * qualquer que seja o instalador registrado (testado no emulador Android 15: instalado "pelo Chrome", atualizou
     * sem perguntar). Fabricantes podem pedir mesmo assim: aí cai em [State.NeedsConfirm].
     */
    @Suppress("UNUSED_PARAMETER")
    fun canUpdateSilently(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31

    /**
     * Uma rodada: consulta e, se houver versão nova e o modo automático estiver ligado, baixa (e instala quando der).
     * [force] = o usuário pediu (botão "Verificar agora"/"Atualizar"): mostra "verificando", o erro de conexão e instala já.
     */
    suspend fun tick(ctx: Context, force: Boolean = false) = lock.withLock {
        val s = state.value
        if (s is State.Downloading || s is State.Installing) return@withLock
        val loud = force || s is State.Idle
        if (loud) state.value = State.Checking
        val remote = try { withContext(Dispatchers.IO) { fetch() } } catch (e: Exception) {
            if (loud) state.value = State.Failed("Sem conexão com o GitHub (${e.javaClass.simpleName})")
            return@withLock
        }
        lastCheck.value = System.currentTimeMillis()
        if (remote == null || remote.code <= BuildConfig.VERSION_CODE) { state.value = State.UpToDate(lastCheck.value); return@withLock }
        lastRemote = remote
        // o que já está na tela para ESTA versão continua (pronta, esperando confirmação, falhou, falta permissão)
        val shownCode = when (s) {
            is State.Ready -> s.remote.code; is State.NeedsConfirm -> s.remote.code; is State.Failed -> s.remote?.code
            is State.NeedsPermission -> s.remote.code; else -> null
        }
        when {
            force -> { userAskedAt = System.currentTimeMillis(); prepare(ctx, remote, installNow = true) }
            s is State.NeedsPermission && shownCode == remote.code -> prepare(ctx, remote, installNow = false) // voltou das configurações
            shownCode == remote.code -> Unit
            autoInstall(ctx) && attempted(ctx) != remote.code -> prepare(ctx, remote, installNow = false)
            else -> state.value = State.Available(remote)
        }
    }

    /** Botão do aviso ("Instalar agora" / "Tentar de novo"): instala a versão disponível já (reaproveita o APK). */
    suspend fun installNow(ctx: Context) {
        val r = lastRemote
        if (r == null) { tick(ctx, force = true); return }
        lock.withLock { userAskedAt = System.currentTimeMillis(); prepare(ctx, r, installNow = true) }
    }

    /** O app saiu da tela: hora de instalar a versão pronta sem incomodar (só se for silenciosa). */
    fun onBackground(ctx: Context) {
        val s = state.value as? State.Ready ?: return
        if (!s.silent) return
        val app = ctx.applicationContext
        scope.launch { lock.withLock { if (state.value == s) commitSafely(app, s.remote) } }
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

    /** Baixa (se preciso) e instala já, ou deixa pronta para quando o usuário sair do app. */
    private suspend fun prepare(ctx: Context, r: Remote, installNow: Boolean) {
        // Sem a permissão não conta como tentativa: ao voltar das configurações continua sozinho.
        if (!ctx.packageManager.canRequestPackageInstalls()) { state.value = State.NeedsPermission(r); return }
        try { withContext(Dispatchers.IO) { download(ctx, r) } } catch (e: Exception) { state.value = State.Failed("Falha no download: ${e.message}", r); return }
        if (installNow) commitSafely(ctx, r) else state.value = State.Ready(r, canUpdateSilently(ctx))
    }

    private suspend fun commitSafely(ctx: Context, r: Remote) {
        setAttempted(ctx, r.code)
        confirmIntent = null
        state.value = State.Installing(r)
        try { withContext(Dispatchers.IO) { commit(ctx, cacheFile(ctx, r)) } } catch (e: Exception) { state.value = State.Failed("Falha ao instalar: ${e.message}", r); return }
        // se o sistema não responder (sessão perdida), não fica "Instalando…" para sempre
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(120_000)
            if (state.value == State.Installing(r)) state.value = State.Failed("A instalação não respondeu. Toque em \"Tentar de novo\"", r)
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
            c.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) state.value = State.Downloading(r, done.toFloat() / total)
                }
            } }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256.isNotBlank() && !hash.equals(r.sha256, ignoreCase = true)) { tmp.delete(); error("arquivo corrompido (SHA-256 não confere)") }
            if (!tmp.renameTo(file)) error("não deu para guardar o APK")
            return file
        } finally { c.disconnect() }
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        // sessão antiga pendurada (instalação cancelada no meio) atrapalha a nova
        for (old in installer.mySessions) runCatching { installer.abandonSession(old.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+: se o BraScan já for o instalador do próprio app, a atualização acontece sem toque.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            // Android 14+: o BraScan passa a ser o "dono" das atualizações do próprio app
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("brascan.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            // resultado num receiver: broadcast não sofre o bloqueio de abrir tela com o app fora da frente
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    /** Chamado pelo [InstallReceiver] com o resultado da sessão. */
    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val r = lastRemote ?: (state.value as? State.Installing)?.remote
        watchdog?.cancel()
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // o processo é reiniciado pelo sistema
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmIntent = confirm
                state.value = if (confirm != null && r != null) State.NeedsConfirm(r) else State.Failed("O sistema pediu confirmação mas não mandou a tela", r)
            }
            else -> state.value = State.Failed(when {
                status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "Assinatura diferente: desinstale a versão antiga uma vez e instale pelo link do GitHub"
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada. Toque em \"Tentar de novo\" e confirme na tela que abrir"
                else -> "Instalação cancelada (${message ?: "código $status"})"
            }, r)
        }
    }

    /** A confirmação chegou logo depois de um toque do usuário (≤ 2 min): pode abrir sem novo toque. */
    fun confirmRequestedByUser() = System.currentTimeMillis() - userAskedAt < 120_000

    /** A MainActivity (na frente) abre a confirmação do sistema; devolve false se não havia o que abrir. */
    fun openConfirm(activity: Activity): Boolean {
        val i = confirmIntent ?: return false
        userAskedAt = 0L
        return runCatching { activity.startActivity(i); true }
            .getOrElse { state.value = State.Failed("Não consegui abrir a confirmação (${it.message})", lastRemote); false }
    }

    /** Nome da versão nova se o app acabou de ser atualizado (uma vez por versão), para avisar na tela. */
    fun justUpdated(ctx: Context): String? {
        val seen = prefs(ctx).getInt("seen", -1)
        if (seen == BuildConfig.VERSION_CODE) return null
        prefs(ctx).edit().putInt("seen", BuildConfig.VERSION_CODE).apply()
        return if (seen in 0 until BuildConfig.VERSION_CODE) BuildConfig.VERSION_NAME else null
    }

    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun releasesPage() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
}
