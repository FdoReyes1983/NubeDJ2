package cl.fernando.djnube

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Separacion automatica de voz / instrumental con IA (Spleeter 2 stems, ONNX Runtime). Corre sola en segundo plano
 * apenas se carga una pista y va por delante de la reproduccion: la pista completa tarda segundos, no minutos.
 * Los modelos viajan dentro de la app (funciona sin internet). La STFT/ISTFT y la mascara estan en C++ (stem.h).
 */
object Separator {
    private val lock = Mutex()                       // un deck a la vez
    private var env: OrtEnvironment? = null
    private var vocals: OrtSession? = null

    private const val COUNT = 2 * 512 * 1024         // floats por bloque (2 canales x 512 cuadros x 1024 bins)

    private fun session(ctx: Context): OrtSession {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        fun open(name: String): OrtSession {
            val bytes = ctx.assets.open("models/$name.onnx").use { it.readBytes() }
            return OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                e.createSession(bytes, o)
            }
        }
        return vocals ?: open("vocals").also { vocals = it }
    }

    /** Lanza la separacion de la pista ya cargada en [d] (cancela la anterior de ese deck). */
    fun start(scope: CoroutineScope, ctx: Context, d: DeckState, say: (String) -> Unit) {
        val old = d.stemJob
        val gen = d.loadGen
        d.stemProg = 0f
        d.stemJob = scope.launch(Dispatchers.Default) {
            old?.cancelAndJoin()
            try {
                lock.withLock { separate(ctx.applicationContext, d, gen) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (d.loadGen == gen) {
                    d.stemProg = -1f
                    val where = e.stackTrace.firstOrNull()?.let { " @${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""
                    d.stemErr = ("${e.javaClass.simpleName}: ${e.message ?: "?"}$where").take(160)
                }
            }
        }
    }

    private suspend fun separate(ctx: Context, d: DeckState, gen: Int) {
        if (d.loadGen != gen) return
        val sv = session(ctx)
        val deck = d.index
        val n = NativeEngine.stemPrepare(deck)
        if (n <= 0) error("la pista no estaba lista para separar (stemPrepare=0)")
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val e = env!!
            val inB = ByteBuffer.allocateDirect(COUNT * 4).order(ByteOrder.nativeOrder())
            val outVB = ByteBuffer.allocateDirect(COUNT * 4).order(ByteOrder.nativeOrder())
            val inF = inB.asFloatBuffer()
            val outVF = outVB.asFloatBuffer()
            val shape = longArrayOf(2, 1, 512, 1024)
            for (p in 0 until n) {
                currentCoroutineContext().ensureActive()
                if (d.loadGen != gen) return                      // se cargo otra pista en el deck
                check(NativeEngine.stemInput(deck, p, inB)) { "fallo la STFT nativa" }
                inF.rewind()
                OnnxTensor.createTensor(e, inF, shape).use { tin ->
                    sv.run(mapOf("x" to tin)).use { res ->
                        val fb = (res[0] as OnnxTensor).floatBuffer
                        fb.rewind()
                        outVF.clear()
                        outVF.put(fb)
                    }
                }
                check(NativeEngine.stemApply(deck, p, outVB)) { "la salida de la IA no es valida (NaN) o fallo la ISTFT" }
                d.stemProg = (p + 1f) / n
            }
            d.stemSecs = (android.os.SystemClock.elapsedRealtime() - t0) / 1000f
        } finally {
            NativeEngine.stemEnd(deck)
        }
    }
}
