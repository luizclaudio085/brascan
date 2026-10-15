package com.brastoy.brascan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brastoy.brascan.ui.BraScanTheme
import com.brastoy.brascan.ui.FieldScreen
import com.brastoy.brascan.ui.GlassCard
import com.brastoy.brascan.ui.Label
import com.brastoy.brascan.ui.ModelScreen
import com.brastoy.brascan.ui.Palette
import com.brastoy.brascan.ui.ScanScreen
import com.brastoy.brascan.ui.ScanState
import com.brastoy.brascan.ui.rememberField
import com.brastoy.brascan.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { BraScanTheme { App() } }
    }
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CAMPO("Campo", Icons.Rounded.Explore), SCAN("Scanner", Icons.Rounded.CameraAlt), MODELO("3D", Icons.Rounded.ViewInAr), AJUSTES("Ajustes", Icons.Rounded.Tune)
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.CAMPO) }
    val scan = remember { ScanState() }
    val (field, hasSensor) = rememberField()
    val update by Updater.state.collectAsState()
    var arOpen by rememberSaveable { mutableStateOf(false) }
    var markerOpen by rememberSaveable { mutableStateOf(false) }
    var resumeMap by remember { mutableStateOf<String?>(null) }
    // projeto atual: volta sozinho depois de uma interrupção e é salvo a cada mudança
    val store = remember { com.brastoy.brascan.scan.ProjectStore(ctx) }
    LaunchedEffect(Unit) { store.load("atual")?.let { scan.load(it, it.pid.ifBlank { scan.projectId }) } }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { scan.saveKey() }.collect {
            kotlinx.coroutines.delay(800)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.save("atual", scan.toProject()) } }
        }
    }

    // Checa o GitHub a cada 30 s enquanto o app está aberto: push na main → APK novo no celular em poucos minutos.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { Updater.tick(ctx); delay(30_000) }
        }
    }

    if (markerOpen) {
        androidx.activity.compose.BackHandler { markerOpen = false }
        com.brastoy.brascan.ar.MarkerScanScreen(initialMap = resumeMap, onClose = { markerOpen = false }, onDone = { found, map -> scan.importScan(found, map); markerOpen = false; tab = Tab.MODELO })
        return
    }
    if (arOpen) {
        androidx.activity.compose.BackHandler { arOpen = false }
        com.brastoy.brascan.ar.ArScanScreen(onClose = { arOpen = false }, onDone = { found -> scan.importScan(found); arOpen = false; tab = Tab.MODELO })
        return
    }

    Scaffold(
        containerColor = Palette.bg,
        topBar = { Header(update) { tab = Tab.AJUSTES } },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A111E)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF1C3352), selectedIconColor = Palette.accent, selectedTextColor = Palette.accent))
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            UpdateBanner(update)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.CAMPO -> FieldScreen(field, hasSensor)
                    Tab.SCAN -> ScanScreen(scan, openAr = { resumeMap = null; markerOpen = true }, openArCore = { arOpen = true }, openResume = { resumeMap = scan.slamMap; markerOpen = true }) { tab = Tab.MODELO }
                    Tab.MODELO -> ModelScreen(scan)
                    Tab.AJUSTES -> Settings(update)
                }
            }
        }
    }
}

@Composable
private fun Header(update: Updater.State, onUpdate: () -> Unit) = Row(
    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF0C1830), Palette.bg))).statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Palette.pink, Palette.accent))), contentAlignment = Alignment.Center) {
        Text("B", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
    }
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Text("BraScan", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text("Scanner 3D · Brastoy MK-7", color = Palette.muted, fontSize = 12.sp)
    }
    val dot = when (update) { is Updater.State.UpToDate -> Palette.y; is Updater.State.Failed -> Palette.pink; is Updater.State.Checking, Updater.State.Idle -> Palette.muted; else -> Color(0xFFFFC531) }
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF13203A)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text("  v${BuildConfig.VERSION_NAME}", color = Palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun UpdateBanner(s: Updater.State) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val visible = s is Updater.State.Available || s is Updater.State.Downloading || s is Updater.State.NeedsPermission || s is Updater.State.Installing
    AnimatedVisibility(visible) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Palette.accent)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(when (s) {
                        is Updater.State.Available -> "Versão ${s.remote.name} disponível"
                        is Updater.State.Downloading -> "Baixando ${s.remote.name}…"
                        is Updater.State.NeedsPermission -> "Libere a instalação para atualizar"
                        is Updater.State.Installing -> "Instalando ${s.remote.name}…"
                        else -> ""
                    }, color = Color.White, fontWeight = FontWeight.SemiBold)
                    if (s is Updater.State.NeedsPermission) Text("Só precisa fazer uma vez", color = Palette.muted, fontSize = 12.sp)
                }
                when (s) {
                    is Updater.State.Available -> Button(onClick = { scope.launch { Updater.tick(ctx, force = true) } }) { Text("Atualizar") }
                    is Updater.State.NeedsPermission -> Button(onClick = { ctx.startActivity(Updater.permissionIntent(ctx)) }) { Text("Liberar") }
                    else -> Unit
                }
            }
            if (s is Updater.State.Downloading) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun Settings(update: Updater.State) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by Updater.lastCheck.collectAsState()
    var auto by remember { mutableStateOf(Updater.autoInstall(ctx)) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Label("Atualização automática")
            Text("Versão instalada: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text(when (update) {
                is Updater.State.UpToDate -> "Em dia"
                is Updater.State.Checking -> "Verificando…"
                is Updater.State.Failed -> update.message
                is Updater.State.Available -> "Nova versão: ${update.remote.name}${if (update.remote.notes.isNotBlank()) "\n“${update.remote.notes}”" else ""}"
                is Updater.State.Downloading -> "Baixando ${(update.progress * 100).toInt()}%"
                is Updater.State.NeedsPermission -> "Falta liberar \"instalar apps desconhecidos\" para o BraScan"
                is Updater.State.Installing -> "Instalando…"
                Updater.State.Idle -> "Aguardando"
            }, color = if (update is Updater.State.Failed) Palette.pink else Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (last > 0) Text("Última verificação: ${DateFormat.getTimeInstance().format(Date(last))} · repete a cada 30 s", color = Palette.muted, fontSize = 12.sp)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Instalar sozinho", color = Color.White)
                    Text("Baixa e instala assim que sair versão nova no GitHub", color = Palette.muted, fontSize = 12.sp)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; Updater.setAutoInstall(ctx, it) })
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { scope.launch { Updater.tick(ctx, force = true) } }, modifier = Modifier.weight(1f)) { Text("Verificar agora") }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.releasesPage()))) }, modifier = Modifier.weight(1f)) { Text("Ver no GitHub") }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Label("Como usar")
            Text(
                "• Campo: vetor magnético em 3D em tempo real. \"Zerar ambiente\" transforma em detector de ímã.\n" +
                    "• Scanner: foto de frente ou de cima → as peças coloridas são detectadas sozinhas. Toque para corrigir.\n" +
                    "• 3D: gire o modelo, toque numa peça para mudar forma, tamanho, cor, posição e orientação.\n" +
                    "• Envie o JSON para o simulador MK-7.\n\n" +
                    "Uma foto só mostra um lado: a profundidade é estimada. Ajuste \"Fundo\" e \"Altura\" no editor 3D.",
                color = Color(0xFFC9D6EA), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
