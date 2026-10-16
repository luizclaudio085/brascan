package com.brastoy.brascan.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle

/**
 * Recebe o resultado da sessão de instalação (PendingIntent de Activity, não de Broadcast).
 * Quando o sistema pede confirmação, a tela "Instalar?" é aberta daqui, de primeiro plano — aberta de um
 * BroadcastReceiver ela caía com "INSTALL_FAILED_ABORTED: Permission denied". Sem interface própria: só repassa e fecha.
 */
class InstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); handle(intent); finish() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent); finish() }

    private fun handle(intent: Intent?) {
        intent ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) runCatching { startActivity(confirm) }.onFailure { Updater.onInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, it.message) }
            return
        }
        Updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
