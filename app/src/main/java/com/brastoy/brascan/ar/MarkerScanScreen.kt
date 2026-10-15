package com.brastoy.brascan.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brastoy.brascan.ui.Palette
import com.brastoy.brascan.vision.Intrinsics
import com.brastoy.brascan.vision.Piece3D
import com.brastoy.brascan.vision.PieceSlam
import com.brastoy.brascan.vision.Segmenter
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

/** O que a tela mostra (atualizado pela thread de análise). */
data class MarkerStatus(
    val hint: String = "Aponte para as peças",
    val tracking: Boolean = false,
    val mapped: Int = 0,
    val pieces: List<Piece3D> = emptyList(),
    val outlines: List<Pair<List<Offset>, Int>> = emptyList(), // cantos (pixels da imagem analisada, já em pé) + cor
    val imgW: Int = 1, val imgH: Int = 1,
    val fps: Float = 0f,
    val gravity: Boolean = false,
)

/**
 * Scanner 3D sem ARCore/Google: CameraX + sensor de gravidade + [PieceSlam] (as peças são os marcadores).
 * Funciona em qualquer Android com câmera.
 */
@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MarkerScanScreen(onClose: () -> Unit, onDone: (List<Piece3D>) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }
    var status by remember { mutableStateOf(MarkerStatus()) }
    val engine = remember { Engine(ctx) { status = it } }
    DisposableEffect(Unit) { engine.startSensors(); onDispose { engine.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permission) {
            AndroidView(factory = { c ->
                PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { pv -> engine.bind(owner, pv) }
            }, modifier = Modifier.fillMaxSize())
            // contornos das peças reconhecidas neste quadro, desenhados por cima da imagem
            Canvas(Modifier.fillMaxSize()) {
                val s = status; val k = max(size.width / s.imgW, size.height / s.imgH)
                val ox = (size.width - s.imgW * k) / 2; val oy = (size.height - s.imgH * k) / 2
                for ((pts, col) in s.outlines) {
                    if (pts.size < 3) continue
                    val path = Path().apply { moveTo(ox + pts[0].x * k, oy + pts[0].y * k); pts.drop(1).forEach { lineTo(ox + it.x * k, oy + it.y * k) }; close() }
                    drawPath(path, Color(col).copy(alpha = .25f)); drawPath(path, Color.White, style = Stroke(width = 5f))
                }
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fechar") }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC0A111E)).padding(horizontal = 14.dp, vertical = 10.dp).weight(1f)) {
                    Text(if (!permission) "Permita o uso da câmera" else status.hint, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Sem Google/ARCore · peças como marcadores · ${status.mapped} no mapa · ${"%.0f".format(status.fps)} quadros/s" +
                        (if (status.gravity) "" else " · sem sensor de gravidade"), color = Palette.muted, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { engine.reset() }, modifier = Modifier.height(54.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Recomeçar") }
            Button(enabled = status.pieces.isNotEmpty(), onClick = { onDone(status.pieces) }, modifier = Modifier.weight(1f).height(54.dp)) {
                Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(8.dp)); Text("Usar no 3D (${status.pieces.size})", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Câmera + sensores + SLAM numa thread de análise só (quadros antigos são descartados). */
@OptIn(ExperimentalCamera2Interop::class)
private class Engine(private val ctx: Context, private val publish: (MarkerStatus) -> Unit) : SensorEventListener {
    private val exec = Executors.newSingleThreadExecutor()
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var gravity: FloatArray? = null
    @Volatile private var slam: PieceSlam? = null
    private var intr: Intrinsics? = null
    private var sensorOrientation = 90
    private var provider: ProcessCameraProvider? = null
    private var lastT = 0L; private var fps = 0f

    fun startSensors() {
        (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    override fun onSensorChanged(e: SensorEvent) { gravity = e.values.clone() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    fun stop() { sm.unregisterListener(this); provider?.unbindAll(); exec.shutdown() }
    fun reset() { exec.execute { slam = intr?.let { PieceSlam(it) } } }

    fun bind(owner: androidx.lifecycle.LifecycleOwner, pv: PreviewView) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get(); provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img ->
                try { process(img) } catch (_: Exception) { } finally { img.close() }
            }
            p.unbindAll()
            val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            sensorOrientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            calib = Calib(
                info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { it.width() to it.height() },
                info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { it.width to it.height },
            )
        }, ContextCompat.getMainExecutor(ctx))
    }

    private class Calib(val lens: FloatArray?, val active: Pair<Int, Int>?, val focalMm: Float?, val sensorMm: Pair<Float, Float>?)
    @Volatile private var calib: Calib? = null

    /** Intrínsecos na resolução analisada (w×h, orientação do sensor). */
    private fun intrinsics(w: Int, h: Int): Intrinsics {
        val c = calib
        if (c?.lens != null && c.active != null && c.lens[0] > 0) {
            val k = w.toDouble() / c.active.first
            return Intrinsics(c.lens[0] * k, c.lens[1] * k, c.lens[2] * k, c.lens[3] * k)
        }
        if (c?.focalMm != null && c.sensorMm != null) {
            val fx = c.focalMm / c.sensorMm.first * w
            return Intrinsics(fx.toDouble(), fx.toDouble(), w / 2.0, h / 2.0)
        }
        val fx = w / (2 * kotlin.math.tan(Math.toRadians(34.0))) // ~68° de campo horizontal: típico de câmera traseira
        return Intrinsics(fx, fx, w / 2.0, h / 2.0)
    }

    /** "Para cima" no sistema da câmera (x direita, y baixo, z para frente do sensor), a partir da gravidade do aparelho. */
    private fun upInCamera(): DoubleArray? {
        val g = gravity ?: return null
        val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]); if (n < 1f) return null
        val dx = g[0] / n; val dy = g[1] / n; val dz = g[2] / n
        // aparelho (x direita, y para cima, z para fora da tela) → imagem em pé (x direita, y baixo) → sensor girado
        val ux = dx.toDouble(); val uy = -dy.toDouble()
        val th = Math.toRadians(sensorOrientation.toDouble())
        val sx = ux * kotlin.math.cos(th) + uy * kotlin.math.sin(th); val sy = -ux * kotlin.math.sin(th) + uy * kotlin.math.cos(th)
        return doubleArrayOf(sx, sy, -dz.toDouble())
    }

    private fun process(img: androidx.camera.core.ImageProxy) {
        val image = img.image ?: return
        val outW = 480
        val (rgb, w, h) = ArScanView.yuvToArgb(image, outW)
        val k = intr ?: intrinsics(w, h).also { intr = it }
        val s = slam ?: PieceSlam(k).also { slam = it }
        val seg = Segmenter.segment(rgb, w, h, minFrac = .0015f)
        val up = upInCamera()
        val dets = s.track(seg, up)
        val now = SystemClock.elapsedRealtime()
        if (lastT != 0L) fps = fps * .8f + 200f / (now - lastT).coerceAtLeast(1)
        lastT = now
        // contornos para desenhar: gira do sensor para a tela em pé
        val rot = img.imageInfo.rotationDegrees
        fun upright(x: Double, y: Double): Offset = when (rot) {
            90 -> Offset((h - y).toFloat(), x.toFloat()); 180 -> Offset((w - x).toFloat(), (h - y).toFloat()); 270 -> Offset(y.toFloat(), (w - x).toFloat()); else -> Offset(x.toFloat(), y.toFloat())
        }
        val (uw, uh) = if (rot == 90 || rot == 270) h to w else w to h
        val pieces = s.pieces()
        val hint = when {
            dets.isEmpty() -> "Não vejo peças: aproxime e melhore a luz"
            s.lost -> "Perdi a posição: volte a mostrar peças já vistas"
            s.map.size < 2 -> "Ótimo! Agora ande em volta devagar"
            pieces.isEmpty() -> "Continue: cada peça precisa aparecer em 3 quadros"
            else -> "${pieces.size} peças no modelo — mostre os outros lados para refinar"
        }
        publish(MarkerStatus(hint, !s.lost, s.map.size, pieces, dets.map { d -> d.imgCorners.map { upright(it[0], it[1]) } to d.color }, uw, uh, fps, up != null))
    }
}
