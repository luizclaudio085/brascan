package com.brastoy.brascan.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brastoy.brascan.ar.ArScanView
import com.brastoy.brascan.scan.Counting
import com.brastoy.brascan.scan.Inventory
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/**
 * Contagem ao vivo: a câmera olha de cima para as peças espalhadas, cada uma ganha uma etiqueta com o tipo e o
 * total por tipo fica estável (mediana dos últimos quadros). "Revisar" congela o quadro para corrigir à mão;
 * "Somar" põe a contagem no inventário.
 */
@Composable
fun CountCameraScreen(onClose: () -> Unit, onAdd: (Map<String, Int>) -> Unit, onFreeze: (CountShot) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }
    val engine = remember { CountEngine(ctx) }
    DisposableEffect(Unit) { onDispose { engine.stop() } }
    val live by engine.state.collectAsState()
    val label = remember { android.graphics.Paint().apply { isAntiAlias = true; textSize = 34f; color = android.graphics.Color.WHITE; isFakeBoldText = true } }
    val labelBg = remember { android.graphics.Paint().apply { color = 0xB0000000.toInt() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permission) {
            AndroidView(factory = { c -> PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { engine.bind(owner, it) } }, modifier = Modifier.fillMaxSize())
            // caixas: a imagem analisada (em pé) cobre a tela como o PreviewView em FILL_CENTER
            Canvas(Modifier.fillMaxSize()) {
                val iw = live.w.toFloat(); val ih = live.h.toFloat()
                if (iw <= 0f || ih <= 0f) return@Canvas
                val s = maxOf(size.width / iw, size.height / ih); val ox = (size.width - iw * s) / 2; val oy = (size.height - ih * s) / 2
                for (f in live.boxes) {
                    val col = if (f.cut) Color.Gray else if (f.fit >= .8f) Color(0xFF3DDC97) else if (f.fit >= .6f) Color(0xFFFFD23F) else Color(0xFFFF8A3D)
                    val tl = Offset(ox + (f.cx - f.w / 2) * iw * s, oy + (f.cy - f.h / 2) * ih * s)
                    drawRect(col, tl, GSize(f.w * iw * s, f.h * ih * s), style = Stroke(3.dp.toPx()))
                    if (f.cut) continue
                    val t = kindShort(f.kind); val tw = label.measureText(t)
                    val lx = tl.x.coerceIn(0f, (size.width - tw - 10).coerceAtLeast(0f)); val ly = (tl.y - 8).coerceAtLeast(40f)
                    drawContext.canvas.nativeCanvas.apply { drawRect(lx, ly - 34, lx + tw + 10, ly + 8, labelBg); drawText(t, lx + 5, ly - 2, label) }
                }
            }
        } else Text("Preciso da câmera para contar as peças.", color = Color.White, modifier = Modifier.align(Alignment.Center))

        Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fechar") }
            Spacer(Modifier.weight(1f))
            Text("%.1f quadros/s · %d ms".format(live.fps, live.ms), color = Color.White.copy(alpha = .7f), fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x80000000)).padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { engine.torch(!live.torch) }) { Icon(if (live.torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Lanterna") }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).background(Color(0xE60A111E)).padding(16.dp)) {
            val total = live.counts.values.sum()
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$total", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(" peças vistas", color = Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(when {
                live.error != null -> live.error!!
                live.frames == 0 -> "Abrindo a câmera…"
                live.boxes.isEmpty() -> "Não vejo peças: espalhe no chão, aponte de cima, melhore a luz"
                live.boxes.all { it.cut } -> "As peças estão cortadas na borda: afaste o celular"
                live.boxes.any { it.fit < .6f } -> "Etiqueta laranja = duvidosa: aproxime ou afaste as peças entre si"
                else -> "Segure parado um instante: o número estabiliza"
            }, color = Color(0xFFC9D6EA), fontSize = 13.sp)
            if (live.counts.isNotEmpty()) Text(Inventory.KINDS.filter { (live.counts[it.key] ?: 0) > 0 }.joinToString { "${live.counts[it.key]} ${kindShort(it)}" },
                color = Palette.accent, fontSize = 13.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(enabled = live.boxes.isNotEmpty(), onClick = { engine.freeze()?.let(onFreeze) }, modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Rounded.Fullscreen, null); Spacer(Modifier.width(6.dp)); Text("Revisar")
                }
                Button(enabled = total > 0, onClick = { onAdd(live.counts) }, modifier = Modifier.weight(1f).height(50.dp), shape = CircleShape) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Somar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Câmera + detector numa thread própria; publica caixas (imagem em pé, normalizadas) e a contagem estável. */
class CountEngine(private val ctx: Context) {
    data class Live(val boxes: List<Counting.Found> = emptyList(), val counts: Map<String, Int> = emptyMap(), val w: Int = 0, val h: Int = 0,
                    val frames: Int = 0, val fps: Float = 0f, val torch: Boolean = false, val error: String? = null, val ms: Int = 0)
    val state = MutableStateFlow(Live())
    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val stab = Counting.Stabilizer(7)
    @Volatile private var torchOn = false
    @Volatile private var last: Triple<IntArray, Int, Int>? = null
    @Volatile private var lastFound: List<Counting.Found> = emptyList()
    private var lastT = 0L
    private var fps = 0f

    fun torch(on: Boolean) { torchOn = on; camera?.cameraControl?.enableTorch(on); state.value = state.value.copy(torch = on) }

    fun bind(owner: androidx.lifecycle.LifecycleOwner, pv: PreviewView) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get(); provider = p
            val sel = { size: Size -> ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build() }
            // prévia e análise no mesmo 4:3: as caixas caem em cima das peças
            val preview = Preview.Builder().setResolutionSelector(sel(Size(1440, 1080))).build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setResolutionSelector(sel(Size(960, 720)))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img ->
                try { process(img) } catch (e: Exception) { android.util.Log.w("BraScan", "quadro da contagem falhou", e) } finally { img.close() }
            }
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                if (torchOn) camera?.cameraControl?.enableTorch(true)
            }.onFailure { android.util.Log.w("BraScan", "câmera da contagem", it); state.value = state.value.copy(error = "Não consegui abrir a câmera traseira (${it.message ?: it.javaClass.simpleName})") }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun process(img: androidx.camera.core.ImageProxy) {
        val image = img.image ?: return
        val t0 = SystemClock.elapsedRealtime()
        val (raw, rw, rh) = ArScanView.yuvToArgb(image, Counting.LIVE_W)
        val up = Counting.upright(raw, rw, rh, img.imageInfo.rotationDegrees)
        val t1 = SystemClock.elapsedRealtime()
        val found = Counting.detect(up.first, up.second, up.third)
        val t2 = SystemClock.elapsedRealtime()
        if (stab.frames % 10 == 0) android.util.Log.i("BraScan", "contagem: conversão ${t1 - t0} ms, detecção ${t2 - t1} ms, ${found.size} peças")
        last = up; lastFound = found
        val counts = stab.push(Counting.tally(found))
        val now = SystemClock.elapsedRealtime()
        if (lastT != 0L) fps = fps * .7f + .3f * 1000f / (now - lastT).coerceAtLeast(1)
        lastT = now
        state.value = Live(found, counts, up.second, up.third, stab.frames, fps, torchOn, ms = (t2 - t0).toInt())
    }

    /** O quadro de agora, para revisar com calma. */
    fun freeze(): CountShot? {
        val (px, w, h) = last ?: return null
        return CountShot(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), lastFound)
    }

    fun stop() { runCatching { provider?.unbindAll() }; exec.shutdown() }
}
