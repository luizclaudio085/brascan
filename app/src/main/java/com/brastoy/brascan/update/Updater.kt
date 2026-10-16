package com.brastoy.brascan.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.brastoy.brascan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Regras aprendidas no celular (16/10/2026):
 * - o APK baixado fica guardado por versão e é conferido pelo SHA-256: não baixa de novo o que já baixou;
 * - uma versão que falhou ao instalar não é tentada de novo sozinha (só pelo botão), e isso sobrevive a reinícios;
 * - a confirmação do sistema ("Instalar?") é aberta por uma Activity nossa (InstallActivity), nunca por um
 *   BroadcastReceiver: abrir de fundo dava "INSTALL_FAILED_ABORTED: Permission denied" mesmo depois de autorizar.
 */
object Updater {
    data class Remote(val code: Int, val name: String, val apk: String, val sha256: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val at: Long) : State
        data class Available(val remote: Remote) : State
        data class Downloading(val remote: Remote, val progress: Float) : State
        data class NeedsPermission(val remote: Remote) : State
        data class Installing(val remote: Remote) : State
        /** [remote] != null: dá para tentar de novo com o APK já baixado. */
        data class Failed(val message: String, val remote: Remote? = null) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val lastCheck = MutableStateFlow(0L)
    private val lock = Mutex()
    private var lastRemote: Remote? = null

    private val base get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()
    /** Última versão cuja instalação foi tentada (persistente: reiniciar o app não reinicia o download). */
    private fun attempted(ctx: Context) = prefs(ctx).getInt("attempted", -1)
    private fun setAttempted(ctx: Context, code: Int) = prefs(ctx).edit().putInt("attempted", code).apply()

    /** Uma rodada: consulta e, se houver versão nova e o modo automático estiver ligado, instala. */
    suspend fun tick(ctx: Context, force: Boolean = false) = lock.withLock {
        val s = state.value
        if (s is State.Downloading || s is State.Installing) return@withLock
        val remote = try { state.value = State.Checking; fetch() } catch (e: Exception) {
            state.value = State.Failed("Sem conexão com o GitHub (${e.javaClass.simpleName})"); return@withLock
        }
        lastCheck.value = System.currentTimeMillis()
        if (remote == null || remote.code <= BuildConfig.VERSION_CODE) { state.value = State.UpToDate(lastCheck.value); return@withLock }
        lastRemote = remote
        val failedBefore = s is State.Failed && s.remote?.code == remote.code
        if (force || (autoInstall(ctx) && attempted(ctx) != remote.code)) install(ctx, remote)
        else if (!failedBefore) state.value = State.Available(remote) // falha anterior fica na tela, com o botão de tentar de novo
    }

    /** Botão "tentar de novo": reinstala a versão disponível (reaproveita o APK já baixado). */
    suspend fun retry(ctx: Context) = lock.withLock { lastRemote?.let { install(ctx, it) } ?: tick(ctx, force = true) }

    private suspend fun fetch(): Remote? = withContext(Dispatchers.IO) {
        val c = open("$base/version.json?t=${System.currentTimeMillis()}")
        try {
            if (c.responseCode == 404) return@withContext null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            Remote(j.getInt("versionCode"), j.getString("versionName"), j.optString("apk", "$base/brascan.apk"), j.optString("sha256"), j.optString("notes"))
        } finally { c.disconnect() }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000; useCaches = false; instanceFollowRedirects = true
        setRequestProperty("Cache-Control", "no-cache"); setRequestProperty("User-Agent", "BraScan/${BuildConfig.VERSION_NAME}")
    }

    private suspend fun install(ctx: Context, r: Remote) {
        // Sem a permissão não conta como tentativa: ao voltar das configurações instala sozinho.
        if (!ctx.packageManager.canRequestPackageInstalls()) { state.value = State.NeedsPermission(r); return }
        setAttempted(ctx, r.code)
        val apk = try { download(ctx, r) } catch (e: Exception) { state.value = State.Failed("Falha no download: ${e.message}", r); return }
        state.value = State.Installing(r)
        try {
            withContext(Dispatchers.IO) { commit(ctx, apk) }
        } catch (e: Exception) { state.value = State.Failed("Falha ao instalar: ${e.message}", r) }
    }

    private fun cacheFile(ctx: Context, r: Remote) = File(File(ctx.cacheDir, "update").apply { mkdirs() }, "brascan-${r.code}.apk")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun download(ctx: Context, r: Remote): File = withContext(Dispatchers.IO) {
        val file = cacheFile(ctx, r)
        // já baixado e íntegro: reaproveita (reinício do app, instalação cancelada, "tentar de novo")
        if (file.exists() && file.length() > 0 && (r.sha256.isBlank() || sha256(file).equals(r.sha256, ignoreCase = true))) return@withContext file
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
            file
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
            // o resultado chega numa Activity nossa: a confirmação do sistema é aberta de primeiro plano
            val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, InstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            session.commit(pi.intentSender)
        }
    }

    /** Chamado pela InstallActivity com o resultado da sessão. */
    fun onInstallStatus(status: Int, message: String?) {
        val r = lastRemote
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // o processo é reiniciado pelo sistema
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            else -> state.value = State.Failed(when {
                status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "Assinatura diferente: desinstale a versão antiga uma vez e instale pelo link do GitHub"
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada pelo sistema. Toque em \"Tentar de novo\" e confirme na tela que abrir"
                else -> "Instalação cancelada (${message ?: "código $status"})"
            }, r)
        }
    }

    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun releasesPage() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
}
