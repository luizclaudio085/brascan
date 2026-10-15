package com.brastoy.brascan.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brastoy.brascan.ui.Palette
import com.brastoy.brascan.vision.Piece3D
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session

/** Scanner 3D em tela cheia. [onDone] recebe as peças reconstruídas (metros, mundo do ARCore). */
@Composable
fun ArScanScreen(onClose: () -> Unit, onDone: (List<Piece3D>) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var session by remember { mutableStateOf<Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askedInstall by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(ScanStatus()) }
    var view by remember { mutableStateOf<ArScanView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed = true; if (e == Lifecycle.Event.ON_PAUSE) resumed = false }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Cria a sessão quando tem permissão e o app está em primeiro plano (o ARCore pode precisar ser instalado antes).
    LaunchedEffect(permission, resumed) {
        if (!permission || !resumed || session != null) return@LaunchedEffect
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !askedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { askedInstall = true; return@LaunchedEffect }
                ArCoreApk.InstallStatus.INSTALLED -> session = Session(ctx)
            }
        } catch (e: Exception) {
            error = when (e) {
                is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "Este celular não suporta ARCore (scanner 3D). O scanner por foto continua funcionando."
                is com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException -> "Para escanear em 3D é preciso instalar o \"Google Play Services para AR\"."
                else -> "Scanner 3D indisponível aqui: precisa de um celular com ARCore e do \"Google Play Services para AR\" (Play Store). O scanner por foto continua funcionando."
            }
        }
    }

    // pausa/retoma sessão + GL junto com o app
    DisposableEffect(session, resumed) {
        val s = session
        if (s != null && resumed) {
            try { s.resume(); view?.onResume() } catch (e: Exception) { error = "Câmera indisponível: ${e.message}" }
        }
        onDispose { if (s != null) { view?.onPause(); s.pause() } }
    }
    DisposableEffect(Unit) { onDispose { view?.shutdown(); session?.close() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = session
        if (s != null && error == null) {
            AndroidView(factory = { c -> ArScanView(c, s) { st -> status = st }.also { view = it; if (resumed) it.onResume() } }, modifier = Modifier.fillMaxSize())
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fechar") }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC0A111E)).padding(horizontal = 14.dp, vertical = 10.dp).weight(1f)) {
                    Text(error ?: if (!permission) "Permita o uso da câmera" else status.hint, color = if (error != null) Palette.pink else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (error == null && session != null) Text(
                        (if (status.depth) "Profundidade ativa" else "Sem sensor de profundidade: usando pontos do SLAM") +
                            " · ${status.observations} pontos · ${status.pieces.size} peças",
                        color = Palette.muted, fontSize = 12.sp,
                    )
                }
            }
            if (error == null && session != null) Row(Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(12) { i -> Box(Modifier.size(width = 18.dp, height = 6.dp).clip(CircleShape).background(if (i < status.coverage) Palette.accent else Color(0x55FFFFFF))) }
            }
        }

        if (error == null && session != null) Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(onClick = { view?.reset() }, modifier = Modifier.height(54.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Recomeçar") }
            Button(enabled = status.pieces.isNotEmpty(), onClick = { onDone(view?.result().orEmpty()) }, modifier = Modifier.weight(1f).height(54.dp)) {
                Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(8.dp)); Text("Usar no 3D (${status.pieces.size})", fontWeight = FontWeight.Bold)
            }
        }
    }
}
