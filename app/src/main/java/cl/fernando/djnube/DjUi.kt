package cl.fernando.djnube

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private val BG = Color(0xFF070B14)
private val PANEL = Color(0xFF111B2B)
private val CYAN = Color(0xFF35D6F5)
private val PINK = Color(0xFFF53FC1)
private val GREEN = Color(0xFF4EDB9A)
private val YELLOW = Color(0xFFF5C542)

private const val PI_F = 3.1415927f
private const val TWO_PI = 6.2831855f
private const val SEC_PER_REV = 1.8f  // 33 1/3 rpm

class DeckState(val index: Int) {
    var name by mutableStateOf("Sin pista")
    var loading by mutableStateOf(false)
    var overview by mutableStateOf<FloatArray?>(null)
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var tempoPct by mutableFloatStateOf(0f)
    var volume by mutableFloatStateOf(1f)

    fun poll() {
        position = NativeEngine.getPositionSeconds(index)
        duration = NativeEngine.getDurationSeconds(index)
        playing = NativeEngine.isPlaying(index)
        level = NativeEngine.getLevel(index)
    }
}

@Composable
fun DjApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val decks = remember { listOf(DeckState(0), DeckState(1)) }
        var crossfader by remember { mutableFloatStateOf(0.5f) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                decks.forEach { it.poll() }
                delay(33)
            }
        }

        val pickers = decks.map { d ->
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    d.loading = true
                    try {
                        val title = withContext(Dispatchers.Default) { TrackLoader.load(ctx, uri, d.index) }
                        d.name = title
                        d.overview = NativeEngine.getOverview(d.index)
                        d.tempoPct = 0f
                        NativeEngine.setTempo(d.index, 1f)
                    } catch (e: Exception) {
                        error = "Error al cargar: ${e.message}"
                    }
                    d.loading = false
                }
            }
        }

        Box(Modifier.fillMaxSize().background(BG)) {
            Row(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckPanel(decks[0], CYAN, Modifier.weight(1f)) { pickers[0].launch(arrayOf("audio/*")) }
                Mixer(decks, crossfader, { crossfader = it; NativeEngine.setCrossfader(it) }, Modifier.width(120.dp))
                DeckPanel(decks[1], PINK, Modifier.weight(1f)) { pickers[1].launch(arrayOf("audio/*")) }
            }
            error?.let {
                Text(
                    it, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFFB3261E))
                        .clickable { error = null }.padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun DeckPanel(d: DeckState, accent: Color, modifier: Modifier, onLoad: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(PANEL).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DECK ${if (d.index == 0) "A" else "B"}", color = accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (d.loading) "Cargando…" else d.name, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onLoad, contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("LOAD", fontSize = 12.sp) }
        }
        Waveform(d, accent, Modifier.fillMaxWidth().height(64.dp))
        Text("${fmt(d.position)} / ${fmt(d.duration)}", color = Color(0xFF9FB0C8), fontSize = 12.sp)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val jog = minOf(maxHeight, maxWidth * 0.5f, 170.dp)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Jog(d, accent, Modifier.size(jog))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PadButton(
                            "CUE", YELLOW, Modifier.weight(1f).height(46.dp),
                            onPress = { NativeEngine.cuePress(d.index) },
                            onRelease = { NativeEngine.cueRelease(d.index) }
                        )
                        PadButton(
                            if (d.playing) "PAUSA" else "PLAY", GREEN, Modifier.weight(1f).height(46.dp),
                            onPress = { NativeEngine.setPlaying(d.index, !NativeEngine.isPlaying(d.index)) }
                        )
                    }
                    Text(
                        "TEMPO ${"%+.1f".format(d.tempoPct)}%  (toca = 0)", color = Color(0xFF9FB0C8), fontSize = 11.sp,
                        modifier = Modifier.clickable { d.tempoPct = 0f; NativeEngine.setTempo(d.index, 1f) }
                    )
                    Slider(
                        value = d.tempoPct, valueRange = -8f..8f,
                        onValueChange = { d.tempoPct = it; NativeEngine.setTempo(d.index, 1f + it / 100f) },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )
                }
            }
        }
    }
}

@Composable
fun PadButton(text: String, color: Color, modifier: Modifier, onPress: () -> Unit, onRelease: () -> Unit = {}) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(color)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onPress()
                    tryAwaitRelease()
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
fun Waveform(d: DeckState, accent: Color, modifier: Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF080D16))
            .pointerInput(d) {
                detectTapGestures(onTap = { off ->
                    if (d.duration > 0) NativeEngine.seekSeconds(d.index, (off.x / size.width) * d.duration)
                })
            }
    ) {
        val w = size.width
        val h = size.height
        val prog = if (d.duration > 0) (d.position / d.duration).toFloat() else 0f
        val ov = d.overview
        if (ov != null && ov.isNotEmpty()) {
            val n = w.toInt()
            for (x in 0 until n) {
                val a = ov[((x.toLong() * ov.size) / n).toInt().coerceIn(0, ov.size - 1)]
                val bh = max(1f, sqrt(a) * h * 0.9f)
                drawLine(
                    color = if (x < prog * w) accent.copy(alpha = 0.4f) else accent,
                    start = Offset(x + 0.5f, (h - bh) / 2f), end = Offset(x + 0.5f, (h + bh) / 2f),
                    strokeWidth = 1f
                )
            }
        }
        drawLine(Color.White, Offset(prog * w, 0f), Offset(prog * w, h), 2f)
    }
}

@Composable
fun Jog(d: DeckState, accent: Color, modifier: Modifier) {
    Canvas(
        modifier.pointerInput(d.index) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val c = Offset(size.width / 2f, size.height / 2f)
                var last = atan2(down.position.y - c.y, down.position.x - c.x)
                var lastT = down.uptimeMillis
                var smooth = 0f
                NativeEngine.scratchBegin(d.index)
                while (true) {
                    val ev = withTimeoutOrNull(40L) { awaitPointerEvent() }
                    if (ev == null) {            // dedo quieto = plato detenido
                        smooth = 0f
                        NativeEngine.scratchMove(d.index, 0f)
                        continue
                    }
                    val p = ev.changes.firstOrNull() ?: break
                    if (!p.pressed) break
                    val a = atan2(p.position.y - c.y, p.position.x - c.x)
                    var da = a - last
                    if (da > PI_F) da -= TWO_PI
                    if (da < -PI_F) da += TWO_PI
                    val dt = max(1L, p.uptimeMillis - lastT) / 1000f
                    val v = (da / TWO_PI) * SEC_PER_REV / dt
                    smooth = 0.6f * smooth + 0.4f * v.coerceIn(-10f, 10f)
                    NativeEngine.scratchMove(d.index, smooth)
                    last = a
                    lastT = p.uptimeMillis
                    p.consume()
                }
                NativeEngine.scratchEnd(d.index)
            }
        }
    ) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF0C1320), r, c)
        for (i in 1..5) drawCircle(Color(0xFF1B2638), r * (0.35f + i * 0.12f), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        drawCircle(accent.copy(alpha = 0.8f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        val ang = (d.position.toFloat() / SEC_PER_REV) * TWO_PI
        val m = Offset(c.x + cos(ang) * r * 0.82f, c.y + sin(ang) * r * 0.82f)
        drawCircle(Color.White, r * 0.06f, m)
        drawCircle(accent, r * 0.12f, c)
    }
}

@Composable
fun Mixer(decks: List<DeckState>, crossfader: Float, onCrossfader: (Float) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(PANEL).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("MIXER", color = Color.White, fontWeight = FontWeight.Bold)
        Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Vu(decks[0].level, Modifier.weight(1f).fillMaxHeight())
            Vu(decks[1].level, Modifier.weight(1f).fillMaxHeight())
        }
        Text("VOL A", color = CYAN, fontSize = 11.sp)
        Slider(
            value = decks[0].volume, valueRange = 0f..1f,
            onValueChange = { decks[0].volume = it; NativeEngine.setVolume(0, it) },
            colors = SliderDefaults.colors(thumbColor = CYAN, activeTrackColor = CYAN),
            modifier = Modifier.height(28.dp)
        )
        Text("VOL B", color = PINK, fontSize = 11.sp)
        Slider(
            value = decks[1].volume, valueRange = 0f..1f,
            onValueChange = { decks[1].volume = it; NativeEngine.setVolume(1, it) },
            colors = SliderDefaults.colors(thumbColor = PINK, activeTrackColor = PINK),
            modifier = Modifier.height(28.dp)
        )
        Text("X-FADER", color = Color.White, fontSize = 11.sp)
        Slider(
            value = crossfader, valueRange = 0f..1f, onValueChange = onCrossfader,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = GREEN),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
fun Vu(level: Float, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF080D16))) {
        val segs = 14
        val lit = (level.coerceIn(0f, 1f) * segs).toInt()
        val gap = 2f
        val sh = (size.height - gap * (segs + 1)) / segs
        for (i in 0 until segs) {
            val y = size.height - gap - (i + 1) * sh - i * gap
            val col = when {
                i >= segs - 2 -> Color(0xFFE53935)
                i >= segs - 5 -> YELLOW
                else -> GREEN
            }
            drawRect(if (i < lit) col else col.copy(alpha = 0.12f), Offset(gap, y), Size(size.width - 2 * gap, sh))
        }
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
