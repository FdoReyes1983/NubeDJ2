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

    // ---- separacion de voz/instrumental con IA (ver Separator.kt) ----
    /** Prepara la separacion de la pista ya cargada en el deck. Devuelve los bloques a procesar (0 = error). */
    external fun stemPrepare(deck: Int): Int
    /** STFT del bloque [p]: magnitudes en un buffer directo de 2*512*1024 floats. */
    external fun stemInput(deck: Int, p: Int, out: java.nio.ByteBuffer): Boolean
    /** Salida de los modelos voz / acompanamiento (buffers directos) para el bloque [p]. */
    external fun stemApply(deck: Int, p: Int, yVocals: java.nio.ByteBuffer, yAccomp: java.nio.ByteBuffer): Boolean
    external fun stemEnd(deck: Int)
    external fun stemClear(deck: Int)
    /** Fraccion ya separada de la pista del deck (0..1); -1 si no hay stems. */
    external fun stemProgress(deck: Int): Float
}
