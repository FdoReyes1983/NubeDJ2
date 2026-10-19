package cl.fernando.djnube

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

data class BpmAnalysis(val bpm: Float, val firstBeat: Double, val duration: Double)

/**
 * Escaner de BPM de la biblioteca. Por pista decodifica solo ~80 s del centro (no toda la cancion), asi que
 * tarda 1-3 s por pista y casi no usa memoria. Se puede analizar toda la biblioteca o una pista suelta.
 */
object BpmScanner {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var failed by mutableIntStateOf(0)
    var current by mutableStateOf("")
    val busy = mutableStateListOf<String>()          // pistas en analisis individual
    private var job: Job? = null

    fun pending(): Int = Library.tracks.count { it.bpm <= 0f }

    /** Analiza todas las pistas que aun no tienen BPM, una tras otra, mostrando el avance. */
    fun scanAll(scope: CoroutineScope, ctx: Context, say: (String) -> Unit) {
        if (running) return
        val todo = Library.tracks.filter { it.bpm <= 0f }
        if (todo.isEmpty()) { say("Todas las pistas ya tienen BPM"); return }
        job = scope.launch {
            running = true
            total = todo.size
            done = 0
            failed = 0
            try {
                for (t in todo) {
                    current = t.name
                    val r = try {
                        withContext(Dispatchers.Default) { analyzeFile(ctx, Uri.parse(t.uri)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (r != null && r.bpm > 0f) Library.saveBpm(t.uri, t.name, r.bpm, r.firstBeat, r.duration) else failed++
                    done++
                }
                say("BPM listo: ${total - failed} pistas analizadas" + if (failed > 0) ", $failed sin deteccion" else "")
            } finally {
                running = false
                current = ""
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** Analiza (o vuelve a analizar) una sola pista. */
    fun scanOne(scope: CoroutineScope, ctx: Context, t: LibTrack, say: (String) -> Unit) {
        if (t.uri in busy) return
        busy.add(t.uri)
        scope.launch {
            try {
                val r = try {
                    withContext(Dispatchers.Default) { analyzeFile(ctx, Uri.parse(t.uri)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (r != null && r.bpm > 0f) {
                    Library.saveBpm(t.uri, t.name, r.bpm, r.firstBeat, r.duration)
                    say("${t.name}: ${"%.1f".format(r.bpm)} BPM")
                } else {
                    say("No se pudo detectar el BPM de ${t.name}: cargala y usa TAP")
                }
            } finally {
                busy.remove(t.uri)
            }
        }
    }

    private const val SEG_SEC = 80          // segundos que se decodifican del centro de la pista
    private const val SHORT_SEC = 100       // pistas mas cortas que esto se decodifican enteras

    /** Bloqueante: llamar fuera del hilo de la UI. Devuelve null si no se pudo leer o detectar. */
    fun analyzeFile(ctx: Context, uri: Uri): BpmAnalysis? {
        val extractor = MediaExtractor()
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
        if (pfd == null) { extractor.release(); return null }
        try {
            pfd.use { extractor.setDataSource(it.fileDescriptor) }
        } catch (e: Exception) {
            extractor.release()
            return null
        }
        var idx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; format = f; break }
        }
        if (idx < 0 || format == null) { extractor.release(); return null }
        extractor.selectTrack(idx)

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        val durSec = durationUs / 1_000_000.0

        val seek = durSec > SHORT_SEC
        val targetSec = if (seek) SEG_SEC else SHORT_SEC
        if (seek) extractor.seekTo(((durSec - SEG_SEC) / 2.0 * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var pcm = ShortArray(sampleRate * targetSec * 2)
        var filled = 0                              // cuadros estereo ya guardados
        var firstPtsUs = -1L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var raw = ShortArray(0)
        try {
            while (!outputDone && filled < sampleRate * targetSec) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ib, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                when {
                    ob >= 0 -> {
                        if (info.size > 0) {
                            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                            val out = codec.getOutputBuffer(ob)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val total = sb.remaining()
                            val ch = if (channels > 0) channels else 2
                            val frames = total / ch
                            if (raw.size < total) raw = ShortArray(total)
                            sb.get(raw, 0, total)
                            val room = pcm.size / 2 - filled
                            val n = minOf(frames, room)
                            for (i in 0 until n) {
                                val l = raw[i * ch]
                                val r = if (ch >= 2) raw[i * ch + 1] else l
                                pcm[(filled + i) * 2] = l
                                pcm[(filled + i) * 2 + 1] = r
                            }
                            filled += n
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (sr != sampleRate && filled == 0) {           // el decodificador cambio la tasa antes de dar audio
                            sampleRate = sr
                            pcm = ShortArray(sampleRate * targetSec * 2)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        if (filled < sampleRate * 8) return null                         // menos de 8 s: no se puede analizar
        val r = NativeEngine.analyzePcm(pcm, filled, sampleRate)
        val bpm = r[0]
        if (bpm <= 0f) return null
        val segStart = if (seek && firstPtsUs >= 0) firstPtsUs / 1_000_000.0 else 0.0
        // la cuadricula de beats es periodica: se lleva el primer beat al rango [0, periodo) de la pista
        val per = 60.0 / bpm
        var fb = (r[1].toDouble() + segStart) % per
        if (fb < 0.0) fb += per
        val dur = if (durSec > 0.0) durSec else filled.toDouble() / sampleRate
        return BpmAnalysis(bpm, fb, dur)
    }
}
