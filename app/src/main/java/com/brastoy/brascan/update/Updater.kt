package com.brastoy.brascan.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val lastCheck = MutableStateFlow(0L)
    private val lock = Mutex()
    private var attempted = -1

    private val base get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    /** Uma rodada: consulta e, se houver versão nova e o modo automático estiver ligado, instala. */
    suspend fun tick(ctx: Context, force: Boolean = false) = lock.withLock {
        val s = state.value
        if (s is State.Downloading || s is State.Installing) return@withLock
        val remote = try { state.value = State.Checking; fetch() } catch (e: Exception) {
            state.value = State.Failed("Sem conexão com o GitHub (${e.javaClass.simpleName})"); return@withLock
        }
        lastCheck.value = System.currentTimeMillis()
        if (remote == null || remote.code <= BuildConfig.VERSION_CODE) { state.value = State.UpToDate(lastCheck.value); return@withLock }
        state.value = State.Available(remote)
        if (force || (autoInstall(ctx) && attempted != remote.code)) install(ctx, remote)
    }

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
        attempted = r.code
        if (!ctx.packageManager.canRequestPackageInstalls()) { state.value = State.NeedsPermission(r); return }
        val apk = try { download(ctx, r) } catch (e: Exception) { state.value = State.Failed("Falha no download: ${e.message}"); return }
        state.value = State.Installing(r)
        try {
            withContext(Dispatchers.IO) { commit(ctx, apk) }
        } catch (e: Exception) { state.value = State.Failed("Falha ao instalar: ${e.message}") }
    }

    private suspend fun download(ctx: Context, r: Remote): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "brascan-${r.code}.apk")
        val c = open(r.apk)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input -> file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) state.value = State.Downloading(r, done.toFloat() / total)
                }
            } }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256.isNotBlank() && !hash.equals(r.sha256, ignoreCase = true)) { file.delete(); error("arquivo corrompido (SHA-256 não confere)") }
            file
        } finally { c.disconnect() }
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // Android 12+: se o BraScan já for o instalador do próprio app, a atualização acontece sem toque.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("brascan.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun releasesPage() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // o processo é reiniciado pelo sistema
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "código $status"
                Updater.state.value = Updater.State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                        "Assinatura diferente: desinstale a versão antiga uma vez e instale pelo link do GitHub" else "Instalação cancelada ($msg)"
                )
            }
        }
    }
}
