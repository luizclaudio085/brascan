package com.brastoy.brascan.three

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI

/**
 * Viewport 3D em Canvas: arraste para girar, pinça para zoom, toque duplo para recentralizar.
 * Gira sozinho devagar enquanto ninguém mexe.
 */
@Composable
fun Viewport3D(
    modifier: Modifier,
    camera: Camera,
    autoRotate: Boolean = true,
    onTap: ((Int) -> Unit)? = null,
    build: (Scene) -> Unit,
) {
    var frame by remember { mutableLongStateOf(0L) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var projected by remember { mutableStateOf<List<Projected>>(emptyList()) }
    val home = remember { Triple(camera.yaw, camera.pitch, camera.distance) }

    LaunchedEffect(autoRotate) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L && autoRotate && System.currentTimeMillis() - lastTouch > 2500) camera.yaw += (t - last) / 1e9f * .25f
            last = t; frame = t
        }
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    lastTouch = System.currentTimeMillis()
                    camera.yaw -= pan.x * .008f
                    camera.pitch = (camera.pitch + pan.y * .006f).coerceIn(-.15f, (PI / 2 - .05).toFloat())
                    camera.distance = (camera.distance / zoom).coerceIn(2.5f, 40f)
                }
            }
            .pointerInput(onTap) {
                detectTapGestures(
                    onDoubleTap = { camera.yaw = home.first; camera.pitch = home.second; camera.distance = home.third; lastTouch = System.currentTimeMillis() },
                    onTap = { p -> lastTouch = System.currentTimeMillis(); onTap?.invoke(Renderer.hit(projected, p)) },
                )
            }
    ) {
        frame // lê o estado para redesenhar a cada quadro
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1730), Color(0xFF04070E))))
        val scene = Scene()
        build(scene)
        val f = Renderer.project(scene, camera, size.width, size.height)
        projected = f.faces
        for ((a, b, l) in f.under) drawLine(l.color, a, b, strokeWidth = l.width)
        for (s in f.shadows) drawPath(Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) }; close() }, Color.Black, alpha = .35f)
        val faces = f.faces
        for (p in faces) {
            val path = Path().apply { moveTo(p.pts[0].x, p.pts[0].y); for (i in 1 until p.pts.size) lineTo(p.pts[i].x, p.pts[i].y); close() }
            val c = p.face.color
            val lit = Color(c.red * p.shade, c.green * p.shade, c.blue * p.shade, 1f)
            drawPath(path, lit, alpha = p.face.alpha)
            p.face.outline?.let { drawPath(path, it, style = Stroke(width = 2.2f)) }
        }
        for ((a, b, l) in f.lines) drawLine(l.color, a, b, strokeWidth = l.width, cap = StrokeCap.Round)
    }
}

/** Piso quadriculado que some com a distância (névoa), usado em todas as cenas. */
fun Scene.floorGrid(half: Int = 5, step: Float = 1f, color: Color = Color(0xFF55AAFF)) {
    val e = half * step
    for (i in -half..half) for (j in -half until half) {
        val k = i * step; val a = j * step; val b = a + step
        val fade = (1f - kotlin.math.max(kotlin.math.abs(k), kotlin.math.abs(a + step / 2)) / e).coerceIn(0f, 1f)
        val c = color.copy(alpha = (if (i == 0) .4f else .22f) * fade * fade)
        under += Line3(V3(k, 0f, a), V3(k, 0f, b), c, 1.2f)
        under += Line3(V3(a, 0f, k), V3(b, 0f, k), c, 1.2f)
    }
}

/** Sombra simples: projeta o polígono no piso (y=0) a partir de uma luz alta. */
fun Scene.shadowOf(pts: List<V3>) { shadows += pts.map { V3(it.x - it.y * .35f, 0.002f, it.z - it.y * .25f) } }
