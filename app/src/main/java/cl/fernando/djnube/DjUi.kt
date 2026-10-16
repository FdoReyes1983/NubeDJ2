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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
    var detail by mutableStateOf<FloatArray?>(null)   // 4 valores por bin de 5 ms: total, graves, medios, agudos
    var firstBeat by mutableDoubleStateOf(0.0)
    var loadFrac by mutableFloatStateOf(1f)     // progreso de la carga (0..1)
    var loopBeats by mutableFloatStateOf(0f)    // 0 = sin loop
    var loopStart by mutableDoubleStateOf(0.0)
    var loopEnd by mutableDoubleStateOf(0.0)
    var fxType by mutableIntStateOf(1)          // 1 eco, 2 reverb, 3 flanger, 4 phaser, 5 gate
    var fxLock by mutableStateOf(false)
    var fxTouch by mutableStateOf(false)
    var fxX by mutableFloatStateOf(0.5f)
    var fxY by mutableFloatStateOf(0.5f)
    var pendingSync by mutableStateOf(false)    // SYNC pedido mientras se calculaba el BPM
    var zoomSec by mutableFloatStateOf(6f)      // segundos visibles en el waveform ampliado
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
        firstBeat = NativeEngine.getFirstBeat(index)
        if (loading) loadFrac = NativeEngine.getLoadFraction(index)
        if (loopBeats > 0f) {
            val lp = NativeEngine.getLoop(index)
            loopStart = lp[0]; loopEnd = lp[1]
            if (lp[1] <= lp[0]) loopBeats = 0f       // el motor lo desactivo (p.ej. al cargar otra pista)
        }
    }

    fun pushEq() = NativeEngine.setEq(index, eqLow, eqMid, eqHigh)
}

// ---------- acciones de BPM / SYNC ----------

private fun applyTempo(d: DeckState) = NativeEngine.setTempo(d.index, 1f + d.tempoPct / 100f)

private fun deckLetter(d: DeckState) = if (d.index == 0) "A" else "B"

/** Iguala el tempo (y los beats, si ambos suenan) de [d] al otro deck. */
private fun doSync(d: DeckState, other: DeckState, say: (String) -> Unit) {
    if (d.analyzing || other.analyzing || (d.bpm <= 0f && d.loading) || (other.bpm <= 0f && other.loading)) {
        // el BPM todavia se esta calculando: el SYNC queda pendiente y se aplica solo al terminar
        d.pendingSync = true
        say("Calculando BPM… el SYNC se aplicara solo en un momento")
        return
    }
    d.pendingSync = false
    val r = NativeEngine.syncDeck(d.index)
    if (r < 0f) {
        say(
            if (NativeEngine.getBpm(d.index) <= 0f) "Deck ${deckLetter(d)} sin BPM: usa TAP"
            else "El otro deck no tiene pista o BPM para sincronizar"
        )
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

        var mode by remember { mutableIntStateOf(0) }   // 0 DECK, 1 LOOP, 2 FX (pestana global)

        val pickers = decks.map { d ->
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    d.loading = true
                    val gen = ++d.loadGen
                    d.analyzing = false
                    d.pendingSync = false
                    d.loopBeats = 0f
                    d.detail = null
                    d.overview = null
                    d.loadFrac = 0f
                    d.name = "Abriendo…"
                    val t0 = SystemClock.elapsedRealtime()
                    var tPlay = 0L
                    try {
                        // La pista se puede tocar en cuanto hay ~2.5 s decodificados (onPlayable): sigue cargando detras.
                        val title = withContext(Dispatchers.Default) {
                            TrackLoader.load(ctx, uri, d.index) { ttl ->
                                tPlay = SystemClock.elapsedRealtime()
                                d.name = ttl
                                d.taps.clear()
                                d.tempoPct = 0f
                                NativeEngine.setTempo(d.index, 1f)
                            }
                        }
                        d.name = title
                        d.overview = NativeEngine.getOverview(d.index)
                        d.detail = NativeEngine.getDetail(d.index)
                        if (gen == d.loadGen) d.loading = false
                        val t1 = SystemClock.elapsedRealtime()
                        say("Lista para tocar en ${"%.1f".format((tPlay - t0) / 1000f)} s · completa en ${"%.1f".format((t1 - t0) / 1000f)} s")
                        // BPM en segundo plano: el BPM aparece cuando termina
                        d.analyzing = true
                        val r = withContext(Dispatchers.Default) { NativeEngine.analyzeBpm(d.index) }
                        if (gen == d.loadGen) {
                            if (NativeEngine.getBpm(d.index) == 0f) NativeEngine.setBpm(d.index, r[0], r[1].toDouble())
                            d.analyzing = false
                            // SYNC pedido mientras se calculaba: se aplica ahora (de cualquiera de los dos decks)
                            for (x in decks) if (x.pendingSync) doSync(x, decks[1 - x.index], say)
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
        val openPicker: (DeckState) -> Unit = { d ->
            if (d.loading) say("Espera a que termine de cargar el deck ${deckLetter(d)}")
            else pickers[d.index].launch(arrayOf("audio/*"))
        }
        val cycleZoom: () -> Unit = {
            val nz = when { decks[0].zoomSec <= 3.5f -> 6f; decks[0].zoomSec <= 7f -> 12f; else -> 3f }
            decks.forEach { it.zoomSec = nz }
        }
        val mixerW = 250.dp

        Box(Modifier.fillMaxSize().background(BG)) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // cabecera: titulo, BPM y tiempo de cada deck + pestanas de modo en el centro
                Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckHeader(decks[0], CYAN, Modifier.weight(1f)) { openPicker(decks[0]) }
                    Row(Modifier.width(mixerW), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("DECK", "LOOP", "FX").forEachIndexed { i, label ->
                            SmallButton(label, Color.White, Modifier.weight(1f).fillMaxHeight(), filled = mode == i) { mode = i }
                        }
                    }
                    DeckHeader(decks[1], PINK, Modifier.weight(1f)) { openPicker(decks[1]) }
                }
                // pista completa de cada deck (tocar = saltar)
                Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Waveform(decks[0], CYAN, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(mixerW))
                    Waveform(decks[1], PINK, Modifier.weight(1f).fillMaxHeight())
                }
                // los dos waveforms ampliados, juntos y a la misma escala de tiempo real: se ve la sincronia
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ZoomWave(decks[0], CYAN, Modifier.fillMaxWidth().weight(1f), cycleZoom)
                    ZoomWave(decks[1], PINK, Modifier.fillMaxWidth().weight(1f), cycleZoom)
                }
                Row(Modifier.fillMaxWidth().weight(1.7f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckArea(decks[0], decks[1], CYAN, mode, Modifier.weight(1f), say)
                    Mixer(
                        decks, crossfader,
                        onCrossfader = { crossfader = it; NativeEngine.setCrossfader(it) },
                        onResetVol = {
                            decks.forEach { it.volume = 1f; NativeEngine.setVolume(it.index, 1f) }
                            say("Volumen de ambos decks al maximo")
                        },
                        modifier = Modifier.width(mixerW)
                    )
                    DeckArea(decks[1], decks[0], PINK, mode, Modifier.weight(1f), say)
                }
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

/** Cabecera de un deck: letra, titulo (con progreso mientras carga), BPM actual, tiempo restante y LOAD. */
@Composable
fun DeckHeader(d: DeckState, accent: Color, modifier: Modifier, onLoad: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(PANEL).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(deckLetter(d), color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            if (d.loading && d.name != "Abriendo…") "${d.name}  ·  cargando ${(d.loadFrac * 100).toInt()}%" else d.name,
            color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (d.bpm > 0f) "%.1f".format(d.bpm * (1f + d.tempoPct / 100f)) else if (d.analyzing) "…" else "--",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        Text("-" + fmt(d.duration - d.position), color = MUTED, fontSize = 11.sp)
        SmallButton("LOAD", accent, Modifier.width(48.dp).fillMaxHeight().padding(vertical = 3.dp)) { onLoad() }
    }
}

/** Panel inferior de un deck: segun la pestana global muestra jog, loops o efectos; CUE / PLAY / SYNC siempre abajo. */
@Composable
fun DeckArea(d: DeckState, other: DeckState, accent: Color, mode: Int, modifier: Modifier, say: (String) -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(PANEL).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                1 -> LoopPad(d, say)
                2 -> FxPad(d, accent)
                else -> DeckControls(d, accent)
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PadButton(
                "CUE", YELLOW, Modifier.weight(1f).fillMaxHeight(),
                onPress = { NativeEngine.cuePress(d.index) },
                onRelease = { NativeEngine.cueRelease(d.index) }
            )
            PadButton(
                if (d.playing) "PAUSA" else "PLAY", GREEN, Modifier.weight(1f).fillMaxHeight(),
                onPress = { NativeEngine.setPlaying(d.index, !NativeEngine.isPlaying(d.index)) }
            )
            SmallButton("SYNC", accent, Modifier.weight(1f).fillMaxHeight(), filled = true) { doSync(d, other, say) }
        }
    }
}

/** Pestana DECK: jog (scratch), fader de pitch vertical y botones de BPM. */
@Composable
fun DeckControls(d: DeckState, accent: Color) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val jog = minOf(maxHeight, maxWidth * 0.46f)
        val rg = d.range.toFloat()
        val bh = ((maxHeight - 8.dp) / 3).coerceIn(24.dp, 38.dp)
        Row(
            Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Jog(d, accent, Modifier.size(jog))
            Column(Modifier.width(34.dp).height(jog), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%+.1f".format(d.tempoPct), color = MUTED, fontSize = 9.sp, maxLines = 1)
                VFader(
                    value = d.tempoPct.coerceIn(-rg, rg), min = -rg, max = rg, color = accent,
                    onChange = { v -> d.tempoPct = if (abs(v) < 0.25f) 0f else v; applyTempo(d) },
                    onReset = { d.tempoPct = 0f; applyTempo(d) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("÷2", accent, Modifier.weight(1f).height(bh)) { scaleBpm(d, 0.5f) }
                    SmallButton("×2", accent, Modifier.weight(1f).height(bh)) { scaleBpm(d, 2f) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("TAP", YELLOW, Modifier.weight(1f).height(bh)) { registerTap(d) }
                    SmallButton("SET", YELLOW, Modifier.weight(1f).height(bh)) { setAnchor(d) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("±${d.range}", MUTED, Modifier.weight(1f).height(bh)) {
                        d.range = RANGES[(RANGES.indexOf(d.range) + 1) % RANGES.size]
                        d.tempoPct = d.tempoPct.coerceIn(-d.range.toFloat(), d.range.toFloat())
                        applyTempo(d)
                    }
                    SmallButton("RESET", YELLOW, Modifier.weight(1f).height(bh)) { d.tempoPct = 0f; applyTempo(d) }
                }
            }
        }
    }
}

/** Fader vertical (arriba = max). Arrastrar para mover, doble toque = onReset. */
@Composable
fun VFader(value: Float, min: Float, max: Float, color: Color, onChange: (Float) -> Unit, onReset: () -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    val reset by rememberUpdatedState(onReset)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures { ch, _ ->
                    ch.consume()
                    val f = 1f - (ch.position.y / size.height).coerceIn(0f, 1f)
                    change(min + f * (max - min))
                }
            }
    ) {
        val cx = size.width / 2f
        val pad = 10f
        val f = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val y = pad + (1f - f) * (size.height - 2 * pad)
        drawLine(Color(0xFF223049), Offset(cx, pad), Offset(cx, size.height - pad), 6f, StrokeCap.Round)
        drawLine(color.copy(alpha = 0.8f), Offset(cx, size.height - pad), Offset(cx, y), 6f, StrokeCap.Round)
        if (min < 0f) {   // marca del centro (pitch en 0)
            val yc = pad + 0.5f * (size.height - 2 * pad)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(cx - 9f, yc), Offset(cx + 9f, yc), 2f)
        }
        drawRoundRect(Color.White, Offset(cx - 13f, y - 8f), Size(26f, 16f), androidx.compose.ui.geometry.CornerRadius(5f, 5f))
        drawRoundRect(color, Offset(cx - 13f, y - 2f), Size(26f, 4f), androidx.compose.ui.geometry.CornerRadius(2f, 2f))
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
        if (ov == null && d.loading) drawRect(accent.copy(alpha = 0.35f), Offset(0f, 0f), Size(w * d.loadFrac, h))
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

/**
 * Waveform ampliado: ventana de unos segundos centrada en el cabezal, con 3 bandas de color
 * (graves = color del deck, medios = mas claro, agudos = blanco) y la cuadricula de beats.
 * Arrastrar = mover la pista; doble toque = cambiar el zoom (3 / 6 / 12 s).
 */
@Composable
fun ZoomWave(d: DeckState, accent: Color, modifier: Modifier, onZoom: () -> Unit) {
    Canvas(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF05080F))
            .pointerInput(d) {
                detectTapGestures(onDoubleTap = { onZoom() })
            }
            .pointerInput(d) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    if (d.duration > 0) {
                        val secPerPx = d.zoomSec * (1f + d.tempoPct / 100f) / size.width
                        NativeEngine.seekSeconds(d.index, (d.position - dx * secPerPx).coerceIn(0.0, d.duration))
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        // la ventana es de tiempo real: con pitch distinto la pista ocupa mas/menos segundos, y asi los beats
        // de dos decks sincronizados quedan alineados en pantalla
        val span = d.zoomSec * (1f + d.tempoPct / 100f)
        val pos = d.position
        val det = d.detail
        val t0 = pos - span / 2.0
        val low = accent
        val med = Color(
            red = accent.red + (1f - accent.red) * 0.55f,
            green = accent.green + (1f - accent.green) * 0.55f,
            blue = accent.blue + (1f - accent.blue) * 0.55f
        )
        if (det != null && det.size >= 8 && d.duration > 0) {
            val nb = det.size / 4
            val rate = nb / d.duration                       // bins por segundo (~200)
            val cols = w.toInt() / 2                         // columnas de 2 px: mitad de trabajo de dibujo
            val binsPerCol = span * rate / cols
            val cw = w / cols
            for (x in 0 until cols) {
                val ts = t0 + span * x / cols
                val b0 = (ts * rate).toInt()
                val b1 = max(b0 + 1, (ts * rate + binsPerCol).toInt())
                if (b1 <= 0 || b0 >= nb) continue
                var pf = 0f; var pl = 0f; var pm = 0f; var ph = 0f
                for (b in max(0, b0) until minOf(nb, b1)) {
                    val o = b * 4
                    if (det[o] > pf) pf = det[o]
                    if (det[o + 1] > pl) pl = det[o + 1]
                    if (det[o + 2] > pm) pm = det[o + 2]
                    if (det[o + 3] > ph) ph = det[o + 3]
                }
                // raiz para que los golpes suaves tambien se vean, pero los picos sigan sobresaliendo
                val hf = sqrt(pf) * mid * 0.96f
                val hl = sqrt(pl) * mid * 0.96f
                val hm = sqrt(pm) * mid * 0.96f
                val hh = sqrt(ph) * mid * 0.96f
                val xx = (x + 0.5f) * cw
                drawLine(low.copy(alpha = 0.28f), Offset(xx, mid - hf), Offset(xx, mid + hf), cw + 0.3f)
                drawLine(low, Offset(xx, mid - hl), Offset(xx, mid + hl), cw + 0.3f)
                drawLine(med, Offset(xx, mid - hm), Offset(xx, mid + hm), cw + 0.3f)
                drawLine(Color.White.copy(alpha = 0.9f), Offset(xx, mid - hh), Offset(xx, mid + hh), cw + 0.3f)
            }
        }
        if (d.loopBeats > 0f && d.loopEnd > d.loopStart) {
            val xs = ((d.loopStart - t0) / span * w).toFloat().coerceIn(0f, w)
            val xe = ((d.loopEnd - t0) / span * w).toFloat().coerceIn(0f, w)
            if (xe > xs) {
                drawRect(YELLOW.copy(alpha = 0.20f), Offset(xs, 0f), Size(xe - xs, h))
                drawLine(YELLOW, Offset(xs, 0f), Offset(xs, h), 3f)
                drawLine(YELLOW, Offset(xe, 0f), Offset(xe, h), 3f)
            }
        }
        // cuadricula de beats (en tiempo de pista; la linea mas gruesa marca cada 4 beats)
        if (d.bpm > 0f && d.duration > 0) {
            val per = 60.0 / d.bpm
            var k = Math.floor((t0 - d.firstBeat) / per).toLong()
            while (true) {
                val tb = d.firstBeat + k * per
                if (tb > t0 + span) break
                if (tb >= t0 && tb >= 0) {
                    val x = ((tb - t0) / span * w).toFloat()
                    val bar = Math.floorMod(k, 4L) == 0L
                    drawLine(
                        Color.White.copy(alpha = if (bar) 0.75f else 0.30f),
                        Offset(x, 0f), Offset(x, h), if (bar) 2.5f else 1f
                    )
                }
                k++
            }
        }
        drawLine(Color.White, Offset(w / 2f, 0f), Offset(w / 2f, h), 2.5f)
        drawLine(Color(0xFFE53935), Offset(w / 2f - 6f, 0f), Offset(w / 2f + 6f, 0f), 4f)
    }
}

// ---------- loops ----------

private val LOOP_SIZES = listOf(
    0.0625f to "1/16", 0.125f to "1/8", 0.25f to "1/4", 0.5f to "1/2", 1f to "1",
    2f to "2", 4f to "4", 8f to "8", 16f to "16", 32f to "32"
)

private fun toggleLoop(d: DeckState, beats: Float, say: (String) -> Unit) {
    if (d.loopBeats == beats) {
        NativeEngine.loopOff(d.index)
        d.loopBeats = 0f
        return
    }
    if (NativeEngine.setLoop(d.index, beats)) {
        d.loopBeats = beats
    } else {
        say(if (d.analyzing) "Calculando BPM… prueba el loop en un momento" else "El loop necesita BPM: usa TAP si no se detecto")
    }
}

/** Botones de loop de 1/16 a 32 beats; tocar el activo lo apaga. El loop arranca en el beat mas cercano. */
@Composable
fun LoopPad(d: DeckState, say: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0 until 5) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in 0 until 2) {
                    val (beats, label) = LOOP_SIZES[r * 2 + c]
                    SmallButton(label, YELLOW, Modifier.weight(1f).fillMaxHeight(), filled = d.loopBeats == beats) {
                        toggleLoop(d, beats, say)
                    }
                }
            }
        }
    }
}

// ---------- efectos ----------

private val FX_NAMES = listOf("", "ECO", "REVERB", "FLANGER", "PHASER", "GATE")

private fun pushFx(d: DeckState) = NativeEngine.setFx(d.index, d.fxType, d.fxTouch || d.fxLock, d.fxX, d.fxY)

/** Selector de efecto + pad XY: X = tiempo/velocidad, Y = cantidad. Tocar el pad lo activa; LOCK lo deja encendido. */
@Composable
fun FxPad(d: DeckState, accent: Color) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in 0 until 3) {
                    val i = row * 3 + c + 1            // 1..6 ; el 6 es LOCK
                    if (i <= 5) {
                        SmallButton(FX_NAMES[i], PINK, Modifier.weight(1f).fillMaxHeight(), filled = d.fxType == i) {
                            d.fxType = i
                            pushFx(d)
                        }
                    } else {
                        SmallButton("LOCK", YELLOW, Modifier.weight(1f).fillMaxHeight(), filled = d.fxLock) {
                            d.fxLock = !d.fxLock
                            pushFx(d)
                        }
                    }
                }
            }
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B1220))
                .border(1.dp, if (d.fxTouch || d.fxLock) PINK else Color(0xFF223049), RoundedCornerShape(8.dp))
                .pointerInput(d) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        d.fxTouch = true
                        fun upd(p: Offset) {
                            d.fxX = (p.x / size.width).coerceIn(0f, 1f)
                            d.fxY = (1f - p.y / size.height).coerceIn(0f, 1f)
                            pushFx(d)
                        }
                        upd(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull() ?: break
                            if (!p.pressed) break
                            upd(p.position)
                            p.consume()
                        }
                        d.fxTouch = false
                        pushFx(d)
                    }
                }
        ) {
            val on = d.fxTouch || d.fxLock
            val px = d.fxX * size.width
            val py = (1f - d.fxY) * size.height
            val col = if (on) PINK else MUTED
            drawLine(col.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 1f)
            drawLine(col.copy(alpha = 0.35f), Offset(0f, py), Offset(size.width, py), 1f)
            drawCircle(col.copy(alpha = if (on) 0.9f else 0.5f), radius = if (on) 14f else 9f, center = Offset(px, py))
        }
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

/** Centro: EQ (4 perillas por deck), faders de volumen verticales, VU y crossfader abajo. */
@Composable
fun Mixer(
    decks: List<DeckState>, crossfader: Float,
    onCrossfader: (Float) -> Unit, onResetVol: () -> Unit, modifier: Modifier
) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(PANEL).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cell = (maxHeight / 4).coerceIn(30.dp, 56.dp)
            val knob = cell - 3.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                KnobColumn(decks[0], CYAN, knob, cell, true, Modifier.weight(1f))
                VFader(
                    decks[0].volume, 0f, 1f, CYAN,
                    onChange = { decks[0].volume = it; NativeEngine.setVolume(0, it) },
                    onReset = { decks[0].volume = 1f; NativeEngine.setVolume(0, 1f) },
                    modifier = Modifier.width(26.dp).fillMaxHeight()
                )
                Row(Modifier.fillMaxHeight().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Vu(decks[0].level, Modifier.width(8.dp).fillMaxHeight())
                    Vu(decks[1].level, Modifier.width(8.dp).fillMaxHeight())
                }
                VFader(
                    decks[1].volume, 0f, 1f, PINK,
                    onChange = { decks[1].volume = it; NativeEngine.setVolume(1, it) },
                    onReset = { decks[1].volume = 1f; NativeEngine.setVolume(1, 1f) },
                    modifier = Modifier.width(26.dp).fillMaxHeight()
                )
                KnobColumn(decks[1], PINK, knob, cell, false, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("VOL↺", YELLOW, Modifier.width(48.dp).fillMaxHeight()) { onResetVol() }
            Slider(
                value = crossfader, valueRange = 0f..1f, onValueChange = onCrossfader,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = GREEN),
                modifier = Modifier.weight(1f).height(28.dp)
            )
            SmallButton("C", Color.White, Modifier.width(28.dp).fillMaxHeight()) { onCrossfader(0.5f) }
        }
    }
}

@Composable
private fun KnobColumn(d: DeckState, accent: Color, knob: Dp, cell: Dp, labelFirst: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(d.eqHigh, { d.eqHigh = it; d.pushEq() }, "HIGH", accent, knob, cell, labelFirst)
        Knob(d.eqMid, { d.eqMid = it; d.pushEq() }, "MID", accent, knob, cell, labelFirst)
        Knob(d.eqLow, { d.eqLow = it; d.pushEq() }, "LOW", accent, knob, cell, labelFirst)
        Knob(d.filter, { d.filter = it; NativeEngine.setFilter(d.index, it) }, "FILTER", YELLOW, knob, cell, labelFirst)
    }
}

/**
 * Perilla rotativa de -1 a 1: se arrastra hacia arriba/abajo y un doble toque la deja en el centro (0).
 * En rojo cuando anula la banda (EQ) o cierra por completo el filtro. La etiqueta va al costado.
 */
@Composable
fun Knob(value: Float, onChange: (Float) -> Unit, label: String, color: Color, diameter: Dp, cell: Dp, labelFirst: Boolean) {
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Row(
        Modifier.height(cell).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (labelFirst) Text(label, color = MUTED, fontSize = 9.sp, maxLines = 1, modifier = Modifier.width(32.dp))
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
        if (!labelFirst) Text(label, color = MUTED, fontSize = 9.sp, maxLines = 1, modifier = Modifier.width(32.dp).padding(start = 4.dp))
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
