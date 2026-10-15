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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
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
import com.brastoy.brascan.vision.Assessment
import com.brastoy.brascan.vision.Catalog
import com.brastoy.brascan.vision.Confidence
import com.brastoy.brascan.vision.Evidence
import com.brastoy.brascan.vision.Inference
import com.brastoy.brascan.vision.Level
import com.brastoy.brascan.vision.Measure
import com.brastoy.brascan.three.Camera
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.three.GlViewport
import com.brastoy.brascan.three.floorGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    /** Mapa do scanner 3D (para continuar o escaneamento depois). */
    var slamMap by mutableStateOf<String?>(null)
    var projectId by mutableStateOf(com.brastoy.brascan.scan.ProjectStore.newId())
    var projectName by mutableStateOf("Sem nome")

    /** Substitui as peças pelas reconstruídas no scanner 3D (pose real, sem foto). */
    fun importScan(found: List<com.brastoy.brascan.vision.Piece3D>, map: String? = null) {
        image = null; selected = -1; pieces.clear()
        val list = Layout.fromScan(found, nextId); nextId += list.size; pieces.addAll(list)
        if (map != null) slamMap = map
    }

    fun toProject() = com.brastoy.brascan.scan.Project(projectName, System.currentTimeMillis(), vista, pieces.toList(), slamMap, image, projectId, rejected.toList())

    fun load(p: com.brastoy.brascan.scan.Project, id: String) {
        selected = -1; pieces.clear(); pieces.addAll(p.pieces); nextId = (p.pieces.maxOfOrNull { it.id } ?: 0) + 1
        vista = p.vista; image = p.photo; slamMap = p.slamMap; projectName = p.name; projectId = id
        rejected.clear(); rejected.addAll(p.rejected)
    }

    fun clear() { selected = -1; pieces.clear(); image = null; slamMap = null; rejected.clear(); projectName = "Sem nome"; projectId = com.brastoy.brascan.scan.ProjectStore.newId() }

    /** Hipóteses rejeitadas pelo usuário (chaves estáveis); as aceitas viram peças marcadas como hipótese. */
    val rejected = androidx.compose.runtime.mutableStateListOf<String>()
    private var hypKey: Any? = null
    private var hypCache: List<Inference.Hypothesis> = emptyList()

    /** Hipóteses ainda em aberto para o modelo atual (recalculadas quando o modelo muda). */
    fun hypotheses(): List<Inference.Hypothesis> {
        val key = listOf(sceneKey(), rejected.toList())
        if (key != hypKey) {
            val obs = placed().map { Measure.P(it.piece.id, it.piece.shape, it.piece.size, it.center, it.u, it.v, it.edge, it.piece.evidence) }
            hypCache = Inference.infer(obs).filter { it.key !in rejected }
            hypKey = key
        }
        return hypCache
    }

    /** Aceitar: as peças da hipótese entram no modelo (marcadas como hipótese) e as alternativas do grupo saem. */
    fun accept(h: Inference.Hypothesis) {
        for (o in hypotheses().filter { it.group == h.group && it.key != h.key }) rejected += o.key
        for (hp in h.pieces) pieces += Piece(newId(), shape = hp.shape, size = hp.size, color = 0xFFB9C4D6.toInt(), pos = hp.center, pu = hp.u, pv = hp.v,
            evidence = Evidence(Evidence.Source.HIPOTESE, accepted = true))
        if (h.pieces.isEmpty()) rejected += h.key // "interior vazio": decisão registrada, nada para adicionar
    }

    fun reject(h: Inference.Hypothesis) { rejected += h.key }

    /** Muda sempre que algo que vale salvar muda. */
    fun saveKey(): Any = listOf(pieces.toList(), vista, slamMap, image, projectName, rejected.toList())
    /** Toda edição feita pelo usuário conta como confirmação da peça. */
    fun update(id: Int, f: (Piece) -> Piece) {
        val i = pieces.indexOfFirst { it.id == id }; if (i < 0) return
        val np = f(pieces[i]); pieces[i] = np.copy(evidence = (np.evidence ?: com.brastoy.brascan.vision.Evidence(com.brastoy.brascan.vision.Evidence.Source.MANUAL)).copy(accepted = true))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(st: ScanState, openAr: () -> Unit, openArCore: () -> Unit, openResume: () -> Unit, open3D: () -> Unit) {
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
            Text("Ande devagar em volta da construção: o app reconhece as peças pela câmera e usa as medidas delas (e a gravidade do celular) para saber onde o celular está e montar tudo em 3D. Funciona sem Google/ARCore.",
                color = Color(0xFFC9D6EA), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Button(onClick = openAr, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(10.dp)); Text("Escanear em 3D", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            // ARCore só aparece se já estiver instalado: nunca pede para instalar nada do Google
            val arCoreInstalled = remember {
                runCatching { com.google.ar.core.ArCoreApk.getInstance().checkAvailability(ctx) == com.google.ar.core.ArCoreApk.Availability.SUPPORTED_INSTALLED }.getOrDefault(false)
            }
            if (st.slamMap != null) FilledTonalButton(onClick = openResume, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Continuar escaneamento salvo")
            }
            if (arCoreInstalled) androidx.compose.material3.TextButton(onClick = openArCore, modifier = Modifier.fillMaxWidth()) {
                Text("Usar ARCore do Google (opcional, este celular tem)", color = Palette.muted, fontSize = 13.sp)
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
                    st.pieces.add(Piece(st.newId(), x, y, side * img.height / img.width, side, st.shape, color = px, evidence = com.brastoy.brascan.vision.Evidence(com.brastoy.brascan.vision.Evidence.Source.MANUAL)))
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
private enum class Mode(val label: String) { MODELO("Modelo"), CONFIANCA("Confiança"), MEDIR("Medir"), RAIOX("Raio-X") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(st: ScanState) {
    val ctx = LocalContext.current
    val camera = remember { Camera(yaw = .35f, pitch = .3f, distance = 5f, target = V3(0f, .5f, 0f)) }
    val demo = st.pieces.isEmpty()
    val demoPlaced = remember { Layout.solve(demoModel(), -1) }
    var mode by remember { mutableStateOf(Mode.MODELO) }
    var pickA by remember { mutableIntStateOf(-1) }; var pickB by remember { mutableIntStateOf(-1) }
    var cutY by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var cutX by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var ghost by remember { mutableStateOf(false) }
    var explode by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // Enquadra a câmera no tamanho do modelo sempre que ele muda.
    androidx.compose.runtime.LaunchedEffect(st.pieces.size, st.vista) {
        val pts = (if (demo) demoPlaced else st.placed()).map { it.center }
        if (pts.isEmpty()) return@LaunchedEffect
        val c = V3(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size, pts.sumOf { it.y.toDouble() }.toFloat() / pts.size, pts.sumOf { it.z.toDouble() }.toFloat() / pts.size)
        val r = pts.maxOf { (it - c).len() } + .8f
        camera.target = c; camera.distance = (r * 2.6f).coerceIn(3f, 30f)
    }

    val placed = if (demo) demoPlaced else st.placed()
    val viewName = if (st.vista == Vista.CIMA) "cima" else "frente"
    fun evidenceOf(pl: Placed) = if (demo) null else pl.piece.evidence?.let { if (it.source == Evidence.Source.FOTO) it.copy(view = viewName) else it }
    val assess = placed.associate { it.piece.id to Confidence.assess(evidenceOf(it), (it.pushed * Catalog.SIDE_M).toDouble(), (it.edge * Catalog.SIDE_M).toDouble()) }
    val mpieces = placed.map { Measure.P(it.piece.id, it.piece.shape, it.piece.size, it.center, it.u, it.v, it.edge, evidenceOf(it)) }

    // Raio-X: explosão (só visual) e cortes
    val c0 = if (placed.isEmpty()) V3.ZERO else placed.fold(V3.ZERO) { acc, p -> acc + p.center } * (1f / placed.size)
    val shown = if (mode == Mode.RAIOX && explode > 0f) placed.map { it.copy(center = c0 + (it.center - c0) * (1f + explode)) } else placed
    val yMax = (shown.maxOfOrNull { it.center.y + it.edge } ?: 1f); val xMin = shown.minOfOrNull { it.center.x - it.edge } ?: -1f; val xMax = shown.maxOfOrNull { it.center.x + it.edge } ?: 1f
    val clipY = if (mode == Mode.RAIOX && cutY < .999f) yMax * cutY else 9000f
    val clipX = if (mode == Mode.RAIOX && cutX < .999f) xMin + (xMax - xMin) * cutX else 9000f
    val highlight = when (mode) { Mode.MEDIR -> if (pickB >= 0) pickB else pickA; else -> st.selected }
    val hyps = if (demo || mode !in setOf(Mode.CONFIANCA, Mode.RAIOX)) emptyList() else st.hypotheses()
    var focusHyp by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            GlViewport(Modifier.fillMaxSize(), camera, key = listOf(if (demo) "demo" else st.sceneKey(), mode, ghost, explode, highlight, pickA, hyps.map { it.key }, focusHyp),
                autoRotate = st.selected < 0 && pickA < 0, clipY = clipY, clipX = clipX,
                onTap = { id ->
                    if (demo) return@GlViewport
                    when (mode) {
                        Mode.MEDIR -> if (id < 0) { pickA = -1; pickB = -1 } else if (pickA < 0 || pickB >= 0) { pickA = id; pickB = -1 } else if (id != pickA) pickB = id
                        else -> st.selected = id
                    }
                }) { s ->
                s.floorGrid(8, .5f)
                Layout.draw(s, shown, highlight,
                    colorOf = { pl -> if (mode == Mode.CONFIANCA) assess[pl.piece.id]?.level?.argb ?: pl.piece.color else if (mode == Mode.MEDIR && pl.piece.id == pickA) 0xFF7FD6FF.toInt() else pl.piece.color },
                    ghostOf = { pl -> if (mode == Mode.RAIOX && (ghost || (st.selected >= 0 && pl.piece.id != st.selected))) .18f else 1f })
                // hipóteses: cinza e transparentes (amarelas quando em foco) — nunca parecem peça vista
                for (h in hyps) for (hp in h.pieces) com.brastoy.brascan.three.TileMesh.build(s, hp.shape, hp.size, hp.center, hp.u, hp.v, hp.edge,
                    Color(if (h.key == focusHyp) 0xFFFFC531.toInt() else Level.CINZA.argb), -1, false, shadow = false, ghost = if (h.key == focusHyp) .55f else .28f)
            }
            Column(Modifier.padding(14.dp).align(Alignment.TopStart)) {
                Text(if (demo) "Exemplo" else "Seu modelo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(when {
                    demo -> "Escaneie uma foto na aba Scanner para ver o seu em 3D"
                    mode == Mode.MEDIR -> "Toque em duas peças para medir entre elas"
                    mode == Mode.CONFIANCA -> "Toque numa peça para ver o porquê da cor"
                    mode == Mode.RAIOX -> "Visualização: não enxerga através de paredes reais" + if (hyps.isNotEmpty()) " · ${hyps.size} hipóteses em cinza" else ""
                    else -> "${st.pieces.size} peças · toque numa peça para editar"
                }, color = Palette.muted, fontSize = 12.sp)
            }
        }
        val sel = st.pieces.firstOrNull { it.id == st.selected }
        GlassCard(Modifier.fillMaxWidth().padding(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Mode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = mode == m, onClick = { mode = m; pickA = -1; pickB = -1 }, shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)) { Text(m.label, fontSize = 12.sp, maxLines = 1) }
                }
            }
            when (mode) {
                Mode.MODELO -> if (sel == null) ExportPanel(st, demo) else PieceEditor(st, sel)
                Mode.CONFIANCA -> ConfidencePanel(st, sel, assess, demo)
                Mode.MEDIR -> MeasurePanel(mpieces, pickA, pickB)
                Mode.RAIOX -> Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    SliderRow("Corte de altura", cutY) { cutY = it }
                    SliderRow("Corte lateral", cutX) { cutX = it }
                    SliderRow("Explodir montagem", explode / 1.5f) { explode = it * 1.5f }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Paredes fantasma", color = Color.White, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = ghost, onCheckedChange = { ghost = it })
                    }
                    Text(if (st.selected >= 0) "Peça isolada: as outras ficam transparentes. Toque fora para soltar." else "Toque numa peça para isolá-la.", color = Palette.muted, fontSize = 12.sp)
                    HypothesesPanel(st, hyps, focusHyp) { focusHyp = it }
                }
            }
        }
    }
}

/** Partes não vistas: cada hipótese com motivo, peso relativo e decisão do usuário. */
@Composable
private fun HypothesesPanel(st: ScanState, hyps: List<Inference.Hypothesis>, focus: String?, onFocus: (String?) -> Unit) {
    Text(if (hyps.isEmpty()) "Nenhuma parte escondida sugerida para este modelo." else "Partes não vistas (hipóteses, em cinza no 3D):",
        color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Column {
        for ((g, alts) in hyps.groupBy { it.group }) {
            if (alts.size > 1) Text("Alternativas (só uma pode valer):", color = Color(0xFFFFC531), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            for (h in alts) Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                .background(if (h.key == focus) Color(0x33FFC531) else Color.Transparent)
                .pointerInput(h.key) { detectTapGestures { onFocus(if (focus == h.key) null else h.key) } }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${h.title} · peso ${"%.0f".format(h.weight * 100)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(h.reason + if (h.pieces.isNotEmpty()) " (${h.pieces.size} peça${if (h.pieces.size > 1) "s" else ""})" else "", color = Palette.muted, fontSize = 11.sp)
                }
                FilledTonalIconButton(onClick = { st.accept(h); onFocus(null) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Check, "Aceitar") }
                FilledTonalIconButton(onClick = { st.reject(h); onFocus(null) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Rejeitar") }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(118.dp))
    androidx.compose.material3.Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
}

@Composable
private fun ExportPanel(st: ScanState, demo: Boolean) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var b5 by remember { mutableStateOf<String?>(null) }
    var experimental by remember { mutableStateOf(false) }
    val hasHyp = st.pieces.any { it.evidence?.source == Evidence.Source.HIPOTESE }
    // conservador: só o que foi visto/confirmado; experimental: inclui hipóteses que você aceitou
    fun exported() = st.placed().filter { experimental || it.piece.evidence?.source != Evidence.Source.HIPOTESE }
    if (hasHyp) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (experimental) "Exportar com hipóteses aceitas (experimental)" else "Exportar só o que foi visto (conservador)", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = experimental, onCheckedChange = { experimental = it })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(enabled = !demo && b5 != "…", onClick = {
            b5 = "…"
            scope.launch {
                when (val r = com.brastoy.brascan.scan.B5Export.encode(ctx, exported())) {
                    is com.brastoy.brascan.scan.B5Export.Result.Ok -> {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Brastoy", r.code))
                        b5 = "Copiado e conferido (reimportado sem diferença, erro máx. ${"%.1f".format(r.maxPosError * 75)} mm): ${r.pieces} peças\n${r.code}"
                    }
                    is com.brastoy.brascan.scan.B5Export.Result.Failed -> b5 = "Não exportei: ${r.message}"
                }
            }
        }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Código B4/B5") }
        FilledTonalButton(enabled = !demo, onClick = {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Layout.json(Layout.exportReady(exported()))), "Enviar para o simulador"))
        }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("JSON") }
    }
    b5?.takeIf { it != "…" }?.let { Text(it, color = if (it.startsWith("Não")) Palette.pink else Palette.y, fontSize = 12.sp, maxLines = 6, modifier = Modifier.padding(top = 8.dp)) }
    ProjectsRow(st)
    Text("Arraste: girar · pinça: zoom · toque duplo: recentralizar", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

/** Projeto: nome, salvar com nome, abrir/apagar salvos, começar um novo. O atual é salvo sozinho. */
@Composable
private fun ProjectsRow(st: ScanState) {
    val ctx = LocalContext.current
    val store = remember { com.brastoy.brascan.scan.ProjectStore(ctx) }
    var dialog by remember { mutableStateOf<String?>(null) } // "salvar" | "abrir"
    var entries by remember { mutableStateOf(emptyList<com.brastoy.brascan.scan.ProjectStore.Entry>()) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Projeto: ${st.projectName}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
        OutlinedButton(onClick = { dialog = "salvar" }) { Text("Salvar") }
        OutlinedButton(onClick = { entries = store.list(); dialog = "abrir" }) { Text("Abrir") }
    }
    when (dialog) {
        "salvar" -> {
            var name by remember { mutableStateOf(if (st.projectName == "Sem nome") "" else st.projectName) }
            androidx.compose.material3.AlertDialog(onDismissRequest = { dialog = null },
                title = { Text("Salvar projeto") },
                text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true) },
                confirmButton = { Button(enabled = name.isNotBlank(), onClick = {
                    st.projectName = name.trim(); store.save(st.projectId, st.toProject()); dialog = null; toast(ctx, "Projeto salvo")
                }) { Text("Salvar") } },
                dismissButton = { OutlinedButton(onClick = { dialog = null }) { Text("Cancelar") } })
        }
        "abrir" -> androidx.compose.material3.AlertDialog(onDismissRequest = { dialog = null },
            title = { Text("Projetos") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (entries.isEmpty()) Text("Nenhum projeto salvo ainda.", color = Palette.muted)
                    entries.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("${e.pieces} peças · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(e.savedAt))}", color = Palette.muted, fontSize = 12.sp)
                            }
                            FilledTonalButton(onClick = { store.load(e.id)?.let { st.load(it, e.id) }; dialog = null }) { Text("Abrir") }
                            FilledTonalIconButton(onClick = { store.delete(e.id); entries = store.list() }) { Icon(Icons.Rounded.Delete, "Apagar") }
                        }
                    }
                }
            },
            confirmButton = { OutlinedButton(onClick = { st.clear(); dialog = null }) { Text("Novo projeto") } },
            dismissButton = { OutlinedButton(onClick = { dialog = null }) { Text("Fechar") } })
    }
}

@Composable
private fun ConfidencePanel(st: ScanState, sel: Piece?, assess: Map<Int, Assessment>, demo: Boolean) {
    if (sel == null) {
        val counts = assess.values.groupingBy { it.level }.eachCount()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Level.entries.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(l.argb)))
                    Text(" ${counts[l] ?: 0}", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Verde: observada e validada · Amarelo: inferida/pouco confiável · Vermelho: conflito · Cinza: desconhecida", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        return
    }
    val a = assess[sel.id] ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(a.level.argb)))
        Text("  ${Catalog.LABEL[sel.shape]} · ${a.level.label}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (!demo && sel.evidence?.accepted != true) FilledTonalButton(onClick = { st.update(sel.id) { it } }) { Text("Confirmar") }
    }
    a.reasons.forEach { Text("• $it", color = Color(0xFFC9D6EA), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun MeasurePanel(ps: List<Measure.P>, a: Int, b: Int) {
    val items = if (a >= 0 && b >= 0) Measure.between(ps.first { it.id == a }, ps.first { it.id == b }) else Measure.overall(ps)
    Text(if (a >= 0 && b >= 0) "Entre as duas peças" else if (a >= 0) "Agora toque na segunda peça" else "Construção inteira", color = Palette.muted, fontSize = 12.sp)
    for (m in items) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        val v = if (m.unit == "cm³") "%.0f".format(m.value) else "%.1f".format(m.value)
        Text("$v${m.plusMinus?.let { " ± %.1f".format(it) } ?: ""} ${m.unit}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("  ${m.kind.label}", color = when (m.kind) { Measure.Kind.OBSERVADA -> Palette.y; Measure.Kind.DERIVADA -> Palette.accent; Measure.Kind.ESTIMADA -> Color(0xFFFFC531) }, fontSize = 11.sp)
    }
    items.firstOrNull { it.note.isNotBlank() }?.let { Text(it.note, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
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
