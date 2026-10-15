package cl.fernando.djnube

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private val BG = Color(0xFF070B14)
private val PANEL = Color(0xFF111B2B)
private val BTN = Color(0xFF1B2638)
private val MUTED = Color(0xFF9FB0C8)
private val CYAN = Color(0xFF35D6F5)
private val PINK = Color(0xFFF53FC1)
private val GREEN = Color(0xFF4EDB9A)
private val YELLOW = Color(0xFFF5C542)
private val RED = Color(0xFFE53935)

private const val PI_F = 3.1415927f
private const val TWO_PI = 6.2831855f
private const val SEC_PER_REV = 1.8f  // 33 1/3 rpm

private val RANGES = listOf(8, 16, 50)  // rango del pitch en %

class DeckState(val index: Int) {
    var name by mutableStateOf("Sin pista")
    var loading by mutableStateOf(false)
    var analyzing by mutableStateOf(false)      // BPM calculandose en segundo plano
    var loadGen = 0                             // se incrementa en cada carga: descarta analisis viejos
    var overview by mutableStateOf<FloatArray?>(null)
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var tempoPct by mutableFloatStateOf(0f)
    var range by mutableIntStateOf(8)
    var volume by mutableFloatStateOf(1f)
    var bpm by mutableFloatStateOf(0f)          // BPM de la pista a tempo 1.0 (0 = desconocido)
    var eqLow by mutableFloatStateOf(0f)        // perillas de -1 a 1 (0 = plano)
    var eqMid by mutableFloatStateOf(0f)
    var eqHigh by mutableFloatStateOf(0f)
    var filter by mutableFloatStateOf(0f)
    val taps = ArrayList<Pair<Long, Double>>()  // (hora del toque en ms, posicion de la pista en s)

    fun poll() {
        position = NativeEngine.getPositionSeconds(index)
        duration = NativeEngine.getDurationSeconds(index)
        playing = NativeEngine.isPlaying(index)
        level = NativeEngine.getLevel(index)
        bpm = NativeEngine.getBpm(index)
    }

    fun pushEq() = NativeEngine.setEq(index, eqLow, eqMid, eqHigh)
}

// ---------- acciones de BPM / SYNC ----------

private fun applyTempo(d: DeckState) = NativeEngine.setTempo(d.index, 1f + d.tempoPct / 100f)

private fun deckLetter(d: DeckState) = if (d.index == 0) "A" else "B"

/** Iguala el tempo (y los beats, si ambos suenan) de [d] al otro deck. */
private fun doSync(d: DeckState, say: (String) -> Unit) {
    val r = NativeEngine.syncDeck(d.index)
    if (r < 0f) {
        say("SYNC necesita el BPM de los dos decks (usa TAP si no se detecto)")
        return
    }
    val pct = (r - 1f) * 100f
    d.range = RANGES.firstOrNull { it >= abs(pct) } ?: 50
    d.tempoPct = pct
    say("SYNC: deck ${deckLetter(d)} a ${"%.1f".format(NativeEngine.getBpm(d.index) * r)} BPM")
}

/** x2 o /2 del BPM detectado (corrige el error de octava mas comun). */
private fun scaleBpm(d: DeckState, factor: Float) {
    val b = NativeEngine.getBpm(d.index)
    if (b <= 0f) return
    val nb = b * factor
    if (nb < 40f || nb > 220f) return
    NativeEngine.setBpm(d.index, nb, NativeEngine.getFirstBeat(d.index))
}

/** SET: marca el punto actual como un beat (la cuadricula de beats se alinea a este punto). */
private fun setAnchor(d: DeckState) {
    val b = NativeEngine.getBpm(d.index)
    if (b > 0f) NativeEngine.setBpm(d.index, b, NativeEngine.getPositionSeconds(d.index))
}

/** TAP: toca al ritmo de la musica (3 o mas veces) para fijar el BPM y alinear los beats. */
private fun registerTap(d: DeckState) {
    val now = SystemClock.elapsedRealtime()
    if (d.taps.isNotEmpty() && now - d.taps.last().first > 2000L) d.taps.clear()
    d.taps.add(now to NativeEngine.getPositionSeconds(d.index))
    while (d.taps.size > 8) d.taps.removeAt(0)
    if (d.taps.size < 3) return

    val spanSec = (d.taps.last().first - d.taps.first().first) / 1000.0
    if (spanSec <= 0.0) return
    val tapped = 60.0 * (d.taps.size - 1) / spanSec
    val factor = if (d.playing) 1.0 + d.tempoPct / 100.0 else 1.0  // la pista suena a tempo * factor
    val trackBpm = tapped / factor
    if (trackBpm < 40.0 || trackBpm > 220.0) return

    var first = NativeEngine.getFirstBeat(d.index)
    if (d.playing) {   // fase media (circular) de los toques sobre la cuadricula de beats
        val period = 60.0 / trackBpm
        var sx = 0.0
        var sy = 0.0
        for (t in d.taps) {
            val a = 2.0 * Math.PI * (t.second / period)
            sx += cos(a)
            sy += sin(a)
        }
        var ph = atan2(sy, sx) / (2.0 * Math.PI)
        if (ph < 0.0) ph += 1.0
        first = ph * period
    }
    NativeEngine.setBpm(d.index, trackBpm.toFloat(), first)
}

// ---------- pantalla ----------

@Composable
fun DjApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val decks = remember { listOf(DeckState(0), DeckState(1)) }
        var crossfader by remember { mutableFloatStateOf(0.5f) }
        var message by remember { mutableStateOf<String?>(null) }
        val say: (String) -> Unit = { message = it }

        LaunchedEffect(Unit) {
            while (true) {
                decks.forEach { it.poll() }
                delay(33)
            }
        }
        LaunchedEffect(message) {
            if (message != null) {
                delay(3500)
                message = null
            }
        }

        val pickers = decks.map { d ->
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    d.loading = true
                    val gen = ++d.loadGen
                    d.analyzing = false
                    try {
                        val title = withContext(Dispatchers.Default) { TrackLoader.load(ctx, uri, d.index) }
                        d.name = title
                        d.overview = NativeEngine.getOverview(d.index)
                        d.taps.clear()
                        d.tempoPct = 0f
                        NativeEngine.setTempo(d.index, 1f)
                        if (gen == d.loadGen) d.loading = false
                        // BPM en segundo plano: la pista ya suena, el BPM aparece cuando termina
                        d.analyzing = true
                        val r = withContext(Dispatchers.Default) { NativeEngine.analyzeBpm(d.index) }
                        if (gen == d.loadGen) {
                            if (NativeEngine.getBpm(d.index) == 0f) NativeEngine.setBpm(d.index, r[0], r[1].toDouble())
                            d.analyzing = false
                        }
                    } catch (e: Exception) {
                        say("Error al cargar: ${e.message}")
                    } catch (e: OutOfMemoryError) {
                        say("Memoria insuficiente para esta pista")
                    }
                    if (gen == d.loadGen) { d.loading = false; d.analyzing = false }
                }
            }
        }

        Box(Modifier.fillMaxSize().background(BG)) {
            Row(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckPanel(decks[0], CYAN, Modifier.weight(1f), say) { pickers[0].launch(arrayOf("audio/*")) }
                Mixer(
                    decks, crossfader,
                    onCrossfader = { crossfader = it; NativeEngine.setCrossfader(it) },
                    onResetVol = {
                        decks.forEach { it.volume = 1f; NativeEngine.setVolume(it.index, 1f) }
                        say("Volumen de ambos decks al maximo")
                    },
                    modifier = Modifier.width(250.dp)
                )
                DeckPanel(decks[1], PINK, Modifier.weight(1f), say) { pickers[1].launch(arrayOf("audio/*")) }
            }
            message?.let {
                Text(
                    it, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF263449))
                        .clickable { message = null }.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun DeckPanel(d: DeckState, accent: Color, modifier: Modifier, say: (String) -> Unit, onLoad: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(PANEL).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DECK ${deckLetter(d)}", color = accent, fontWeight = FontWeight.Bold)
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
        Waveform(d, accent, Modifier.fillMaxWidth().height(56.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${fmt(d.position)} / ${fmt(d.duration)}", color = MUTED, fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text("BPM ", color = MUTED, fontSize = 11.sp)
            Text(
                if (d.bpm > 0f) "%.1f".format(d.bpm * (1f + d.tempoPct / 100f)) else if (d.analyzing) "…" else "--",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val jog = minOf(maxHeight, maxWidth * 0.52f, 175.dp)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Jog(d, accent, Modifier.size(jog))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        PadButton(
                            "CUE", YELLOW, Modifier.weight(1f).height(44.dp),
                            onPress = { NativeEngine.cuePress(d.index) },
                            onRelease = { NativeEngine.cueRelease(d.index) }
                        )
                        PadButton(
                            if (d.playing) "PAUSA" else "PLAY", GREEN, Modifier.weight(1f).height(44.dp),
                            onPress = { NativeEngine.setPlaying(d.index, !NativeEngine.isPlaying(d.index)) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallButton("SYNC", accent, Modifier.weight(1.5f).height(34.dp), filled = true) { doSync(d, say) }
                        SmallButton("÷2", accent, Modifier.weight(1f).height(34.dp)) { scaleBpm(d, 0.5f) }
                        SmallButton("×2", accent, Modifier.weight(1f).height(34.dp)) { scaleBpm(d, 2f) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallButton("TAP", YELLOW, Modifier.weight(1f).height(34.dp)) { registerTap(d) }
                        SmallButton("SET", YELLOW, Modifier.weight(1f).height(34.dp)) { setAnchor(d) }
                        SmallButton("±${d.range}", MUTED, Modifier.weight(1f).height(34.dp)) {
                            d.range = RANGES[(RANGES.indexOf(d.range) + 1) % RANGES.size]
                            d.tempoPct = d.tempoPct.coerceIn(-d.range.toFloat(), d.range.toFloat())
                            applyTempo(d)
                        }
                    }
                    val rg = d.range.toFloat()
                    Text(
                        "PITCH ${"%+.1f".format(d.tempoPct)}%", color = MUTED, fontSize = 11.sp,
                        modifier = Modifier.clickable { d.tempoPct = 0f; applyTempo(d) }
                    )
                    Slider(
                        value = d.tempoPct.coerceIn(-rg, rg), valueRange = -rg..rg,
                        onValueChange = { d.tempoPct = it; applyTempo(d) },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

/** Boton con respuesta al instante (actua al tocar, no al soltar) y efecto de pulsado. */
@Composable
fun PadButton(text: String, color: Color, modifier: Modifier, onPress: () -> Unit, onRelease: () -> Unit = {}) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = if (pressed) 0.6f else 1f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    press()
                    tryAwaitRelease()
                    pressed = false
                    release()
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** Boton pequeno (SYNC, TAP, SET...). Actua en el instante del toque, importante para TAP. */
@Composable
fun SmallButton(text: String, color: Color, modifier: Modifier, filled: Boolean = false, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    val bg = when {
        filled -> color.copy(alpha = if (pressed) 0.6f else 1f)
        pressed -> color.copy(alpha = 0.35f)
        else -> BTN
    }
    Box(
        modifier.clip(shape).background(bg).border(1.dp, color, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    press()
                    tryAwaitRelease()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (filled) Color.Black else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
        )
    }
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
        for (i in 1..5) drawCircle(Color(0xFF1B2638), r * (0.35f + i * 0.12f), c, style = Stroke(1f))
        drawCircle(accent.copy(alpha = 0.8f), r, c, style = Stroke(3f))
        val ang = (d.position.toFloat() / SEC_PER_REV) * TWO_PI
        val m = Offset(c.x + cos(ang) * r * 0.82f, c.y + sin(ang) * r * 0.82f)
        drawCircle(Color.White, r * 0.06f, m)
        drawCircle(accent, r * 0.12f, c)
    }
}

// ---------- mezclador ----------

@Composable
fun Mixer(
    decks: List<DeckState>, crossfader: Float,
    onCrossfader: (Float) -> Unit, onResetVol: () -> Unit, modifier: Modifier
) {
    BoxWithConstraints(modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(PANEL).padding(6.dp)) {
        val bottom = 128.dp  // alto reservado para volumenes, crossfader y RESET
        val cell = ((maxHeight - bottom) / 4).coerceIn(40.dp, 62.dp)
        val knob = cell - 19.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(cell * 4), horizontalArrangement = Arrangement.SpaceBetween) {
                KnobColumn(decks[0], CYAN, knob, cell, Modifier.weight(1f))
                Row(
                    Modifier.fillMaxHeight().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Vu(decks[0].level, Modifier.width(9.dp).fillMaxHeight())
                    Vu(decks[1].level, Modifier.width(9.dp).fillMaxHeight())
                }
                KnobColumn(decks[1], PINK, knob, cell, Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VOL A", color = CYAN, fontSize = 10.sp)
                    Slider(
                        value = decks[0].volume, valueRange = 0f..1f,
                        onValueChange = { decks[0].volume = it; NativeEngine.setVolume(0, it) },
                        colors = SliderDefaults.colors(thumbColor = CYAN, activeTrackColor = CYAN),
                        modifier = Modifier.height(28.dp)
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VOL B", color = PINK, fontSize = 10.sp)
                    Slider(
                        value = decks[1].volume, valueRange = 0f..1f,
                        onValueChange = { decks[1].volume = it; NativeEngine.setVolume(1, it) },
                        colors = SliderDefaults.colors(thumbColor = PINK, activeTrackColor = PINK),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
            Text(
                "X-FADER (toca = centro)", color = Color.White, fontSize = 10.sp,
                modifier = Modifier.clickable { onCrossfader(0.5f) }
            )
            Slider(
                value = crossfader, valueRange = 0f..1f, onValueChange = onCrossfader,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = GREEN),
                modifier = Modifier.height(28.dp)
            )
            SmallButton("RESET VOL", YELLOW, Modifier.fillMaxWidth().height(26.dp)) { onResetVol() }
        }
    }
}

@Composable
private fun KnobColumn(d: DeckState, accent: Color, knob: Dp, cell: Dp, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(d.eqHigh, { d.eqHigh = it; d.pushEq() }, "HIGH", accent, knob, cell)
        Knob(d.eqMid, { d.eqMid = it; d.pushEq() }, "MID", accent, knob, cell)
        Knob(d.eqLow, { d.eqLow = it; d.pushEq() }, "LOW", accent, knob, cell)
        Knob(d.filter, { d.filter = it; NativeEngine.setFilter(d.index, it) }, "FILTER", YELLOW, knob, cell)
    }
}

/**
 * Perilla rotativa de -1 a 1: se arrastra hacia arriba/abajo y un doble toque la deja en el centro (0).
 * En rojo cuando anula la banda (EQ) o cierra por completo el filtro.
 */
@Composable
fun Knob(value: Float, onChange: (Float) -> Unit, label: String, color: Color, diameter: Dp, cell: Dp) {
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(
        Modifier.height(cell),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(
            Modifier.size(diameter)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { pc, dragAmount ->
                        pc.consume()
                        // 70 dp de arrastre = medio recorrido de la perilla
                        change((v - dragAmount / 70.dp.toPx()).coerceIn(-1f, 1f))
                    }
                }
        ) {
            val vv = v.coerceIn(-1f, 1f)
            val stroke = diameter.toPx() * 0.12f
            val rad = (size.minDimension - stroke) / 2f
            val c = center
            val topLeft = Offset(c.x - rad, c.y - rad)
            val box = Size(rad * 2f, rad * 2f)
            val arcColor = if (vv <= -0.97f) RED else color
            drawArc(
                color = Color(0xFF223049), startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (abs(vv) > 0.01f) {
                drawArc(
                    color = arcColor, startAngle = if (vv < 0f) 270f + vv * 135f else 270f,
                    sweepAngle = abs(vv) * 135f, useCenter = false,
                    topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            val a = Math.toRadians((270f + vv * 135f).toDouble()).toFloat()
            drawLine(
                Color.White,
                Offset(c.x + cos(a) * rad * 0.30f, c.y + sin(a) * rad * 0.30f),
                Offset(c.x + cos(a) * rad * 0.80f, c.y + sin(a) * rad * 0.80f),
                strokeWidth = stroke * 0.5f, cap = StrokeCap.Round
            )
        }
        Text(label, color = MUTED, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
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
                i >= segs - 2 -> RED
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
