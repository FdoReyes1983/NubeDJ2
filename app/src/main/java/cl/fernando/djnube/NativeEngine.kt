package cl.fernando.djnube

/** Puente JNI hacia el motor de audio C++ (engine.cpp). */
object NativeEngine {
    init { System.loadLibrary("djengine") }

    external fun start(): Boolean
    external fun stop()

    external fun loadBegin(deck: Int, sampleRate: Int, estFrames: Int)
    external fun loadAppend(deck: Int, pcm: ShortArray, frames: Int)
    external fun loadPublish(deck: Int): Boolean
    external fun getLoadFraction(deck: Int): Float
    external fun loadEnd(deck: Int): Boolean

    external fun setPlaying(deck: Int, playing: Boolean)
    external fun isPlaying(deck: Int): Boolean
    external fun cuePress(deck: Int)
    external fun cueRelease(deck: Int)
    external fun seekSeconds(deck: Int, sec: Double)

    external fun setTempo(deck: Int, tempo: Float)
    external fun setVolume(deck: Int, v: Float)
    external fun setCrossfader(x: Float)

    external fun scratchBegin(deck: Int)
    external fun scratchMove(deck: Int, vel: Float)
    external fun scratchEnd(deck: Int)

    external fun getPositionSeconds(deck: Int): Double
    external fun getDurationSeconds(deck: Int): Double
    external fun getLevel(deck: Int): Float
    external fun getOverview(deck: Int): FloatArray

    /** EQ de 3 bandas y filtro DJ. Perillas de -1 a 1 (0 = plano; en el EQ -1 = anular la banda). */
    external fun setEq(deck: Int, low: Float, mid: Float, high: Float)
    /** Filtro DJ: -1 = pasa-bajos cerrado, 0 = apagado, +1 = pasa-altos cerrado. */
    external fun setFilter(deck: Int, v: Float)
    /** Voz: 0 = normal, 1 = sin voz (quita el canal central), 2 = solo voz. */
    external fun setVocal(deck: Int, mode: Int)

    /** Analiza la pista cargada. Devuelve [bpm, primerBeatEnSegundos]; bpm = 0 si no lo detecta. */
    external fun setLoop(deck: Int, beats: Float): Boolean
    external fun loopOff(deck: Int)
    external fun getLoop(deck: Int): DoubleArray
    external fun setFx(deck: Int, type: Int, on: Boolean, x: Float, y: Float)
    external fun getDetail(deck: Int): FloatArray
    external fun analyzeBpm(deck: Int): FloatArray
    /** Analiza un trozo de PCM estereo int16 sin deck (escaner de biblioteca). Devuelve [bpm, primerBeat relativo al trozo]. */
    external fun analyzePcm(pcm: ShortArray, frames: Int, sampleRate: Int): FloatArray
    external fun setBpm(deck: Int, bpm: Float, firstBeat: Double)
    external fun getBpm(deck: Int): Float
    external fun getFirstBeat(deck: Int): Double

    /**
     * SYNC de un toque: iguala el tempo de [deck] al del otro deck y, si ambos suenan, alinea los beats.
     * Devuelve el nuevo factor de tempo (1.0 = sin cambio) o -1 si falta el BPM de alguno de los decks.
     */
    external fun syncDeck(deck: Int): Float

    // ---- separacion de voz con IA (ver Separator.kt) ----
    external fun sepBegin(estFrames: Int, srcRate: Int)
    external fun sepAppend(pcm: ShortArray, frames: Int)
    /** Termina la lectura (pasa a 44.1 kHz) y devuelve cuantos bloques de IA hay que procesar; 0 = error. */
    external fun sepPrepare(): Int
    /** STFT del bloque [idx] en un buffer directo de 4*3072*256 floats. */
    external fun sepStft(idx: Int, out: java.nio.ByteBuffer): Boolean
    /** ISTFT de la salida del modelo para el bloque [idx]; guarda la voz de ese tramo. */
    external fun sepIstft(idx: Int, model: FloatArray): Boolean
    external fun sepFinish(vocPath: String, instPath: String): Boolean
    external fun sepCancel()
    /** Stems de IA de la pista cargada en el deck (si existen, SIN VOZ / SOLO VOZ los usan). */
    external fun stemLoad(deck: Int, vocPath: String, instPath: String): Boolean
    external fun stemClear(deck: Int)
    external fun hasStems(deck: Int): Boolean
}
