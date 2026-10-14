package cl.fernando.djnube

/** Puente JNI hacia el motor de audio C++ (engine.cpp). */
object NativeEngine {
    init { System.loadLibrary("djengine") }

    external fun start(): Boolean
    external fun stop()

    external fun loadBegin(deck: Int, sampleRate: Int, estFrames: Int)
    external fun loadAppend(deck: Int, pcm: ShortArray, frames: Int)
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

    /** Analiza la pista cargada. Devuelve [bpm, primerBeatEnSegundos]; bpm = 0 si no lo detecta. */
    external fun analyzeBpm(deck: Int): FloatArray
    external fun setBpm(deck: Int, bpm: Float, firstBeat: Double)
    external fun getBpm(deck: Int): Float
    external fun getFirstBeat(deck: Int): Double

    /**
     * SYNC de un toque: iguala el tempo de [deck] al del otro deck y, si ambos suenan, alinea los beats.
     * Devuelve el nuevo factor de tempo (1.0 = sin cambio) o -1 si falta el BPM de alguno de los decks.
     */
    external fun syncDeck(deck: Int): Float
}
