package com.remoit.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

enum class EditMode { BOX, PEN }

enum class Handle { L, R, T, B, TL, TR, BL, BR }

class EditorState {
    val boxes = mutableStateListOf<MarkBox>()
    val strokes = mutableStateListOf<MarkStroke>()
    val current = mutableStateListOf<Offset>()
    var selected by mutableStateOf<Long?>(null)
    var mode by mutableStateOf(EditMode.BOX)
    var penSize by mutableFloatStateOf(0.03f)
    var smartBoxes by mutableStateOf(true)

    private val undoStack = ArrayList<Pair<List<MarkBox>, List<MarkStroke>>>()
    private var idCounter = 0L

    fun newId(): Long { idCounter++; return System.nanoTime() + idCounter }
    fun isEmpty() = boxes.isEmpty() && strokes.isEmpty()
    fun toSpec() = EditSpec(boxes.toList(), strokes.toList(), smartBoxes)
    fun canUndo() = undoStack.isNotEmpty()

    fun snapshot() {
        undoStack.add(Pair(boxes.toList(), strokes.toList()))
        if (undoStack.size > 40) undoStack.removeAt(0)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val last = undoStack.removeAt(undoStack.size - 1)
        boxes.clear(); boxes.addAll(last.first)
        strokes.clear(); strokes.addAll(last.second)
        val s = selected
        if (s != null && boxes.none { it.id == s }) selected = null
    }

    fun update(id: Long, f: (MarkBox) -> MarkBox) {
        val i = boxes.indexOfFirst { it.id == id }
        if (i >= 0) boxes[i] = f(boxes[i])
    }

    fun selectedBox(): MarkBox? {
        val s = selected ?: return null
        return boxes.firstOrNull { it.id == s }
    }

    fun reset() {
        boxes.clear(); strokes.clear(); current.clear()
        selected = null
        mode = EditMode.BOX
        smartBoxes = true
        undoStack.clear()
    }
}

// ------------------------------------------------------------------ geometry helpers

private const val MIN_SIZE = 0.015f

private fun handlePoints(b: MarkBox, w: Float, h: Float): List<Pair<Handle, Offset>> {
    val l = b.l * w
    val r = b.r * w
    val t = b.t * h
    val bt = b.b * h
    val cx = (l + r) / 2f
    val cy = (t + bt) / 2f
    return listOf(
        Pair(Handle.L, Offset(l, cy)), Pair(Handle.R, Offset(r, cy)),
        Pair(Handle.T, Offset(cx, t)), Pair(Handle.B, Offset(cx, bt)),
        Pair(Handle.TL, Offset(l, t)), Pair(Handle.TR, Offset(r, t)),
        Pair(Handle.BL, Offset(l, bt)), Pair(Handle.BR, Offset(r, bt))
    )
}

private fun hitHandle(b: MarkBox, p: Offset, w: Float, h: Float, radius: Float): Handle? {
    var best: Handle? = null
    var bd = radius
    for (pair in handlePoints(b, w, h)) {
        val d = (pair.second - p).getDistance()
        if (d < bd) { bd = d; best = pair.first }
    }
    return best
}

private fun resizeBox(b: MarkBox, hd: Handle, dx: Float, dy: Float): MarkBox {
    var l = b.l
    var r = b.r
    var t = b.t
    var bt = b.b
    val mL = hd == Handle.L || hd == Handle.TL || hd == Handle.BL
    val mR = hd == Handle.R || hd == Handle.TR || hd == Handle.BR
    val mT = hd == Handle.T || hd == Handle.TL || hd == Handle.TR
    val mB = hd == Handle.B || hd == Handle.BL || hd == Handle.BR
    if (mL) l = (l + dx).coerceIn(0f, r - MIN_SIZE)
    if (mR) r = (r + dx).coerceIn(l + MIN_SIZE, 1f)
    if (mT) t = (t + dy).coerceIn(0f, bt - MIN_SIZE)
    if (mB) bt = (bt + dy).coerceIn(t + MIN_SIZE, 1f)
    return b.copy(l = l, r = r, t = t, b = bt)
}

private fun moveBox(b: MarkBox, dx: Float, dy: Float): MarkBox {
    val mx = dx.coerceIn(-b.l, 1f - b.r)
    val my = dy.coerceIn(-b.t, 1f - b.b)
    return b.copy(l = b.l + mx, r = b.r + mx, t = b.t + my, b = b.b + my)
}

private fun contains(b: MarkBox, p: Offset, w: Float, h: Float): Boolean =
    p.x >= b.l * w && p.x <= b.r * w && p.y >= b.t * h && p.y <= b.b * h

private fun hitBox(ed: EditorState, p: Offset, w: Float, h: Float): MarkBox? {
    ed.selectedBox()?.let { if (contains(it, p, w, h)) return it }
    for (b in ed.boxes.asReversed()) if (contains(b, p, w, h)) return b
    return null
}

// ------------------------------------------------------------------ overlay (boxes + pen)

@Composable
fun MarkOverlay(ed: EditorState, onTouch: () -> Unit, modifier: Modifier = Modifier) {
    var ow by remember { mutableFloatStateOf(1f) }
    var oh by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val handleHit = with(density) { 26.dp.toPx() }

    Box(
        modifier
            .onSizeChanged { ow = it.width.toFloat(); oh = it.height.toFloat() }
            .pointerInput(ed.mode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTouch()
                    val w = max(1f, ow)
                    val h = max(1f, oh)

                    if (ed.mode == EditMode.PEN) {
                        ed.current.clear()
                        ed.current.add(Offset((down.position.x / w).coerceIn(0f, 1f), (down.position.y / h).coerceIn(0f, 1f)))
                        drag(down.id) { ch ->
                            val q = Offset((ch.position.x / w).coerceIn(0f, 1f), (ch.position.y / h).coerceIn(0f, 1f))
                            val last = ed.current.last()
                            if ((q - last).getDistance() * w > 1.5f) ed.current.add(q)
                            ch.consume()
                        }
                        if (ed.current.size == 1) ed.current.add(ed.current[0] + Offset(0.0005f, 0f))
                        ed.snapshot()
                        ed.strokes.add(MarkStroke(ed.current.toList(), ed.penSize))
                        ed.current.clear()
                        return@awaitEachGesture
                    }

                    // ---- box mode
                    val sel = ed.selectedBox()
                    val handle = if (sel != null) hitHandle(sel, down.position, w, h, handleHit) else null
                    if (sel != null && handle != null) {
                        val sid = sel.id
                        ed.snapshot()
                        drag(down.id) { ch ->
                            val d = ch.positionChange()
                            ed.update(sid) { b -> resizeBox(b, handle, d.x / w, d.y / h) }
                            ch.consume()
                        }
                        return@awaitEachGesture
                    }

                    val hit = hitBox(ed, down.position, w, h)
                    if (hit == null) {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            val bw = 0.30f
                            val bh = 0.08f
                            val cx = (down.position.x / w).coerceIn(0f, 1f)
                            val cy = (down.position.y / h).coerceIn(0f, 1f)
                            val l = (cx - bw / 2f).coerceIn(0f, 1f - bw)
                            val t = (cy - bh / 2f).coerceIn(0f, 1f - bh)
                            ed.snapshot()
                            val id = ed.newId()
                            ed.boxes.add(MarkBox(id, l, t, l + bw, t + bh))
                            ed.selected = id
                        }
                    } else if (hit.id == ed.selected) {
                        // long press in the middle, then drag = move the box
                        var released = false
                        val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed) { released = true; break }
                                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) { released = true; break }
                            }
                            true
                        } == null
                        if (timedOut && !released) {
                            val hid = hit.id
                            ed.snapshot()
                            drag(down.id) { ch ->
                                val d = ch.positionChange()
                                ed.update(hid) { b -> moveBox(b, d.x / w, d.y / h) }
                                ch.consume()
                            }
                        }
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null) ed.selected = hit.id
                    }
                }
            }
    ) {
        // pen strokes: opaque red inside, the whole layer at 50% so overlaps never get darker
        Canvas(Modifier.fillMaxSize().graphicsLayer(alpha = 0.5f, compositingStrategy = CompositingStrategy.Offscreen)) {
            val red = Color(0xFFFF2D2D)
            for (s in ed.strokes) drawStroke(s.pts, s.width * size.width, red)
            if (ed.current.isNotEmpty()) drawStroke(ed.current.toList(), ed.penSize * size.width, red)
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
            for (b in ed.boxes) {
                val sel = b.id == ed.selected
                val tl = Offset(b.l * w, b.t * h)
                val sz = Size((b.r - b.l) * w, (b.b - b.t) * h)
                drawRect(Color(0x40FF3B3B), tl, sz)
                drawRect(
                    color = if (sel) C.Accent2 else Color.White, topLeft = tl, size = sz,
                    style = Stroke(width = (if (sel) 2.5f else 1.5f).dp.toPx(), pathEffect = if (sel) null else dash)
                )
            }
            ed.selectedBox()?.let { drawHandles(it, w, h) }
        }
    }
}

private fun DrawScope.drawStroke(pts: List<Offset>, widthPx: Float, color: Color) {
    if (pts.isEmpty()) return
    val wpx = max(3f, widthPx)
    if (pts.size == 1) {
        drawCircle(color, wpx / 2f, Offset(pts[0].x * size.width, pts[0].y * size.height))
        return
    }
    val path = Path()
    path.moveTo(pts[0].x * size.width, pts[0].y * size.height)
    for (i in 1 until pts.size) path.lineTo(pts[i].x * size.width, pts[i].y * size.height)
    drawPath(path, color, style = Stroke(width = wpx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The grab buttons on every side and corner. Sides show the "><" mark. */
private fun DrawScope.drawHandles(b: MarkBox, w: Float, h: Float) {
    val pw = 14.dp.toPx()
    val ph = 28.dp.toPx()
    val chev = 4.dp.toPx()
    val dark = Color(0xFF07080C)
    for (pair in handlePoints(b, w, h)) {
        val k = pair.first
        val p = pair.second
        when (k) {
            Handle.L, Handle.R -> {
                drawRoundRect(C.Accent2, Offset(p.x - pw / 2f, p.y - ph / 2f), Size(pw, ph), CornerRadius(7.dp.toPx()))
                val st = Stroke(width = 1.8f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val a = Path()
                a.moveTo(p.x - chev * 1.2f, p.y - chev); a.lineTo(p.x - chev * 0.3f, p.y); a.lineTo(p.x - chev * 1.2f, p.y + chev)
                val c = Path()
                c.moveTo(p.x + chev * 1.2f, p.y - chev); c.lineTo(p.x + chev * 0.3f, p.y); c.lineTo(p.x + chev * 1.2f, p.y + chev)
                drawPath(a, dark, style = st)
                drawPath(c, dark, style = st)
            }
            Handle.T, Handle.B -> {
                drawRoundRect(C.Accent2, Offset(p.x - ph / 2f, p.y - pw / 2f), Size(ph, pw), CornerRadius(7.dp.toPx()))
                val st = Stroke(width = 1.8f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val a = Path()
                a.moveTo(p.x - chev, p.y - chev * 1.2f); a.lineTo(p.x, p.y - chev * 0.3f); a.lineTo(p.x + chev, p.y - chev * 1.2f)
                val c = Path()
                c.moveTo(p.x - chev, p.y + chev * 1.2f); c.lineTo(p.x, p.y + chev * 0.3f); c.lineTo(p.x + chev, p.y + chev * 1.2f)
                drawPath(a, dark, style = st)
                drawPath(c, dark, style = st)
            }
            else -> {
                drawCircle(C.Accent2, 7.dp.toPx(), p)
                drawCircle(dark, 3.dp.toPx(), p)
            }
        }
    }
}

// ------------------------------------------------------------------ screen

@Composable
private fun CtlBtn(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2)
            .border(1.dp, C.Border, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, style = ts(14, FontWeight.SemiBold)) }
}

@Composable
private fun ToolBtn(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(48.dp).clip(shape)
            .background(if (selected) C.Accent.copy(alpha = 0.22f) else C.Surface2)
            .border(1.dp, if (selected) C.Accent else C.Border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, style = ts(14, FontWeight.SemiBold, if (selected) C.Accent2 else C.Text)) }
}

private fun grabFrame(ctx: Context, uri: Uri?, posMs: Long): Bitmap? {
    if (uri == null) return null
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        var bm = r.getFrameAtTime(posMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val m = max(bm.width, bm.height)
        if (m > 1600) {
            val s = 1600f / m
            bm = Bitmap.createScaledBitmap(bm, max(1, (bm.width * s).toInt()), max(1, (bm.height * s).toInt()), true)
        }
        bm
    } catch (e: Exception) {
        null
    } finally {
        try { r.release() } catch (e: Exception) { }
    }
}

@Composable
fun EditorScreen(vm: AppVm) {
    val ed = vm.ed
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = vm.kind == MediaKind.VIDEO

    var vv by remember { mutableStateOf<VideoView?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(vm.durMs) }
    var playing by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vv) {
        while (true) {
            val v = vv
            if (v != null) {
                try { pos = v.currentPosition.toLong(); playing = v.isPlaying } catch (e: Exception) { }
            }
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { try { vv?.stopPlayback() } catch (e: Exception) { } } }

    fun seekBy(deltaMs: Long) {
        val v = vv ?: return
        val target = (v.currentPosition + deltaMs).coerceIn(0L, max(0L, dur))
        v.seekTo(target.toInt())
        pos = target
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
        TopBar(if (isVideo) "Mark the text  •  video" else "Mark the text  •  image") { vm.back() }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val aspect = max(0.05f, vm.dispW.toFloat() / max(1, vm.dispH).toFloat())
            var w = maxWidth
            var h = w / aspect
            if (h > maxHeight) { h = maxHeight; w = h * aspect }
            Box(Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                if (isVideo) {
                    AndroidView(
                        factory = { c ->
                            VideoView(c).apply {
                                setVideoURI(vm.videoUri)
                                setOnPreparedListener { mp ->
                                    dur = mp.duration.toLong()
                                    seekTo(1)
                                }
                                setOnCompletionListener { playing = false }
                                vv = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val bmp = vm.preview
                    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                MarkOverlay(ed, onTouch = { try { vv?.pause() } catch (e: Exception) { } }, modifier = Modifier.fillMaxSize())
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (isVideo) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CtlBtn("◀◀ 10s", Modifier.weight(1f)) { seekBy(-10_000L) }
                    CtlBtn(if (playing) "❚❚  Pause" else "▶  Play", Modifier.weight(1.2f)) {
                        val v = vv
                        if (v != null) { if (v.isPlaying) v.pause() else v.start() }
                    }
                    CtlBtn("10s ▶▶", Modifier.weight(1f)) { seekBy(10_000L) }
                }
                Slider(
                    value = (scrub ?: pos.toFloat()).coerceIn(0f, max(1f, dur.toFloat())),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        val s = scrub
                        if (s != null) { vv?.seekTo(s.toInt()); pos = s.toLong() }
                        scrub = null
                    },
                    valueRange = 0f..max(1f, dur.toFloat()),
                    colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Surface2),
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                )
                Text(
                    fmtTime((scrub ?: pos.toFloat()).toLong()) + " / " + fmtTime(dur),
                    style = ts(11, color = C.Muted, family = Mono), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Gap(6)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolBtn("Create box", ed.mode == EditMode.BOX, Modifier.weight(1f)) { ed.mode = EditMode.BOX }
                ToolBtn("Pen", ed.mode == EditMode.PEN, Modifier.weight(1f)) { ed.mode = EditMode.PEN; ed.selected = null }
                ToolBtn("Options", false, Modifier.weight(1f)) { showOptions = true }
            }
            Gap(6)
            val sb = ed.selectedBox()
            val hint = when {
                status != null -> status!!
                ed.mode == EditMode.PEN -> "Draw over the text with your finger. Red shows what will be removed."
                sb != null -> "Drag the side buttons to resize. Long-press the middle, then drag to move."
                else -> "Tap on the text to create a box."
            }
            Text(hint, style = ts(11, color = if (status != null) C.Accent2 else C.Muted, line = 16), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Gap(8)
            PxButton("Process", { vm.startJob(ctx) }, Modifier.fillMaxWidth())
            Gap(6)
        }
    }

    if (showOptions) {
        Dialog(onDismissRequest = { showOptions = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface)
                    .border(1.dp, C.Border, RoundedCornerShape(24.dp)).padding(20.dp).verticalScroll(rememberScrollState())
            ) {
                Text("Options", style = ts(22, FontWeight.Bold))
                Gap(14)
                Text("Pen size", style = ts(14, FontWeight.SemiBold))
                Slider(
                    value = ed.penSize, onValueChange = { ed.penSize = it }, valueRange = 0.008f..0.09f,
                    colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Surface2)
                )
                Gap(6)
                OptionRow(
                    if (ed.smartBoxes) "Box mode: Smart (text only)" else "Box mode: Whole box",
                    if (ed.smartBoxes) "Only the real text pixels inside a box are replaced. Tap to replace the whole box instead (use this for very faint marks)."
                    else "Everything inside a box is replaced. Tap to switch back to Smart mode."
                ) { ed.smartBoxes = !ed.smartBoxes }
                OptionRow("Undo last change", "Goes back one step", enabled = ed.canUndo()) { ed.undo() }
                OptionRow(
                    if (finding) "Searching for text…" else "Auto-find text on this frame",
                    "Finds text and creates boxes for you. Adjust them afterwards.", enabled = !finding
                ) {
                    finding = true
                    status = "Searching for text…"
                    showOptions = false
                    scope.launch {
                        val bmp: Bitmap? = withContext(Dispatchers.IO) { if (isVideo) grabFrame(ctx, vm.videoUri, pos) else vm.previewBmp }
                        if (bmp == null) {
                            status = "Could not read this frame."
                        } else {
                            val rects = try { TextFinder.find(bmp) } catch (e: Throwable) { emptyList() }
                            if (rects.isEmpty()) {
                                status = "No text found on this frame. Create a box by tapping, or use the pen."
                            } else {
                                ed.snapshot()
                                for (r in rects) ed.boxes.add(MarkBox(ed.newId(), r.left, r.top, max(r.right, r.left + MIN_SIZE), max(r.bottom, r.top + MIN_SIZE)))
                                ed.mode = EditMode.BOX
                                status = "Found ${rects.size} text area" + (if (rects.size == 1) "" else "s") + ". Adjust them if needed."
                            }
                        }
                        finding = false
                    }
                }
                val sb = ed.selectedBox()
                if (sb != null) {
                    val from = if (sb.startMs <= 0L) "start" else fmtTime(sb.startMs)
                    val to = if (sb.endMs == Long.MAX_VALUE) "end" else fmtTime(sb.endMs)
                    Gap(6)
                    Text("Selected box is removed from $from to $to", style = ts(12, color = C.Accent2, line = 18))
                    Gap(8)
                    if (isVideo) {
                        OptionRow("Start removing from here", "Uses the current video time (" + fmtTime(pos) + ")") {
                            ed.snapshot(); ed.update(sb.id) { it.copy(startMs = pos) }
                        }
                        OptionRow("Stop removing here", "Uses the current video time (" + fmtTime(pos) + ")") {
                            ed.snapshot(); ed.update(sb.id) { it.copy(endMs = pos) }
                        }
                        OptionRow("Whole video", "Remove from the first to the last second") {
                            ed.snapshot(); ed.update(sb.id) { it.copy(startMs = 0L, endMs = Long.MAX_VALUE) }
                        }
                    }
                    OptionRow("Delete selected box", "", danger = true) {
                        ed.snapshot()
                        ed.boxes.removeAll { it.id == sb.id }
                        ed.selected = null
                    }
                }
                OptionRow("Clear pen drawings", "", danger = true, enabled = ed.strokes.isNotEmpty()) { ed.snapshot(); ed.strokes.clear() }
                OptionRow("Clear everything", "Removes all boxes and drawings", danger = true, enabled = !ed.isEmpty()) {
                    ed.snapshot(); ed.boxes.clear(); ed.strokes.clear(); ed.selected = null
                }
                Gap(6)
                PxButton("Done", { showOptions = false }, Modifier.fillMaxWidth())
            }
        }
    }
    vm.message?.let { m -> MessageDialog(m) { vm.message = null } }
    if (vm.showNoCredits) {
        MessageDialog("No credits left. A new credit arrives in ${Credits.refillText()}.") { vm.showNoCredits = false }
    }
}

@Composable
private fun OptionRow(title: String, sub: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface2)
            .clickable(enabled = enabled, onClick = onClick).padding(16.dp)
    ) {
        Text(title, style = ts(15, FontWeight.SemiBold, if (!enabled) C.Muted else if (danger) C.Danger else C.Text))
        if (sub.isNotEmpty()) Text(sub, style = ts(12, color = C.Muted, line = 18))
    }
}
