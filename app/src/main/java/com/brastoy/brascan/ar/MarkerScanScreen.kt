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
import kotlin.math.abs
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
    /** Peça que a dica principal quer que você mostre (já em pé, pixels da imagem analisada). */
    val target: Offset? = null,
    val advice: List<String> = emptyList(),
    val resuming: Boolean = false,
    /** Ajustes globais aceitos (laços fechados) e o erro de reprojeção depois do último. */
    val loops: Int = 0,
    val reprojPx: Double = Double.NaN,
    /** Gravação de diagnóstico em andamento e quantos quadros já foram guardados. */
    val recording: Boolean = false,
    val recorded: Int = 0,
)

/**
 * Scanner 3D sem ARCore/Google: CameraX + sensor de gravidade + [PieceSlam] (as peças são os marcadores).
 * Funciona em qualquer Android com câmera.
 */
@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MarkerScanScreen(initialMap: String? = null, onClose: () -> Unit, onDone: (List<Piece3D>, String?) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }
    var status by remember { mutableStateOf(MarkerStatus()) }
    // como a pré-visualização foi desenhada (SurfaceView ou TextureView): decide como a RA fica por cima dela
    var previewIsSurface by remember { mutableStateOf<Boolean?>(null) }
    val engine = remember { Engine(ctx, initialMap, { status = it }) { previewIsSurface = it } }
    DisposableEffect(Unit) { engine.startSensors(); onDispose { engine.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permission) {
            AndroidView(factory = { c ->
                PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.PERFORMANCE }.also { pv -> engine.bind(owner, pv) }
            }, modifier = Modifier.fillMaxSize())
            // peças 3D por cima das peças de verdade (RA sem ARCore): as já confirmadas sólidas, as novas fantasma.
            // Só nasce depois de saber como a pré-visualização é desenhada: sobre SurfaceView a RA fica entre a
            // câmera e os botões; sobre TextureView precisa ficar por cima da janela toda.
            previewIsSurface?.let { surface ->
                AndroidView(factory = { c -> ArOverlayView(c, onTopOfWindow = !surface).also { engine.overlay = it } }, modifier = Modifier.fillMaxSize())
            }
            // contornos das peças reconhecidas neste quadro, desenhados por cima da imagem
            Canvas(Modifier.fillMaxSize()) {
                val s = status; val k = max(size.width / s.imgW, size.height / s.imgH)
                val ox = (size.width - s.imgW * k) / 2; val oy = (size.height - s.imgH * k) / 2
                s.target?.let { t ->
                    val c = Offset(ox + t.x * k, oy + t.y * k)
                    drawCircle(Color(0xFFFFC531), 46f, c, style = Stroke(width = 7f)); drawCircle(Color(0xFFFFC531), 10f, c)
                }
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
                    Text(if (!permission) "Permita o uso da câmera" else status.advice.firstOrNull() ?: status.hint, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    status.advice.drop(1).forEach { Text("· $it", color = Color(0xFFC9D6EA), fontSize = 12.sp) }
                    Text("Sem Google/ARCore · peças como marcadores · ${status.mapped} no mapa · ${"%.0f".format(status.fps)} quadros/s" +
                        (if (status.loops > 0) " · ${status.loops} laços fechados (%.1f px)".format(status.reprojPx) else "") +
                        (if (status.gravity) "" else " · sem sensor de gravidade"), color = Palette.muted, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { engine.reset() }, modifier = Modifier.height(54.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (status.resuming) "Ignorar salvo" else "Recomeçar") }
            // diagnóstico: grava quadros + decisões do SLAM num .zip para analisar no PC o que deu errado
            FilledTonalButton(onClick = { if (status.recording) engine.stopRecording { Diagnostico.share(ctx, it) } else engine.startRecording() }, modifier = Modifier.height(54.dp)) {
                Text(if (status.recording) "⏹ ${status.recorded}" else "⏺ Gravar", maxLines = 1)
            }
            Button(enabled = status.pieces.isNotEmpty(), onClick = { engine.finish()?.let { (ps, map) -> onDone(ps, map) } ?: onDone(status.pieces, engine.exportMap()) }, modifier = Modifier.weight(1f).height(54.dp)) {
                Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(8.dp)); Text("3D (${status.pieces.size})", fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Câmera + sensores + SLAM numa thread de análise só (quadros antigos são descartados). */
@OptIn(ExperimentalCamera2Interop::class)
private class Engine(private val ctx: Context, private var initialMap: String?, private val publish: (MarkerStatus) -> Unit, private val onPreviewKind: (Boolean) -> Unit) : SensorEventListener {
    private val exec = Executors.newSingleThreadExecutor()
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    /** Amostras de gravidade com carimbo de tempo (ns do relógio dos sensores): o quadro usa a mais próxima dele. */
    private val gravRing = ArrayDeque<Pair<Long, FloatArray>>()
    private val gravLock = Any()
    private var gravEma: FloatArray? = null
    private var gravFromAccel = false
    /** O relógio dos quadros da câmera é o mesmo dos sensores (SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME)? */
    @Volatile private var camClockRealtime = false
    @Volatile private var slam: PieceSlam? = null
    private var intr: Intrinsics? = null
    private var sensorOrientation = 90
    private var provider: ProcessCameraProvider? = null
    private var lastT = 0L; private var fps = 0f
    /** Sobreposição 3D (RA) alimentada a cada quadro analisado. */
    @Volatile var overlay: ArOverlayView? = null
    @Volatile private var diag: Diagnostico? = null
    @Volatile private var lastRot = 0

    fun startRecording() { exec.execute { diag = Diagnostico(ctx) } }
    fun stopRecording(then: (java.io.File) -> Unit) {
        exec.execute { diag?.let { d -> diag = null; val zip = d.finish(slam?.export()); ContextCompat.getMainExecutor(ctx).execute { then(zip) } } }
    }

    fun startSensors() {
        // gravidade filtrada pelo sistema; sem ela, acelerômetro cru com média móvel e rejeição de solavancos
        val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = g ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        gravFromAccel = g == null
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
    override fun onSensorChanged(e: SensorEvent) {
        var v = e.values.clone()
        if (gravFromAccel) {
            // mão/passos somam 2-3 m/s² à gravidade: descarta amostras longe de 9,81 e suaviza o resto
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (abs(n - 9.81f) > 1.5f) return
            val ema = gravEma
            v = if (ema == null) v else FloatArray(3) { ema[it] + (v[it] - ema[it]) * .12f }
            gravEma = v
        }
        synchronized(gravLock) { gravRing.addLast(e.timestamp to v); while (gravRing.size > 64) gravRing.removeFirst() }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Gravidade no instante do quadro ([tsNs] no relógio dos sensores), ou a mais recente se os relógios diferem. */
    private fun gravityAt(tsNs: Long): FloatArray? = synchronized(gravLock) {
        if (gravRing.isEmpty()) return null
        if (!camClockRealtime) return gravRing.last().second
        gravRing.minByOrNull { abs(it.first - tsNs) }?.second
    }

    fun stop() { sm.unregisterListener(this); provider?.unbindAll(); exec.shutdown() }
    /** Recomeçar do zero (também serve para ignorar um mapa salvo que não está batendo). */
    fun reset() { exec.execute { initialMap = null; slam = intr?.let { PieceSlam(it) } } }

    /** Ajuste global final + peças + mapa, na thread de análise (não cruza com o rastreio). */
    fun finish(): Pair<List<Piece3D>, String>? = runCatching { exec.submit<Pair<List<Piece3D>, String>?> { slam?.let { it.optimize(); it.pieces() to it.export() } }.get() }.getOrNull()

    /** Mapa atual em texto (lido na thread de análise para não cruzar com o rastreio). */
    fun exportMap(): String? = runCatching { exec.submit<String?> { slam?.export() }.get() }.getOrNull()

    fun bind(owner: androidx.lifecycle.LifecycleOwner, pv: PreviewView) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get(); provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            // 640×480 de verdade (setTargetResolution era interpretado já girado e vinha 960×720+ reduzido por vizinho)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(Size(640, 480), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img ->
                // uma exceção no quadro não pode derrubar a câmera, mas também não pode sumir em silêncio
                try { process(img) } catch (e: Exception) { android.util.Log.w("BraScan", "quadro do scanner falhou", e) } finally { img.close() }
            }
            p.unbindAll()
            val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            // PreviewView só cria o filho (SurfaceView ou TextureView) ao receber a superfície: espera ele aparecer
            fun kind(): Boolean? = (0 until pv.childCount).map { pv.getChildAt(it) }.firstNotNullOfOrNull { v -> if (v is android.view.SurfaceView) true else if (v is android.view.TextureView) false else null }
            var tries = 0
            fun poll() { kind()?.let { onPreviewKind(it) } ?: if (tries++ < 40) pv.postDelayed({ poll() }, 100) else onPreviewKind(false) }
            poll()
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            sensorOrientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            camClockRealtime = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
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
    private fun upInCamera(tsNs: Long): DoubleArray? {
        val g = gravityAt(tsNs) ?: return null
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
        // gravidade do instante do quadro, não de depois da segmentação (a mão continua girando enquanto processa)
        val up = upInCamera(img.imageInfo.timestamp)
        val outW = 480
        val (rgb, w, h) = ArScanView.yuvToArgb(image, outW)
        val k = intr ?: intrinsics(w, h).also { intr = it }
        val s = slam ?: PieceSlam(k).also { sl -> initialMap?.let { sl.restore(it) }; slam = sl }
        val t0 = SystemClock.elapsedRealtime()
        val seg = Segmenter.segment(rgb, w, h, minFrac = .0015f)
        val dets = s.track(seg, up, rgb)
        val now = SystemClock.elapsedRealtime()
        val rot0 = img.imageInfo.rotationDegrees; lastRot = rot0
        overlay?.frame = OverlayFrame(s.camera, s.lost, k, w, h, rot0, s.map.map { it.toPiece3D() to it.count })
        diag?.let { d -> runCatching { d.frame(rgb, w, h, rot0, k, up, s, dets, now - t0) } }
        if (lastT != 0L) fps = fps * .8f + 200f / (now - lastT).coerceAtLeast(1)
        lastT = now
        // contornos para desenhar: gira do sensor para a tela em pé
        val rot = img.imageInfo.rotationDegrees
        fun upright(x: Double, y: Double): Offset = when (rot) {
            90 -> Offset((h - y).toFloat(), x.toFloat()); 180 -> Offset((w - x).toFloat(), (h - y).toFloat()); 270 -> Offset(y.toFloat(), (w - x).toFloat()); else -> Offset(x.toFloat(), y.toFloat())
        }
        val (uw, uh) = if (rot == 90 || rot == 270) h to w else w to h
        val pieces = s.pieces()
        val adv = s.advice()
        val target = adv.firstOrNull()?.targetId?.let { id -> s.projectToImage(id) }?.let { upright(it[0], it[1]) }
        val hint = when {
            dets.isEmpty() -> "Não vejo peças: aproxime e melhore a luz"
            s.lost -> "Perdi a posição: volte a mostrar peças já vistas"
            s.map.size < 2 -> "Ótimo! Agora ande em volta devagar"
            pieces.isEmpty() -> "Continue andando devagar: cada peça precisa ser vista de ${PieceSlam.MIN_VIEWS} lugares"
            else -> "${pieces.size} peças no modelo — mostre os outros lados para refinar"
        }
        publish(MarkerStatus(hint, !s.lost, s.map.size, pieces, dets.map { d -> d.imgCorners.map { upright(it[0], it[1]) } to d.color }, uw, uh, fps, up != null,
            target, adv.map { it.text }, s.restoredMap && !s.relocalized,
            s.baReports.count { it.accepted }, s.baReports.lastOrNull { it.accepted }?.rmsAfter ?: Double.NaN,
            diag != null, diag?.frames ?: 0))
    }
}
