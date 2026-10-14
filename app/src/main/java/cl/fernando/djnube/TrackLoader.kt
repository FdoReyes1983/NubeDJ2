package cl.fernando.djnube

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteOrder

/** Decodifica un archivo de audio a PCM int16 estereo y lo entrega al motor nativo. */
object TrackLoader {

    fun load(context: Context, uri: Uri, deck: Int): String {
        val title = displayName(context, uri)
        val extractor = MediaExtractor()
        val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
            "No se pudo abrir el archivo"
        }
        pfd.use { extractor.setDataSource(it.fileDescriptor) }

        var idx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                idx = i; format = f; break
            }
        }
        if (idx < 0 || format == null) {
            extractor.release()
            error("El archivo no tiene audio")
        }
        extractor.selectTrack(idx)

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        // La pista se decodifica completa a PCM en memoria (~10 MB por minuto), asi que se pone un limite.
        if (durationUs > MAX_DURATION_US) {
            extractor.release()
            error("La pista dura ${durationUs / 60_000_000L} min; el maximo es ${MAX_DURATION_US / 60_000_000L} min")
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var begun = false
        var raw = ShortArray(0)
        var stereo = ShortArray(0)

        try {
            while (!outputDone) {
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
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ob >= 0 -> {
                        if (info.size > 0) {
                            if (!begun) {
                                val est = (durationUs * sampleRate / 1_000_000L).toInt()
                                NativeEngine.loadBegin(deck, sampleRate, est)
                                begun = true
                            }
                            val out = codec.getOutputBuffer(ob)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val total = sb.remaining()
                            val ch = if (channels > 0) channels else 2
                            val frames = total / ch
                            if (raw.size < total) raw = ShortArray(total)
                            sb.get(raw, 0, total)
                            if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                            when (ch) {
                                2 -> System.arraycopy(raw, 0, stereo, 0, frames * 2)
                                1 -> for (i in 0 until frames) { stereo[2 * i] = raw[i]; stereo[2 * i + 1] = raw[i] }
                                else -> for (i in 0 until frames) { stereo[2 * i] = raw[i * ch]; stereo[2 * i + 1] = raw[i * ch + 1] }
                            }
                            NativeEngine.loadAppend(deck, stereo, frames)
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

        check(begun && NativeEngine.loadEnd(deck)) { "No se pudo decodificar el audio" }

        // BPM y primer beat (tarda de ~1 a 3 s segun el largo de la pista); bpm = 0 si no se detecta
        val bpm = NativeEngine.analyzeBpm(deck)
        NativeEngine.setBpm(deck, bpm[0], bpm[1].toDouble())
        return title
    }

    private const val MAX_DURATION_US = 15L * 60L * 1_000_000L

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
        }
        return uri.lastPathSegment ?: "Pista"
    }
}
