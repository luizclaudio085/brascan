package com.brastoy.brascan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brastoy.brascan.scan.Counting
import com.brastoy.brascan.scan.Inventory
import com.brastoy.brascan.scan.SimCodec
import com.brastoy.brascan.vision.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Um lote para revisar antes de ir para o inventário: imagem em pé + peças achadas nela (caixas normalizadas). */
class CountShot(val image: Bitmap, val found: List<Counting.Found>)

/** Nome curto de um tipo do inventário: "Quadrado G", "Tri. retângulo P"… */
fun kindShort(k: Inventory.Kind) = Inventory.label(k) + " " + when (k.size) { "grande" -> "G"; "medio" -> "M"; else -> "P" }

/**
 * Aba Peças: o inventário (quantas de cada um dos 30 tipos do simulador), contagem pela câmera ao vivo ou por
 * foto com revisão, e o inventário como código B8 (copiar para o simulador / colar de lá).
 */
@Composable
fun InventoryScreen(shot: CountShot?, onShot: (CountShot?) -> Unit, openCamera: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var inv by remember { mutableStateOf(Inventory.load(ctx)) }
    fun set(n: Inventory) { inv = n; Inventory.save(ctx, n) }
    var busy by remember { mutableStateOf(false) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> pending = u } }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pending = photoUri }
    var pasteOpen by remember { mutableStateOf(false) }
    var b8 by remember { mutableStateOf<String?>(null) }
    var askZero by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf<Inventory.Kind?>(null) }

    pending?.let { uri ->
        LaunchedEffect(uri) {
            busy = true
            val r = withContext(Dispatchers.Default) {
                runCatching { val b = decodeBitmap(ctx, uri, 1600); CountShot(b, Counting.detect(b)) }.getOrNull()
            }
            busy = false; pending = null
            if (r == null) toast(ctx, "Não consegui abrir a imagem") else onShot(r)
        }
    }

    if (shot != null) {
        androidx.activity.compose.BackHandler { onShot(null) }
        CountReview(shot, onCancel = { onShot(null) }) { add, replace ->
            set(if (replace) Inventory(add) else inv.plus(add)); onShot(null)
            toast(ctx, if (replace) "Inventário trocado: ${add.values.sum()} peças" else "${add.values.sum()} peças somadas ao inventário")
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Label("Inventário de peças")
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text("${inv.total()}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(" peças", color = Palette.muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("Para contar: espalhe as peças no chão sem uma encostar na outra e aponte o celular de cima. Dá para contar aos poucos, lote por lote: cada lote é somado.",
                color = Color(0xFFC9D6EA), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Button(onClick = openCamera, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(10.dp)); Text("Contar com a câmera", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(enabled = !busy, onClick = {
                    val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
                    photoUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(dir, "lote_${System.currentTimeMillis()}.jpg"))
                    runCatching { shoot.launch(photoUri!!) }.onFailure { toast(ctx, "Câmera indisponível") }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Foto do lote") }
                FilledTonalButton(enabled = !busy, onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Galeria")
                }
            }
            if (busy) Column(Modifier.padding(top = 10.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Contando as peças da foto…", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Label("Código B8 do simulador")
            Text("O inventário vira um código curto (B8) para colar no Brastoy Simulator. Também lê o inventário de um código do simulador.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = b8 != "…", onClick = {
                    b8 = "…"
                    scope.launch {
                        b8 = when (val r = SimCodec.encodeInventory(ctx, inv)) {
                            is SimCodec.Result.Ok -> {
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Brastoy", r.code))
                                "Copiado e conferido (${r.code.codePointCount(0, r.code.length)} caracteres): ${r.code}"
                            }
                            is SimCodec.Result.Failed -> "Não gerei o código: ${r.message}"
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copiar B8") }
                OutlinedButton(onClick = { pasteOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Colar código") }
            }
            b8?.takeIf { it != "…" }?.let { Text(it, color = if (it.startsWith("Não")) Palette.pink else Palette.y, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(top = 8.dp)) }
        }

        for (size in Catalog.SIZES) {
            val kinds = Inventory.KINDS.filter { it.size == size }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(Inventory.sizeLabel(size)); Spacer(Modifier.weight(1f))
                    Text("${kinds.sumOf { inv[it] }} peças", color = Palette.muted, fontSize = 12.sp)
                }
                for (k in kinds) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    ShapeIcon(k, if (inv[k] > 0) Palette.accent else Palette.muted.copy(alpha = .5f), Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(Inventory.label(k), color = if (inv[k] > 0) Color.White else Palette.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(enabled = inv[k] > 0, onClick = { set(inv.with(k, inv[k] - 1)) }) { Icon(Icons.Rounded.Remove, "Menos") }
                    Text("${inv[k]}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.widthIn(min = 34.dp).clickable { typing = k }.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(enabled = inv[k] < Inventory.MAX, onClick = { set(inv.with(k, inv[k] + 1)) }) { Icon(Icons.Rounded.Add, "Mais") }
                }
            }
        }
        TextButton(enabled = inv.total() > 0, onClick = { askZero = true }, modifier = Modifier.fillMaxWidth()) { Text("Zerar inventário", color = Palette.pink) }
        Spacer(Modifier.height(8.dp))
    }

    if (pasteOpen) PasteCodeDialog(onDismiss = { pasteOpen = false }) { d ->
        pasteOpen = false
        val i = d.inventory
        if (i != null) { set(i); toast(ctx, "Inventário do código: ${i.total()} peças") }
        else if (d.items.isNotEmpty()) {
            // código só com construção: o inventário vira "as peças que essa construção usa"
            val use = d.items.groupingBy { Counting.kindOf(it.type, it.size).key }.eachCount()
            set(Inventory(use)); toast(ctx, "O código não tinha inventário: usei as ${d.items.size} peças da construção")
        } else toast(ctx, "O código não tem inventário")
    }
    if (askZero) AlertDialog(onDismissRequest = { askZero = false }, title = { Text("Zerar o inventário?") },
        text = { Text("As ${inv.total()} peças contadas somem desta lista.") },
        confirmButton = { Button(onClick = { set(Inventory()); askZero = false }) { Text("Zerar") } },
        dismissButton = { OutlinedButton(onClick = { askZero = false }) { Text("Cancelar") } })
    typing?.let { k ->
        var text by remember(k) { mutableStateOf(inv[k].toString()) }
        AlertDialog(onDismissRequest = { typing = null }, title = { Text(kindShort(k)) },
            text = { OutlinedTextField(value = text, onValueChange = { s -> text = s.filter(Char::isDigit).take(3) }, label = { Text("Quantidade (0 a ${Inventory.MAX})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
            confirmButton = { Button(onClick = { set(inv.with(k, text.toIntOrNull() ?: 0)); typing = null }) { Text("OK") } },
            dismissButton = { OutlinedButton(onClick = { typing = null }) { Text("Cancelar") } })
    }
}

/** Contorno da peça (com furos), no tamanho relativo dela (grande ocupa tudo, pequeno metade). */
@Composable
fun ShapeIcon(k: Inventory.Kind, color: Color, modifier: Modifier = Modifier) = Canvas(modifier) { drawShape(k, color, Offset(size.width / 2, size.height / 2), size.minDimension * .5f) }

private fun DrawScope.drawShape(k: Inventory.Kind, color: Color, c: Offset, maxR: Float, stroke: Float = 2.2f) {
    val poly = Catalog.polygon(k.type)
    val r = poly.maxOf { kotlin.math.hypot(it.first, it.second) }
    val scale = maxR / r * (Catalog.SCALE[k.size] ?: 1f).let { s -> .55f + .45f * s }
    fun path(pts: List<Pair<Float, Float>>) = Path().apply { pts.forEachIndexed { i, (x, y) -> val p = Offset(c.x + x * scale, c.y - y * scale); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
    drawPath(path(poly), color.copy(alpha = .25f))
    drawPath(path(poly), color, style = Stroke(stroke))
    for (h in Catalog.holes(k.type)) drawPath(path(h), color, style = Stroke(stroke * .7f))
}

/**
 * Revisão de um lote: caixas sobre a foto (verde = encaixou bem no molde, amarelo = duvidosa). Tocar numa peça
 * corrige o tipo ou tira; tocar num lugar vazio marca uma que o detector não viu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountReview(shot: CountShot, onCancel: () -> Unit, onAdd: (Map<String, Int>, Boolean) -> Unit) {
    val found = remember(shot) { mutableStateListOf<Counting.Found>().apply { addAll(shot.found) } }
    var editing by remember { mutableStateOf<Int?>(null) } // índice; -1 = peça nova em [addAt]
    var addAt by remember { mutableStateOf(Offset.Zero) }
    val bmp = remember(shot) { shot.image.asImageBitmap() }
    val medW = remember(shot) { shot.found.map { it.w }.sorted().getOrNull(shot.found.size / 2) ?: .1f }
    val medH = remember(shot) { shot.found.map { it.h }.sorted().getOrNull(shot.found.size / 2) ?: (.1f * shot.image.width / shot.image.height) }
    val label = remember { android.graphics.Paint().apply { isAntiAlias = true; textSize = 30f; color = android.graphics.Color.WHITE; isFakeBoldText = true } }
    val labelBg = remember { android.graphics.Paint().apply { color = 0xB0000000.toInt() } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Label("Revise a contagem do lote")
            Text("Toque numa peça para trocar o tipo ou tirar. Toque num lugar vazio para marcar uma peça que faltou.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(shot.image.width.toFloat() / shot.image.height).clip(RoundedCornerShape(14.dp))) {
                Image(bmp, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().pointerInput(shot) {
                    detectTapGestures { p ->
                        val x = p.x / size.width; val y = p.y / size.height
                        val hit = found.indices.filter { i -> val f = found[i]; kotlin.math.abs(x - f.cx) < f.w * .6f && kotlin.math.abs(y - f.cy) < f.h * .6f }
                            .minByOrNull { i -> val f = found[i]; kotlin.math.hypot(x - f.cx, y - f.cy) }
                        if (hit != null) editing = hit else { addAt = Offset(x, y); editing = -1 }
                    }
                }) {
                    for (f in found) {
                        val col = if (f.cut) Color.Gray else if (f.fit >= .8f) Color(0xFF3DDC97) else if (f.fit >= .6f) Color(0xFFFFD23F) else Color(0xFFFF8A3D)
                        val tl = Offset((f.cx - f.w / 2) * size.width, (f.cy - f.h / 2) * size.height)
                        drawRect(col, tl, Size(f.w * size.width, f.h * size.height), style = Stroke(3.dp.toPx()))
                        val t = if (f.cut) "cortada?" else kindShort(f.kind); val tw = label.measureText(t)
                        val lx = (tl.x).coerceIn(0f, (size.width - tw - 8).coerceAtLeast(0f)); val ly = (tl.y - 6).coerceAtLeast(30f)
                        drawContext.canvas.nativeCanvas.apply { drawRect(lx, ly - 28, lx + tw + 8, ly + 6, labelBg); drawText(t, lx + 4, ly - 2, label) }
                    }
                }
            }
        }
        val tally = Counting.tally(found)
        GlassCard(Modifier.fillMaxWidth()) {
            Label("${tally.values.sum()} peças neste lote")
            val cut = found.count { it.cut }
            if (cut > 0) Text("$cut cortada${if (cut > 1) "s" else ""} pela borda da foto (cinza) não conta${if (cut > 1) "m" else ""}: toque nela para confirmar o tipo.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (tally.isEmpty()) Text("Nenhuma peça. Toque na foto para marcar.", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            for (size in Catalog.SIZES) {
                val line = Inventory.KINDS.filter { it.size == size && (tally[it.key] ?: 0) > 0 }
                if (line.isEmpty()) continue
                Text(Inventory.sizeLabel(size) + ": " + line.joinToString { "${tally[it.key]} ${Inventory.label(it)}" }, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Button(enabled = tally.isNotEmpty(), onClick = { onAdd(tally, false) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Somar ao inventário", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = tally.isNotEmpty(), onClick = { onAdd(tally, true) }, modifier = Modifier.weight(1f)) { Text("Substituir inventário") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Descartar lote") }
        }
        Spacer(Modifier.height(8.dp))
    }

    editing?.let { idx ->
        val cur = found.getOrNull(idx)?.kind
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(if (cur != null) "Que peça é esta?" else "Marcar peça que faltou") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    for (size in Catalog.SIZES) {
                        Text(Inventory.sizeLabel(size), color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (k in Inventory.KINDS.filter { it.size == size }) FilterChip(selected = k == cur, onClick = {
                                if (idx >= 0) found[idx] = found[idx].copy(kind = k, fit = 1f, cut = false)
                                else {
                                    val px = shot.image.getPixel((addAt.x * shot.image.width).toInt().coerceIn(0, shot.image.width - 1), (addAt.y * shot.image.height).toInt().coerceIn(0, shot.image.height - 1))
                                    found += Counting.Found(addAt.x, addAt.y, medW, medH, k, px, 1f)
                                }
                                editing = null
                            }, label = { Text(Inventory.label(k), fontSize = 12.sp) })
                        }
                    }
                }
            },
            confirmButton = { if (idx >= 0) TextButton(onClick = { found.removeAt(idx); editing = null }) { Text("Não é peça (tirar)", color = Palette.pink) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } })
    }
}

