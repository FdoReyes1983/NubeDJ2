package cl.fernando.djnube

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Separacion de voz con IA (modelo MDX-Net "Kim_Vocal_2", ONNX). Descarga el modelo una sola vez (~64 MB),
 * procesa la pista completa en bloques de ~5.7 s y deja dos archivos por pista (voz e instrumental) que el motor
 * usa para SIN VOZ / SOLO VOZ. La STFT/ISTFT se hace en C++ (sep.h); aqui solo se mueve el modelo.
 */
object Separator {
    private const val MODEL_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx"
    private const val MODEL_NAME = "Kim_Vocal_2.onnx"
    private const val MODEL_SIZE = 66_759_214L
    private const val MAX_MIN = 10L

    var running by mutableStateOf(false)
    var name by mutableStateOf("")
    var phase by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var eta by mutableStateOf("")
    var currentUri by mutableStateOf("")
    val done = mutableStateListOf<String>()          // URIs con stems listos

    private var job: Job? = null
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val dir = File(ctx.applicationContext.filesDir, "stems")
        // la lista de pistas listas se reconstruye con el indice stems.txt (clave -> uri)
        try {
            val idx = File(dir, "index.txt")
            if (idx.exists()) idx.readLines().forEach { l ->
                val u = l.trim()
                if (u.isNotEmpty() && vocFile(u).exists() && instFile(u).exists()) done.add(u)
            }
        } catch (e: Exception) {
            // sin indice
        }
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("MD5").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(): File = File(app!!.filesDir, "stems").also { it.mkdirs() }
    private fun vocFile(uri: String) = File(dir(), key(uri) + ".voc")
    private fun instFile(uri: String) = File(dir(), key(uri) + ".inst")

    private fun saveIndex() {
        try { File(dir(), "index.txt").writeText(done.joinToString("\n")) } catch (e: Exception) { }
    }

    fun has(uri: String): Boolean = uri in done

    /** Carga los stems de [uri] en el deck (si existen). Llamar fuera del hilo de la UI. */
    fun attach(deck: Int, uri: String): Boolean {
        if (app == null || uri !in done) return false
        return NativeEngine.stemLoad(deck, vocFile(uri).absolutePath, instFile(uri).absolutePath)
    }

    fun delete(uri: String) {
        if (app == null) return
        runCatching { vocFile(uri).delete(); instFile(uri).delete() }
        if (done.remove(uri)) saveIndex()
    }

    fun cancel() { job?.cancel() }

    /** Separa una pista de la biblioteca. onDone(uri) se llama al terminar bien (para cargar los stems en el deck). */
    fun start(scope: CoroutineScope, ctx: Context, uri: String, title: String, say: (String) -> Unit, onDone: (String) -> Unit) {
        if (running) { say("Ya hay una separacion en curso"); return }
        if (uri in done) { say("Esa pista ya esta separada: usa SIN VOZ / SOLO VOZ en el deck"); return }
        init(ctx)
        job = scope.launch {
            running = true
            name = title
            currentUri = uri
            progress = 0f
            eta = ""
            val t0 = SystemClock.elapsedRealtime()
            try {
                withContext(Dispatchers.Default) { separate(ctx.applicationContext, uri, t0) }
                if (uri !in done) done.add(uri)
                saveIndex()
                val s = (SystemClock.elapsedRealtime() - t0) / 1000
                say("Separada en ${s / 60} min ${s % 60} s: ya puedes usar SIN VOZ / SOLO VOZ")
                onDone(uri)
            } catch (e: CancellationException) {
                say("Separacion cancelada")
            } catch (e: OutOfMemoryError) {
                say("Memoria insuficiente para separar esta pista")
            } catch (e: Exception) {
                say("No se pudo separar: ${e.message}")
            } finally {
                NativeEngine.sepCancel()
                runCatching { File(dir(), key(uri) + ".voc.part").delete() }
                running = false
                phase = ""
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun separate(ctx: Context, uri: String, t0: Long) {
        val model = ensureModel(ctx)
        phase = "Leyendo la pista"
        progress = 0f
        val frames = decode(ctx, Uri.parse(uri))
        val chunks = NativeEngine.sepPrepare()
        if (chunks <= 0) error("la pista no tiene audio")

        phase = "Separando con IA"
        progress = 0f
        val env = OrtEnvironment.getEnvironment()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            opts.setMemoryPatternOptimization(false)       // menos memoria
            opts.setCPUArenaAllocator(false)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            env.createSession(model.absolutePath, opts).use { sess ->
                val inName = sess.inputNames.first()
                val count = 4 * 3072 * 256
                val inBuf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
                val inF: FloatBuffer = inBuf.asFloatBuffer()
                val outArr = FloatArray(count)
                val shape = longArrayOf(1, 4, 3072, 256)
                val tStart = SystemClock.elapsedRealtime()
                for (i in 0 until chunks) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(NativeEngine.sepStft(i, inBuf)) { "fallo la STFT" }
                    inF.rewind()
                    OnnxTensor.createTensor(env, inF, shape).use { tin ->
                        sess.run(mapOf(inName to tin)).use { res ->
                            val ot = res[0] as OnnxTensor
                            val fb = ot.floatBuffer
                            fb.rewind()
                            fb.get(outArr)
                        }
                    }
                    check(NativeEngine.sepIstft(i, outArr)) { "fallo la ISTFT" }
                    progress = (i + 1f) / chunks
                    val el = (SystemClock.elapsedRealtime() - tStart) / 1000f
                    val rest = (el / (i + 1) * (chunks - i - 1)).toInt()
                    eta = "quedan ~${rest / 60} min ${rest % 60} s"
                }
            }
        }
        phase = "Guardando"
        val vf = vocFile(uri)
        val inf = instFile(uri)
        check(NativeEngine.sepFinish(vf.absolutePath, inf.absolutePath)) { "no se pudo guardar (¿falta espacio?)" }
        check(frames > 0)
    }

    private fun ensureModel(ctx: Context): File {
        val dir = File(ctx.filesDir, "models").also { it.mkdirs() }
        val f = File(dir, MODEL_NAME)
        if (f.exists() && f.length() == MODEL_SIZE) return f
        phase = "Descargando modelo de IA (64 MB, una sola vez)"
        progress = 0f
        eta = ""
        val part = File(dir, "$MODEL_NAME.part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) error("la descarga del modelo fallo (HTTP ${conn.responseCode})")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else MODEL_SIZE
            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastUi = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi > 200) { progress = (got.toFloat() / total).coerceIn(0f, 1f); lastUi = now }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            part.delete()
            error("sin conexion para descargar el modelo (${e.message})")
        } finally {
            conn?.disconnect()
        }
        if (part.length() != MODEL_SIZE) { part.delete(); error("el modelo descargado esta incompleto") }
        if (!part.renameTo(f)) error("no se pudo guardar el modelo")
        return f
    }

    /** Decodifica la pista completa y la deja en la sesion nativa (que la pasa a 44.1 kHz). Devuelve los frames leidos. */
    private suspend fun decode(ctx: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        val pfd = requireNotNull(ctx.contentResolver.openFileDescriptor(uri, "r")) { "no se pudo abrir el archivo" }
        pfd.use { extractor.setDataSource(it.fileDescriptor) }
        var idx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; format = f; break }
        }
        if (idx < 0 || format == null) { extractor.release(); error("el archivo no tiene audio") }
        extractor.selectTrack(idx)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        if (durationUs > MAX_MIN * 60_000_000L) { extractor.release(); error("la pista dura mas de $MAX_MIN min") }
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var begun = false
        var total = 0L
        var raw = ShortArray(0)
        var stereo = ShortArray(0)
        try {
            while (!outputDone) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (durationUs > 0) progress = (extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                            codec.queueInputBuffer(ib, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                when {
                    ob >= 0 -> {
                        if (info.size > 0) {
                            if (!begun) {
                                NativeEngine.sepBegin((durationUs * sampleRate / 1_000_000L).toInt(), sampleRate)
                                begun = true
                            }
                            val out = codec.getOutputBuffer(ob)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val n = sb.remaining()
                            val ch = if (channels > 0) channels else 2
                            val frames = n / ch
                            if (raw.size < n) raw = ShortArray(n)
                            sb.get(raw, 0, n)
                            if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                            when (ch) {
                                2 -> System.arraycopy(raw, 0, stereo, 0, frames * 2)
                                1 -> for (i in 0 until frames) { stereo[2 * i] = raw[i]; stereo[2 * i + 1] = raw[i] }
                                else -> for (i in 0 until frames) { stereo[2 * i] = raw[i * ch]; stereo[2 * i + 1] = raw[i * ch + 1] }
                            }
                            NativeEngine.sepAppend(stereo, frames)
                            total += frames
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (!begun) sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        check(begun && total > 0) { "no se pudo decodificar el audio" }
        return total
    }
}
