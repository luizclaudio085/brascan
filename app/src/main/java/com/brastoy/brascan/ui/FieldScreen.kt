package com.brastoy.brascan.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brastoy.brascan.three.Camera
import com.brastoy.brascan.three.Line3
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.three.Viewport3D
import com.brastoy.brascan.three.floorGrid
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Estado do magnetômetro com filtro passa-baixa e rastro 3D da ponta do vetor. */
class FieldState {
    var b by mutableStateOf(floatArrayOf(0f, 0f, 0f))
    var accuracy by mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    var baseline by mutableStateOf<FloatArray?>(null)
    var peak by mutableFloatStateOf(0f)
    val history = mutableStateListOf<Float>()
    val trail = ArrayDeque<V3>()
    val magnitude get() = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
    val delta get() = baseline?.let { sqrt((b[0] - it[0]).sq() + (b[1] - it[1]).sq() + (b[2] - it[2]).sq()) }
    private fun Float.sq() = this * this

    private var n = 0

    fun push(v: FloatArray) {
        val a = .25f
        b = floatArrayOf(b[0] + a * (v[0] - b[0]), b[1] + a * (v[1] - b[1]), b[2] + a * (v[2] - b[2]))
        peak = max(peak, delta ?: 0f)
        if (n++ % 3 != 0) return
        val m = magnitude
        history.add(m); if (history.size > 160) history.removeAt(0)
        trail.addLast(world(b)); if (trail.size > 120) trail.removeFirst()
    }

    /** Coordenadas do aparelho (X direita, Y topo, Z para fora da tela) → mundo com o celular deitado. */
    fun world(v: FloatArray, scale: Float = 1 / 22f) = V3(v[0] * scale, v[2] * scale, -v[1] * scale)
}

@Composable
fun FieldScreen(state: FieldState, hasSensor: Boolean) {
    val camera = remember { Camera(yaw = .8f, pitch = .42f, distance = 5.2f, target = V3(0f, -.3f, 0f)) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Campo magnético")
                    Text(String.format(Locale.US, "%.1f", state.magnitude), fontSize = 54.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("µT (microtesla)", color = Palette.muted, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    val d = state.delta
                    Label(if (d == null) "Detector de ímã" else "Variação")
                    Text(if (d == null) "—" else String.format(Locale.US, "%+.1f", d), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = when { d == null -> Palette.muted; d > 40 -> Palette.pink; d > 12 -> Color(0xFFFFC531); else -> Palette.y })
                    Text(when { d == null -> "zere para usar"; d > 40 -> "ímã muito perto!"; d > 12 -> "ímã por perto"; else -> "nada por perto" }, color = Palette.muted, fontSize = 12.sp)
                }
            }
            if (!hasSensor) Text("Este aparelho não tem magnetômetro (comum no emulador). Use um celular de verdade.", color = Palette.pink, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            else if (state.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) Text("Sensor descalibrado: faça um 8 no ar com o celular.", color = Color(0xFFFFC531), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Label("Vetor 3D — arraste para girar, pinça para zoom")
            Spacer(Modifier.height(8.dp))
            Viewport3D(Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(16.dp)), camera) { s ->
                s.floorGrid(4, .5f)
                s.box(V3(0f, .04f, 0f), V3(.7f, .08f, 1.4f), Color(0xFF26324A), outline = Color(0xFF55688A))
                s.box(V3(0f, .085f, 0f), V3(.6f, .01f, 1.25f), Color(0xFF0B1220))
                s.lines += Line3(V3.ZERO_UP, V3(1.4f, .09f, 0f), Palette.x.copy(alpha = .8f), 3f)
                s.lines += Line3(V3.ZERO_UP, V3(0f, .09f, -1.4f), Palette.y.copy(alpha = .8f), 3f)
                s.lines += Line3(V3.ZERO_UP, V3(0f, 1.4f, 0f), Palette.z.copy(alpha = .8f), 3f)
                val tr = state.trail.toList()
                for (i in 1 until tr.size) s.lines += Line3(tr[i - 1] + V3.ZERO_UP, tr[i] + V3.ZERO_UP, Palette.accent.copy(alpha = i / tr.size.toFloat() * .7f), 2.5f)
                val tip = state.world(state.b)
                if (tip.len() > .05f) s.arrow(V3.ZERO_UP, tip + V3.ZERO_UP, Palette.pink, 6f)
                state.baseline?.let { s.lines += Line3(V3.ZERO_UP, state.world(it) + V3.ZERO_UP, Color(0x88FFFFFF), 2f) }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Palette.x, "X"); Legend(Palette.y, "Y"); Legend(Palette.z, "Z"); Legend(Palette.pink, "Campo B"); Legend(Palette.accent, "rastro")
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Label("Eixos")
            listOf(Triple("X", state.b[0], Palette.x), Triple("Y", state.b[1], Palette.y), Triple("Z", state.b[2], Palette.z)).forEach { (n, v, c) ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = c, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    LinearProgressIndicator(progress = { (abs(v) / 100f).coerceIn(0f, 1f) }, color = c, trackColor = c.copy(alpha = .12f),
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)))
                    Text(String.format(Locale.US, "%+7.1f", v), fontFamily = FontFamily.Monospace, color = Color.White, modifier = Modifier.width(78.dp).padding(start = 10.dp))
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Label("Histórico")
            Spark(state.history, Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.US, "pico de variação %.1f µT", state.peak), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { state.baseline = state.b.clone(); state.peak = 0f }) {
                    Icon(Icons.Rounded.CenterFocusStrong, null); Spacer(Modifier.width(6.dp)); Text("Zerar ambiente")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Legend(c: Color, t: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.width(10.dp).height(10.dp)) { drawCircle(c) }
    Text(" $t", color = Palette.muted, fontSize = 12.sp)
}

@Composable
private fun Spark(points: List<Float>, modifier: Modifier) = Canvas(modifier) {
    if (points.size < 2) return@Canvas
    val lo = points.min() - 2; val hi = max(points.max() + 2, lo + 10)
    fun pt(i: Int) = Offset(i * size.width / (points.size - 1), size.height * (1 - (points[i] - lo) / (hi - lo)))
    val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until points.size) lineTo(pt(i).x, pt(i).y) }
    val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
    drawPath(fill, Brush.verticalGradient(listOf(Palette.accent.copy(alpha = .35f), Color.Transparent)))
    drawPath(line, Palette.accent, style = Stroke(width = 4f))
}

/** Liga o listener do sensor enquanto a tela existir. */
@Composable
fun rememberField(ctx: Context = LocalContext.current): Pair<FieldState, Boolean> {
    val state = remember { FieldState() }
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    DisposableEffect(sensor) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { state.push(e.values) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) { state.accuracy = a }
        }
        sensor?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l) }
    }
    return state to (sensor != null)
}
