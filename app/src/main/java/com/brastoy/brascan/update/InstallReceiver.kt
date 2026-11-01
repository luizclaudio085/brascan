package com.brastoy.brascan.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Resultado da sessão de instalação. Só repassa ao [Updater]: quando o sistema pede confirmação, o pedido fica
 * guardado e a MainActivity abre a tela "Instalar?" (abrir daqui, ou por PendingIntent de Activity com o app fora
 * da frente, é bloqueado pelo Android 14/15).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else null
        Updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}
