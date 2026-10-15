package com.brastoy.brascan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brastoy.brascan.scan.Detector
import com.brastoy.brascan.scan.Layout
import com.brastoy.brascan.scan.Orient
import com.brastoy.brascan.scan.Piece
import com.brastoy.brascan.scan.Placed
import com.brastoy.brascan.scan.SHAPES
import com.brastoy.brascan.scan.SHAPE_LABEL
import com.brastoy.brascan.scan.SIZES
import com.brastoy.brascan.scan.Vista
import com.brastoy.brascan.three.Camera
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.three.GlViewport
import com.brastoy.brascan.three.floorGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

class ScanState {
    var image by mutableStateOf<Bitmap?>(null)
    var vista by mutableStateOf(Vista.FRENTE)
    var shape by mutableStateOf("quadrado")
    var selected by mutableIntStateOf(-1)
    var busy by mutableStateOf(false)
    val pieces = mutableStateListOf<Piece>()
    private var nextId = 1
    fun newId() = nextId++
    fun aspect() = image?.let { it.width.toFloat() / it.height } ?: 1f
    private var cacheKey: Any? = null
    private var cache: List<Placed> = emptyList()
    /** Posições finais (com colisão resolvida), recalculadas só quando peças/vista/seleção mudam. */
    fun placed(): List<Placed> {
        val key = Triple(pieces.toList(), vista, selected)
        if (key != cacheKey) { cache = Layout.solve(Layout.place(pieces, vista, aspect()), selected); cacheKey = key }
        return cache
    }
    fun sceneKey(): Any = Triple(pieces.toList(), vista, selected)
    /** Substitui as peças pelas reconstruídas no scanner 3D (pose real, sem foto). */
    fun importScan(found: List<com.brastoy.brascan.vision.Piece3D>) {
        image = null; selected = -1; pieces.clear()
        val list = Layout.fromScan(found, nextId); nextId += list.size; pieces.addAll(list)
    }
    fun update(id: Int, f: (Piece) -> Piece) { val i = pieces.indexOfFirst { it.id == id }; if (i >= 0) pieces[i] = f(pieces[i]) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(st: ScanState, openAr: () -> Unit, open3D: () -> Unit) {
    val ctx = LocalContext.current
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingLoad by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> pendingLoad = u } }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingLoad = photoUri }

    pendingLoad?.let { uri ->
        androidx.compose.runtime.LaunchedEffect(uri) {
            st.busy = true
            val bmp = withContext(Dispatchers.IO) { runCatching { decode(ctx, uri) }.getOrNull() }
            if (bmp == null) toast(ctx, "Não consegui abrir a imagem") else {
                st.image = bmp; st.pieces.clear(); st.selected = -1
                val found = withContext(Dispatchers.Default) { Detector.detect(bmp, st.newId()) }
                repeat(found.size) { st.newId() }
                st.pieces.addAll(found)
                toast(ctx, if (found.isEmpty()) "Nenhuma peça colorida achada — toque na foto para marcar" else "${found.size} peças detectadas. Revise e veja em 3D")
            }
            st.busy = false; pendingLoad = null
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Label("Scanner 3D (SLAM)")
            Text("Ande em volta da construção com a câmera: o celular rastreia a própria posição, mede a profundidade e monta as peças em 3D com a orientação real.",
                color = Color(0xFFC9D6EA), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Button(onClick = openAr, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(10.dp)); Text("Escanear em 3D", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Label("Ou por foto · 1 · Como a foto foi tirada")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Vista.entries.forEachIndexed { i, v ->
                    SegmentedButton(selected = st.vista == v, onClick = { st.vista = v }, shape = SegmentedButtonDefaults.itemShape(i, Vista.entries.size)) { Text(v.label) }
                }
            }
            Text(if (st.vista == Vista.FRENTE) "De frente a foto dá largura e altura: as peças viram paredes em pé." else "De cima a foto dá largura e profundidade: as peças ficam deitadas no chão.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
                    val f = File(dir, "brascan_${System.currentTimeMillis()}.jpg")
                    photoUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                    runCatching { shoot.launch(photoUri!!) }.onFailure { toast(ctx, "Câmera indisponível") }
                }, Modifier.weight(1f).height(52.dp)) { Icon(Icons.Rounded.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Câmera") }
                FilledTonalButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Galeria")
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Label("2 · Revise as peças (toque para marcar/desmarcar)")
            Spacer(Modifier.height(8.dp))
            PhotoMarker(st)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SHAPES.forEach { s -> FilterChip(selected = st.shape == s, onClick = { st.shape = s }, label = { Text(SHAPE_LABEL.getValue(s)) }) }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(enabled = st.image != null && !st.busy, onClick = {
                    st.image?.let { b -> st.pieces.clear(); val found = Detector.detect(b, st.newId()); repeat(found.size) { st.newId() }; st.pieces.addAll(found); toast(ctx, "${found.size} peças detectadas") }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Detectar") }
                OutlinedButton(onClick = { st.pieces.clear(); st.selected = -1 }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text("Limpar") }
            }
        }

        Button(onClick = open3D, enabled = st.pieces.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(10.dp)); Text("Ver modelo 3D (${st.pieces.size} peças)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PhotoMarker(st: ScanState) {
    val img = st.image
    if (img == null) {
        Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A1220)).border(1.dp, Palette.stroke, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(if (st.busy) "Processando…" else "Tire ou escolha uma foto da construção", color = Palette.muted)
        }
        return
    }
    val bmp = remember(img) { img.asImageBitmap() }
    Box(Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height).clip(RoundedCornerShape(16.dp))) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize().pointerInput(img, st.shape) {
            detectTapGestures { p ->
                val x = p.x / size.width; val y = p.y / size.height
                val hit = st.pieces.lastOrNull { kotlin.math.abs(it.cx - x) < it.w / 2 && kotlin.math.abs(it.cy - y) < it.h / 2 }
                    ?: st.pieces.firstOrNull { hypot(it.cx - x, it.cy - y) < .04f }
                if (hit != null) st.pieces.remove(hit) else {
                    val side = st.pieces.map { min(it.w, it.h) }.sorted().getOrNull(st.pieces.size / 2) ?: .12f
                    val px = img.getPixel((x * img.width).toInt().coerceIn(0, img.width - 1), (y * img.height).toInt().coerceIn(0, img.height - 1))
                    st.pieces.add(Piece(st.newId(), x, y, side * img.height / img.width, side, st.shape, color = px))
                }
            }
        }) {
            st.pieces.forEach { p ->
                val tl = Offset((p.cx - p.w / 2) * size.width, (p.cy - p.h / 2) * size.height)
                val sz = Size(p.w * size.width, p.h * size.height)
                drawRect(Color(p.color).copy(alpha = .25f), tl, sz)
                drawRect(Color.White, tl, sz, style = Stroke(width = 4f))
                drawCircle(Color(p.color), 9f, Offset(p.cx * size.width, p.cy * size.height))
            }
        }
    }
}

/** Modelo 3D editável: toque numa peça para selecionar e ajustar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(st: ScanState) {
    val ctx = LocalContext.current
    val camera = remember { Camera(yaw = .35f, pitch = .3f, distance = 5f, target = V3(0f, .5f, 0f)) }
    val demo = st.pieces.isEmpty()
    val demoPlaced = remember { Layout.solve(demoModel(), -1) }
    // Enquadra a câmera no tamanho do modelo sempre que ele muda.
    androidx.compose.runtime.LaunchedEffect(st.pieces.size, st.vista) {
        val pts = (if (demo) demoPlaced else st.placed()).map { it.center }
        if (pts.isEmpty()) return@LaunchedEffect
        val c = V3(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size, pts.sumOf { it.y.toDouble() }.toFloat() / pts.size, pts.sumOf { it.z.toDouble() }.toFloat() / pts.size)
        val r = pts.maxOf { (it - c).len() } + .8f
        camera.target = c; camera.distance = (r * 2.6f).coerceIn(3f, 30f)
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            GlViewport(Modifier.fillMaxSize(), camera, key = if (demo) "demo" else st.sceneKey(), autoRotate = st.selected < 0, onTap = { if (!demo) st.selected = it }) { s ->
                s.floorGrid(8, .5f)
                val placed = if (demo) demoPlaced else st.placed()
                Layout.draw(s, placed, st.selected)
            }
            Column(Modifier.padding(14.dp).align(Alignment.TopStart)) {
                Text(if (demo) "Exemplo" else "Seu modelo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(if (demo) "Escaneie uma foto na aba Scanner para ver o seu em 3D" else "${st.pieces.size} peças · toque numa peça para editar",
                    color = Palette.muted, fontSize = 12.sp)
            }
        }
        val sel = st.pieces.firstOrNull { it.id == st.selected }
        GlassCard(Modifier.fillMaxWidth().padding(12.dp)) {
            if (sel == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(enabled = !demo, onClick = { copy(ctx, Layout.json(st.placed())) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copiar JSON")
                    }
                    Button(enabled = !demo, onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Layout.json(st.placed())), "Enviar para o MK-7"))
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Enviar MK-7") }
                }
                Text("Arraste: girar · pinça: zoom · toque duplo: recentralizar", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else PieceEditor(st, sel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PieceEditor(st: ScanState, p: Piece) {
    val placed = st.placed().firstOrNull { it.piece.id == p.id }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(p.color)))
        Text("  ${SHAPE_LABEL[p.shape]} · ${p.size}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { st.pieces.removeAll { it.id == p.id }; st.selected = -1 }) { Icon(Icons.Rounded.Delete, "Excluir") }
        OutlinedButton(onClick = { st.selected = -1 }) { Text("Pronto") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SHAPES.forEach { s -> FilterChip(selected = p.shape == s, onClick = { st.update(p.id) { it.copy(shape = s) } }, label = { Text(SHAPE_LABEL.getValue(s)) }) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SIZES.forEach { s -> FilterChip(selected = p.size == s, onClick = { st.update(p.id) { it.copy(size = s) } }, label = { Text(s) }) }
        Palette.tiles.forEach { c -> Box(Modifier.padding(top = 10.dp).size(28.dp).clip(CircleShape).background(Color(c)).border(if (p.color == c) 3.dp else 0.dp, Color.White, CircleShape).pointerInput(c) { detectTapGestures { st.update(p.id) { it.copy(color = c) } } }) }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Orient.entries.forEachIndexed { i, o ->
            SegmentedButton(selected = placed?.orient == o, onClick = { st.update(p.id) { it.copy(orient = o) } }, shape = SegmentedButtonDefaults.itemShape(i, Orient.entries.size)) { Text(o.label, fontSize = 12.sp) }
        }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Stepper("Fundo", { st.update(p.id) { it.copy(depth = it.depth - .5f) } }, { st.update(p.id) { it.copy(depth = it.depth + .5f) } })
        Stepper("Altura", { st.update(p.id) { it.copy(lift = it.lift - .5f) } }, { st.update(p.id) { it.copy(lift = it.lift + .5f) } })
        FilledTonalIconButton(onClick = { st.update(p.id) { it.copy(turn = (it.turn + 1) % 4) } }) { Icon(Icons.Rounded.RotateRight, "Girar") }
    }
}

@Composable
private fun Stepper(label: String, minus: () -> Unit, plus: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    FilledTonalIconButton(onClick = minus, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "$label -") }
    Text(label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    FilledTonalIconButton(onClick = plus, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "$label +") }
}

/** Casinha de exemplo para a aba 3D não ficar vazia antes do primeiro scan. */
private fun demoModel(): List<Placed> {
    val e = 1f; val h = e / 2; val t = e * .866f
    val c = Palette.tiles
    var id = 10_000
    fun p(shape: String, color: Int, at: V3, o: Orient, turn: Int = 0) =
        Layout.fixed(Piece(id++, shape = shape, size = "grande", color = color, orient = o, turn = turn), at, o)
    return listOf(
        p("quadrado", c[0], V3(-h, h, e), Orient.FRENTE), p("porta", c[1], V3(h, h, e), Orient.FRENTE),
        p("quadrado", c[1], V3(-h, h, -e), Orient.FRENTE), p("quadrado", c[0], V3(h, h, -e), Orient.FRENTE),
        p("quadrado", c[2], V3(-e, h, h), Orient.LADO), p("quadrado", c[3], V3(-e, h, -h), Orient.LADO),
        p("janela", c[2], V3(e, h, h), Orient.LADO), p("quadrado", c[3], V3(e, h, -h), Orient.LADO),
        p("equilatero", c[4], V3(-h, e + t / 3, e), Orient.FRENTE), p("equilatero", c[5], V3(h, e + t / 3, e), Orient.FRENTE),
        p("equilatero", c[5], V3(-h, e + t / 3, -e), Orient.FRENTE), p("equilatero", c[4], V3(h, e + t / 3, -e), Orient.FRENTE),
        p("quadrado", c[1], V3(-h, .0325f, h), Orient.DEITADA), p("moldura", c[0], V3(h, .0325f, h), Orient.DEITADA),
        p("quadrado", c[0], V3(-h, .0325f, -h), Orient.DEITADA), p("quadrado", c[1], V3(h, .0325f, -h), Orient.DEITADA),
        p("retangulo", c[3], V3(e * 2.2f, .25f, 0f), Orient.LADO), p("isoceles", c[2], V3(-e * 2f, h, e * 1.2f), Orient.FRENTE), p("L", c[4], V3(e * 2f, .0325f, e * 1.6f), Orient.DEITADA),
    )
}

private fun decode(ctx: Context, uri: Uri): Bitmap {
    val max = 1400
    return if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder já aplica a rotação EXIF das fotos da câmera.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val s = min(1f, max.toFloat() / maxOf(info.size.width, info.size.height))
            d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val b = ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: error("imagem inválida")
        val s = min(1f, max.toFloat() / maxOf(b.width, b.height))
        if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BraScan", text))
    toast(ctx, "JSON copiado")
}

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
