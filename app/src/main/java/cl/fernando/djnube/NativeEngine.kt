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
}
