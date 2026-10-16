// Nube DJ 2 - motor de audio (Oboe, baja latencia).
// 2 decks, playhead propio con velocidad variable (scratch), crossfader.
#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include "bpm.h"
#include "dsp.h"
#include "fx.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

#define LOG_TAG "NubeDJ"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kDecks = 2;
constexpr int kBins = 1500;
constexpr int kChunk = 1024;  // frames por bloque de DSP

struct Track {
    std::vector<int16_t> pcm;  // estereo intercalado
    int sampleRate = 44100;
    // La pista se puede tocar mientras aun se decodifica: pcm se reserva completo al inicio (nunca se
    // reasigna) y avail indica cuantos frames ya estan listos. frames = largo total (estimado hasta done).
    std::atomic<int64_t> frames{0};
    std::atomic<int64_t> avail{0};
    std::atomic<bool> done{false};
    int64_t capFrames = 0;
    std::vector<float> overview;
    std::vector<float> detail;   // 4 valores por bin de 5 ms: total, graves, medios, agudos (0..1)
};

struct Deck {
    std::shared_ptr<Track> track;    // acceder con std::atomic_load / atomic_store
    std::shared_ptr<Track> loading;  // solo hilo de carga
    std::atomic<bool> playing{false};
    std::atomic<bool> cuePreview{false};
    std::atomic<bool> scratching{false};
    std::atomic<float> scratchVel{0.f};
    std::atomic<float> tempo{1.f};
    std::atomic<float> volume{1.f};
    std::atomic<double> position{0.0};  // en frames de la pista (publicado por el hilo de audio)
    std::atomic<double> cue{0.0};
    std::atomic<bool> seekPending{false};
    std::atomic<double> seekTarget{0.0};
    std::atomic<float> level{0.f};
    std::atomic<float> loadFrac{1.f};     // progreso de la carga actual (0..1)
    bool published = false;               // solo hilo de carga
    // EQ / filtro (perillas -1..1) y BPM de la pista cargada (0 = desconocido)
    std::atomic<float> eqLow{0.f}, eqMid{0.f}, eqHigh{0.f}, filter{0.f};
    std::atomic<float> bpm{0.f};
    std::atomic<double> firstBeat{0.0};
    // loop (en frames de la pista) y efecto activo (tipo, encendido, X, Y del pad)
    std::atomic<bool> loopOn{false};
    std::atomic<double> loopStart{0.0}, loopEnd{0.0};
    std::atomic<int> fxType{1};
    std::atomic<bool> fxOn{false};
    std::atomic<float> fxX{0.5f}, fxY{0.5f};
    // solo hilo de audio
    double pos = 0.0;
    float rate = 0.f;
    dj::DeckFx fx;
    dj::DeckEffects fxu;
};

class Engine : public oboe::AudioStreamDataCallback,
               public oboe::AudioStreamErrorCallback,
               public std::enable_shared_from_this<Engine> {
public:
    Deck decks[kDecks];
    std::atomic<float> crossfader{0.5f};

    bool start() {
        running_ = true;
        return openAndStart();
    }

    void stop() {
        running_ = false;
        std::lock_guard<std::mutex> lk(mtx_);
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    void seekFrames(int k, double frames) {
        decks[k].seekTarget.store(frames);
        decks[k].seekPending.store(true);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);
        std::fill(out, out + numFrames * 2, 0.f);
        const float x = std::clamp(crossfader.load(), 0.f, 1.f);
        const float gain[kDecks] = {std::cos(x * 1.5707963f), std::sin(x * 1.5707963f)};
        for (int32_t off = 0; off < numFrames; off += kChunk) {
            const int32_t n = std::min<int32_t>(kChunk, numFrames - off);
            for (int k = 0; k < kDecks; ++k) renderDeck(decks[k], out + off * 2, n, gain[k]);
        }
        for (int32_t i = 0; i < numFrames * 2; ++i) out[i] = std::clamp(out[i], -1.f, 1.f);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override {
        if (running_) openAndStart();  // p.ej. se desconectaron los audifonos
    }

private:
    bool openAndStart() {
        std::lock_guard<std::mutex> lk(mtx_);
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setUsage(oboe::Usage::Media)
            ->setDataCallback(shared_from_this())
            ->setErrorCallback(shared_from_this());
        oboe::Result r = b.openStream(stream_);
        if (r != oboe::Result::OK) {
            LOGE("openStream fallo: %s", oboe::convertToText(r));
            return false;
        }
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        outRate_ = stream_->getSampleRate();
        r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("requestStart fallo: %s", oboe::convertToText(r));
            return false;
        }
        return true;
    }

    void renderDeck(Deck& d, float* out, int32_t n, float cfGain) {
        std::shared_ptr<Track> t = std::atomic_load(&d.track);
        const int64_t avail = t ? t->avail.load(std::memory_order_acquire) : 0;
        if (!t || avail < 2) {
            d.level.store(d.level.load() * 0.85f);
            return;
        }
        const bool scr = d.scratching.load();
        if (d.seekPending.exchange(false)) {
            d.pos = std::clamp(d.seekTarget.load(), 0.0, (double)(avail - 1));
            if (!d.playing.load() && !scr) d.rate = 0.f;
        }
        const double ratio = (double)t->sampleRate / (double)outRate_;
        float target = 0.f;
        if (scr) target = d.scratchVel.load();
        else if (d.playing.load()) target = d.tempo.load();
        const float a = scr ? 0.007f : 0.0015f;  // suavizado de velocidad
        const bool trackDone = t->done.load(std::memory_order_acquire);
        const double last = (double)(avail - 1);
        const int16_t* pcm = t->pcm.data();
        constexpr float k = 1.f / 32768.f;

        const bool loopOn = d.loopOn.load() && !scr;
        const double loopS = d.loopStart.load(), loopE = d.loopEnd.load();
        const double loopLen = loopE - loopS;
        float* tmp = tmp_;
        double pos = d.pos;
        float rate = d.rate;
        for (int32_t i = 0; i < n; ++i) {
            rate += (target - rate) * a;
            pos += (double)rate * ratio;
            if (loopOn && loopLen > 64.0 && rate > 0.f && pos >= loopE) {
                pos = loopS + std::fmod(pos - loopS, loopLen);      // vuelta al inicio del loop
            }
            if (pos <= 0.0) {
                pos = 0.0;
                if (rate < 0.f) rate = 0.f;
            } else if (pos >= last) {
                pos = last;
                if (rate > 0.f) {
                    rate = 0.f;
                    if (!scr && trackDone) d.playing.store(false);   // si aun decodifica, espera los datos
                }
            }
            const float g = std::min(1.f, std::fabs(rate) * 50.f);  // silencio si esta detenido
            float l = 0.f, r = 0.f;
            if (g > 0.f) {
                const int64_t i0 = (int64_t)pos;
                const int64_t i1 = std::min<int64_t>(i0 + 1, avail - 1);
                const float f = (float)(pos - (double)i0);
                l = (pcm[i0 * 2] + (pcm[i1 * 2] - pcm[i0 * 2]) * f) * k * g;
                r = (pcm[i0 * 2 + 1] + (pcm[i1 * 2 + 1] - pcm[i0 * 2 + 1]) * f) * k * g;
            }
            tmp[2 * i] = l;
            tmp[2 * i + 1] = r;
        }
        d.pos = pos;
        d.rate = rate;
        d.position.store(pos);

        d.fx.process(tmp, n, (float)outRate_, d.eqLow.load(), d.eqMid.load(), d.eqHigh.load(),
                     d.filter.load());

        {   // efecto del deck (despues del EQ/filtro); beatSec = duracion de un beat a la velocidad actual
            const float bpm = d.bpm.load();
            const float tempo = d.tempo.load();
            const float beatSec = (bpm > 0.f && tempo > 0.1f) ? 60.f / (bpm * tempo) : 0.f;
            d.fxu.process(tmp, n, (float)outRate_, d.fxType.load(), d.fxOn.load(), d.fxX.load(),
                          d.fxY.load(), beatSec);
        }

        const float vol = d.volume.load() * cfGain;
        float peak = 0.f;
        for (int32_t i = 0; i < n * 2; ++i) {
            const float v = tmp[i] * vol;
            out[i] += v;
            peak = std::max(peak, std::fabs(v));
        }
        d.level.store(std::max(peak, d.level.load() * 0.85f));
    }

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex mtx_;
    std::atomic<bool> running_{false};
    int outRate_ = 48000;
    float tmp_[kChunk * 2];
};

std::shared_ptr<Engine> gEngine = std::make_shared<Engine>();

inline bool validDeck(int k) { return k >= 0 && k < kDecks; }

}  // namespace

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_cl_fernando_djnube_NativeEngine_##name

JNI(jboolean, start)(JNIEnv*, jobject) { return gEngine->start(); }
JNI(void, stop)(JNIEnv*, jobject) { gEngine->stop(); }

// ---- carga de pistas (PCM int16 estereo, decodificado en Kotlin) ----
JNI(void, loadBegin)(JNIEnv*, jobject, jint deck, jint sampleRate, jint estFrames) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    auto t = std::make_shared<Track>();
    t->sampleRate = sampleRate > 0 ? sampleRate : 44100;
    // capacidad fija (estimacion + 2 % + 5 s); sin duracion conocida se reservan 10 min
    t->capFrames = estFrames > 0 ? (int64_t)((double)estFrames * 1.02) + (int64_t)t->sampleRate * 5
                                 : (int64_t)t->sampleRate * 600;
    t->pcm.assign((size_t)t->capFrames * 2, 0);
    t->frames.store(estFrames > 0 ? estFrames : 0);
    d.loadFrac.store(0.f);
    d.published = false;
    d.loading = t;
}

JNI(void, loadAppend)(JNIEnv* env, jobject, jint deck, jshortArray arr, jint frames) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    Track* t = d.loading.get();
    if (!t || frames <= 0) return;
    const int64_t have = t->avail.load(std::memory_order_relaxed);
    const int64_t n = std::min<int64_t>(frames, t->capFrames - have);
    if (n <= 0) return;                                             // mas largo de lo estimado: se recorta
    env->GetShortArrayRegion(arr, 0, (jsize)(n * 2), t->pcm.data() + have * 2);   // directo, sin copia intermedia
    t->avail.store(have + n, std::memory_order_release);
    const int64_t tot = t->frames.load();
    d.loadFrac.store(tot > 0 ? std::min(1.f, (float)((double)(have + n) / (double)tot)) : 0.f);
}

// Deja la pista en carga disponible para tocar (cuando ya hay unos segundos decodificados).
static bool publishLoading(Deck& d) {
    std::shared_ptr<Track> t = d.loading;
    if (!t || d.published || t->avail.load() < 2) return false;
    d.published = true;
    d.loopOn.store(false);
    d.playing.store(false);
    d.cuePreview.store(false);
    d.bpm.store(0.f);
    d.firstBeat.store(0.0);
    d.cue.store(0.0);
    d.seekTarget.store(0.0);
    d.seekPending.store(true);
    std::atomic_store(&d.track, t);
    return true;
}

JNI(jboolean, loadPublish)(JNIEnv*, jobject, jint deck) {
    return validDeck(deck) && publishLoading(gEngine->decks[deck]) ? JNI_TRUE : JNI_FALSE;
}

JNI(jfloat, getLoadFraction)(JNIEnv*, jobject, jint deck) {
    return validDeck(deck) ? gEngine->decks[deck].loadFrac.load() : 1.f;
}

JNI(jboolean, loadEnd)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return JNI_FALSE;
    Deck& d = gEngine->decks[deck];
    std::shared_ptr<Track> t = d.loading;
    d.loading.reset();
    if (!t) return JNI_FALSE;
    const int64_t total = t->avail.load();
    if (total < 2) return JNI_FALSE;
    t->frames.store(total);
    t->pcm.resize((size_t)total * 2);        // solo recorta (no reasigna): el hilo de audio sigue leyendo sin problema

    t->overview.assign(kBins, 0.f);
    float gmax = 0.f;
    const int16_t* pcm = t->pcm.data();
    for (int b = 0; b < kBins; ++b) {
        int64_t s = t->frames * b / kBins;
        int64_t e = t->frames * (b + 1) / kBins;
        if (e <= s) e = s + 1;
        if (e > t->frames) e = t->frames;
        int m = 0;
        for (int64_t i = s; i < e; ++i) {
            m = std::max(m, std::max(std::abs((int)pcm[2 * i]), std::abs((int)pcm[2 * i + 1])));
        }
        const float v = (float)m / 32768.f;
        t->overview[b] = v;
        gmax = std::max(gmax, v);
    }
    if (gmax > 0.f) for (auto& v : t->overview) v /= gmax;

    {   // Waveform de detalle: picos por bin de 5 ms en 3 bandas (graves < 200 Hz, medios, agudos > 2.5 kHz)
        const int hop = std::max(1, t->sampleRate / 200);
        const int64_t nb = t->frames / hop;
        t->detail.assign((size_t)nb * 4, 0.f);
        const float a1 = 1.f - std::exp(-2.f * 3.14159265f * 200.f / (float)t->sampleRate);
        const float a2 = 1.f - std::exp(-2.f * 3.14159265f * 2500.f / (float)t->sampleRate);
        float l1 = 0.f, l2 = 0.f;
        for (int64_t b = 0; b < nb; ++b) {
            float pf = 0.f, pl = 0.f, pm = 0.f, ph = 0.f;
            const int16_t* q = pcm + (b * hop) * 2;
            for (int i = 0; i < hop; ++i) {
                const float x = 0.5f * ((float)q[2 * i] + (float)q[2 * i + 1]) * (1.f / 32768.f);
                l1 += a1 * (x - l1);
                l2 += a2 * (x - l2);
                pf = std::max(pf, std::fabs(x));
                pl = std::max(pl, std::fabs(l1));
                pm = std::max(pm, std::fabs(l2 - l1));
                ph = std::max(ph, std::fabs(x - l2));
            }
            float* o = &t->detail[(size_t)b * 4];
            o[0] = pf; o[1] = pl; o[2] = pm; o[3] = ph;
        }
        // normalizar con el percentil 98 del total: los picos se ven grandes sin que un solo golpe aplaste el resto
        if (nb > 0) {
            std::vector<float> tot((size_t)nb);
            for (int64_t b = 0; b < nb; ++b) tot[b] = t->detail[(size_t)b * 4];
            const size_t k = (size_t)((double)nb * 0.98);
            std::nth_element(tot.begin(), tot.begin() + std::min<size_t>(k, tot.size() - 1), tot.end());
            const float norm = std::max(1e-4f, tot[std::min<size_t>(k, tot.size() - 1)]);
            for (auto& v : t->detail) v = std::min(1.f, v / norm);
        }
    }

    d.loadFrac.store(1.f);
    t->done.store(true, std::memory_order_release);
    d.loading = t;                            // por si aun no se publico
    publishLoading(d);
    d.loading.reset();
    return JNI_TRUE;
}

// ---- transporte ----
JNI(void, setPlaying)(JNIEnv*, jobject, jint deck, jboolean playing) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    auto t = std::atomic_load(&d.track);
    if (!t) return;
    d.cuePreview.store(false);
    if (playing && d.position.load() >= (double)(t->avail.load() - 2)) gEngine->seekFrames(deck, 0.0);
    d.playing.store(playing == JNI_TRUE);
}

JNI(jboolean, isPlaying)(JNIEnv*, jobject, jint deck) {
    return validDeck(deck) && gEngine->decks[deck].playing.load() ? JNI_TRUE : JNI_FALSE;
}

JNI(void, cuePress)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    auto t = std::atomic_load(&d.track);
    if (!t) return;
    const double pos = d.position.load();
    if (d.playing.load()) {                       // sonando: volver al cue y pausar
        d.playing.store(false);
        gEngine->seekFrames(deck, d.cue.load());
    } else if (std::fabs(pos - d.cue.load()) < 0.04 * t->sampleRate) {
        d.cuePreview.store(true);                 // en el cue: sonar mientras se mantiene
        d.playing.store(true);
    } else {
        d.cue.store(pos);                         // pausado en otro punto: fijar cue
    }
}

JNI(void, cueRelease)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    if (d.cuePreview.exchange(false)) {
        d.playing.store(false);
        gEngine->seekFrames(deck, d.cue.load());
    }
}

JNI(void, seekSeconds)(JNIEnv*, jobject, jint deck, jdouble sec) {
    if (!validDeck(deck)) return;
    auto t = std::atomic_load(&gEngine->decks[deck].track);
    if (!t) return;
    gEngine->seekFrames(deck, std::max(0.0, sec) * t->sampleRate);
}

// ---- mezcla ----
JNI(void, setTempo)(JNIEnv*, jobject, jint deck, jfloat tempo) {
    if (validDeck(deck)) gEngine->decks[deck].tempo.store(std::clamp(tempo, 0.5f, 1.5f));
}
JNI(void, setVolume)(JNIEnv*, jobject, jint deck, jfloat v) {
    if (validDeck(deck)) gEngine->decks[deck].volume.store(std::clamp(v, 0.f, 1.f));
}
JNI(void, setCrossfader)(JNIEnv*, jobject, jfloat x) { gEngine->crossfader.store(x); }

// ---- jog / scratch (velocidad: 1.0 = normal, negativa = hacia atras) ----
JNI(void, scratchBegin)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return;
    gEngine->decks[deck].scratchVel.store(0.f);
    gEngine->decks[deck].scratching.store(true);
}
JNI(void, scratchMove)(JNIEnv*, jobject, jint deck, jfloat vel) {
    if (validDeck(deck)) gEngine->decks[deck].scratchVel.store(std::clamp(vel, -12.f, 12.f));
}
JNI(void, scratchEnd)(JNIEnv*, jobject, jint deck) {
    if (validDeck(deck)) gEngine->decks[deck].scratching.store(false);
}

// ---- estado para la UI ----
JNI(jdouble, getPositionSeconds)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return 0.0;
    auto t = std::atomic_load(&gEngine->decks[deck].track);
    return t ? gEngine->decks[deck].position.load() / t->sampleRate : 0.0;
}
JNI(jdouble, getDurationSeconds)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return 0.0;
    auto t = std::atomic_load(&gEngine->decks[deck].track);
    return t ? (double)std::max<int64_t>(t->frames.load(), t->avail.load()) / t->sampleRate : 0.0;
}
JNI(jfloat, getLevel)(JNIEnv*, jobject, jint deck) {
    return validDeck(deck) ? gEngine->decks[deck].level.load() : 0.f;
}
JNI(jfloatArray, getOverview)(JNIEnv* env, jobject, jint deck) {
    std::shared_ptr<Track> t;
    if (validDeck(deck)) t = std::atomic_load(&gEngine->decks[deck].track);
    const jsize n = t ? (jsize)t->overview.size() : 0;
    jfloatArray arr = env->NewFloatArray(n);
    if (n > 0) env->SetFloatArrayRegion(arr, 0, n, t->overview.data());
    return arr;
}

JNI(jfloatArray, getDetail)(JNIEnv* env, jobject, jint deck) {
    std::shared_ptr<Track> t;
    if (validDeck(deck)) t = std::atomic_load(&gEngine->decks[deck].track);
    const jsize n = t ? (jsize)t->detail.size() : 0;
    jfloatArray arr = env->NewFloatArray(n);
    if (n > 0) env->SetFloatArrayRegion(arr, 0, n, t->detail.data());
    return arr;
}

// ---- loops ----
// beats > 0: loop de esa cantidad de beats (1/16 = 0.0625 ... 32). Si ya hay un loop activo se conserva su
// inicio; si no, empieza en el beat mas cercano a la posicion actual. Devuelve false si falta el BPM.
JNI(jboolean, setLoop)(JNIEnv*, jobject, jint deck, jfloat beats) {
    if (!validDeck(deck) || beats <= 0.f) return JNI_FALSE;
    Deck& d = gEngine->decks[deck];
    std::shared_ptr<Track> t = std::atomic_load(&d.track);
    const float bpm = d.bpm.load();
    if (!t || bpm <= 0.f) return JNI_FALSE;
    const double per = 60.0 / bpm * t->sampleRate;
    const double len = per * beats;
    const double fb = d.firstBeat.load() * t->sampleRate;
    double start;
    if (d.loopOn.load()) {
        start = d.loopStart.load();
    } else {
        const double step = std::min(per, len);                      // loops cortos se alinean a su propio largo
        start = fb + std::round((d.position.load() - fb) / step) * step;
    }
    const double maxStart = (double)(t->avail.load() - 2) - len;
    if (maxStart <= 0.0) return JNI_FALSE;
    start = std::clamp(start, 0.0, maxStart);
    d.loopOn.store(false);
    d.loopStart.store(start);
    d.loopEnd.store(start + len);
    d.loopOn.store(true);
    return JNI_TRUE;
}
JNI(void, loopOff)(JNIEnv*, jobject, jint deck) {
    if (validDeck(deck)) gEngine->decks[deck].loopOn.store(false);
}
// {inicio, fin} del loop en segundos; {0, 0} si no hay loop
JNI(jdoubleArray, getLoop)(JNIEnv* env, jobject, jint deck) {
    double r[2] = {0.0, 0.0};
    if (validDeck(deck)) {
        Deck& d = gEngine->decks[deck];
        std::shared_ptr<Track> t = std::atomic_load(&d.track);
        if (t && d.loopOn.load()) {
            r[0] = d.loopStart.load() / t->sampleRate;
            r[1] = d.loopEnd.load() / t->sampleRate;
        }
    }
    jdoubleArray a = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(a, 0, 2, r);
    return a;
}

// ---- efectos ----
// type: 1 eco, 2 reverb, 3 flanger, 4 phaser, 5 gate. on = pad tocado/bloqueado. x, y: 0..1
JNI(void, setFx)(JNIEnv*, jobject, jint deck, jint type, jboolean on, jfloat x, jfloat y) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    d.fxType.store(std::clamp((int)type, 0, 5));
    d.fxX.store(std::clamp(x, 0.f, 1.f));
    d.fxY.store(std::clamp(y, 0.f, 1.f));
    d.fxOn.store(on == JNI_TRUE);
}

// ---- EQ / filtro (perillas -1..1, 0 = plano) ----
JNI(void, setEq)(JNIEnv*, jobject, jint deck, jfloat low, jfloat mid, jfloat high) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    d.eqLow.store(std::clamp(low, -1.f, 1.f));
    d.eqMid.store(std::clamp(mid, -1.f, 1.f));
    d.eqHigh.store(std::clamp(high, -1.f, 1.f));
}
JNI(void, setFilter)(JNIEnv*, jobject, jint deck, jfloat v) {
    if (validDeck(deck)) gEngine->decks[deck].filter.store(std::clamp(v, -1.f, 1.f));
}

// ---- BPM ----
// Analiza la pista cargada (puede tardar unos segundos: llamar fuera del hilo de la UI).
// Devuelve {bpm, primerBeatSegundos}; bpm = 0 si no se pudo detectar.
JNI(jfloatArray, analyzeBpm)(JNIEnv* env, jobject, jint deck) {
    float res[2] = {0.f, 0.f};
    if (validDeck(deck)) {
        std::shared_ptr<Track> t = std::atomic_load(&gEngine->decks[deck].track);
        if (t && t->done.load()) {
            const dj::BpmResult r = dj::analyzeBpm(t->pcm.data(), t->frames.load(), t->sampleRate);
            res[0] = r.bpm;
            res[1] = (float)r.firstBeat;
        }
    }
    jfloatArray arr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(arr, 0, 2, res);
    return arr;
}
JNI(void, setBpm)(JNIEnv*, jobject, jint deck, jfloat bpm, jdouble firstBeat) {
    if (!validDeck(deck)) return;
    gEngine->decks[deck].bpm.store(bpm);
    gEngine->decks[deck].firstBeat.store(firstBeat);
}
JNI(jfloat, getBpm)(JNIEnv*, jobject, jint deck) {
    return validDeck(deck) ? gEngine->decks[deck].bpm.load() : 0.f;
}
JNI(jdouble, getFirstBeat)(JNIEnv*, jobject, jint deck) {
    return validDeck(deck) ? gEngine->decks[deck].firstBeat.load() : 0.0;
}

// SYNC de un solo toque: iguala el tempo de `deck` al del otro deck y, si ambos suenan,
// alinea tambien los beats. Devuelve el nuevo factor de tempo, o -1 si falta el BPM de alguno.
JNI(jfloat, syncDeck)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return -1.f;
    Deck& d = gEngine->decks[deck];
    Deck& o = gEngine->decks[1 - deck];
    const float bOwn = d.bpm.load(), bOth = o.bpm.load();
    if (bOwn <= 0.f || bOth <= 0.f) return -1.f;

    float r = bOth * o.tempo.load() / bOwn;
    bool octave = false;  // p.ej. 77 BPM contra 154 BPM: se iguala a la octava mas cercana
    while (r > 1.41f) { r *= 0.5f; octave = true; }
    while (r < 0.71f) { r *= 2.f; octave = true; }
    r = std::clamp(r, 0.5f, 1.5f);
    d.tempo.store(r);

    std::shared_ptr<Track> tr = std::atomic_load(&d.track);
    std::shared_ptr<Track> to = std::atomic_load(&o.track);
    if (!octave && tr && to && d.playing.load() && o.playing.load()) {
        const double perO = 60.0 / bOth, perD = 60.0 / bOwn;
        const double posO = o.position.load() / to->sampleRate;
        const double posD = d.position.load() / tr->sampleRate;
        double fo = (posO - o.firstBeat.load()) / perO;
        double fd = (posD - d.firstBeat.load()) / perD;
        fo -= std::floor(fo);
        fd -= std::floor(fd);
        double diff = fd - fo;
        if (diff > 0.5) diff -= 1.0;
        if (diff < -0.5) diff += 1.0;
        gEngine->seekFrames(deck, (posD - diff * perD) * tr->sampleRate);
    }
    return r;
}
