// Nube DJ 2 - motor de audio (Oboe, baja latencia).
// 2 decks, playhead propio con velocidad variable (scratch), crossfader.
#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include "bpm.h"
#include "dsp.h"

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
    int64_t frames = 0;
    std::vector<float> overview;
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
    // EQ / filtro (perillas -1..1) y BPM de la pista cargada (0 = desconocido)
    std::atomic<float> eqLow{0.f}, eqMid{0.f}, eqHigh{0.f}, filter{0.f};
    std::atomic<float> bpm{0.f};
    std::atomic<double> firstBeat{0.0};
    // solo hilo de audio
    double pos = 0.0;
    float rate = 0.f;
    dj::DeckFx fx;
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
        if (!t || t->frames < 2) {
            d.level.store(d.level.load() * 0.85f);
            return;
        }
        const bool scr = d.scratching.load();
        if (d.seekPending.exchange(false)) {
            d.pos = std::clamp(d.seekTarget.load(), 0.0, (double)(t->frames - 1));
            if (!d.playing.load() && !scr) d.rate = 0.f;
        }
        const double ratio = (double)t->sampleRate / (double)outRate_;
        float target = 0.f;
        if (scr) target = d.scratchVel.load();
        else if (d.playing.load()) target = d.tempo.load();
        const float a = scr ? 0.007f : 0.0015f;  // suavizado de velocidad
        const double last = (double)(t->frames - 1);
        const int16_t* pcm = t->pcm.data();
        constexpr float k = 1.f / 32768.f;

        float* tmp = tmp_;
        double pos = d.pos;
        float rate = d.rate;
        for (int32_t i = 0; i < n; ++i) {
            rate += (target - rate) * a;
            pos += (double)rate * ratio;
            if (pos <= 0.0) {
                pos = 0.0;
                if (rate < 0.f) rate = 0.f;
            } else if (pos >= last) {
                pos = last;
                if (rate > 0.f) {
                    rate = 0.f;
                    if (!scr) d.playing.store(false);
                }
            }
            const float g = std::min(1.f, std::fabs(rate) * 50.f);  // silencio si esta detenido
            float l = 0.f, r = 0.f;
            if (g > 0.f) {
                const int64_t i0 = (int64_t)pos;
                const int64_t i1 = std::min<int64_t>(i0 + 1, t->frames - 1);
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
    d.loading = std::make_shared<Track>();
    d.loading->sampleRate = sampleRate > 0 ? sampleRate : 44100;
    if (estFrames > 0) d.loading->pcm.reserve((size_t)estFrames * 2 + 8192);
}

JNI(void, loadAppend)(JNIEnv* env, jobject, jint deck, jshortArray arr, jint frames) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    if (!d.loading || frames <= 0) return;
    jshort* p = env->GetShortArrayElements(arr, nullptr);
    if (!p) return;
    d.loading->pcm.insert(d.loading->pcm.end(), p, p + (size_t)frames * 2);
    env->ReleaseShortArrayElements(arr, p, JNI_ABORT);
}

JNI(jboolean, loadEnd)(JNIEnv*, jobject, jint deck) {
    if (!validDeck(deck)) return JNI_FALSE;
    Deck& d = gEngine->decks[deck];
    std::shared_ptr<Track> t = d.loading;
    d.loading.reset();
    if (!t) return JNI_FALSE;
    t->frames = (int64_t)(t->pcm.size() / 2);
    if (t->frames < 2) return JNI_FALSE;
    t->pcm.shrink_to_fit();

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

    d.playing.store(false);
    d.cuePreview.store(false);
    d.bpm.store(0.f);
    d.firstBeat.store(0.0);
    d.cue.store(0.0);
    d.seekTarget.store(0.0);
    d.seekPending.store(true);
    std::atomic_store(&d.track, t);
    return JNI_TRUE;
}

// ---- transporte ----
JNI(void, setPlaying)(JNIEnv*, jobject, jint deck, jboolean playing) {
    if (!validDeck(deck)) return;
    Deck& d = gEngine->decks[deck];
    auto t = std::atomic_load(&d.track);
    if (!t) return;
    d.cuePreview.store(false);
    if (playing && d.position.load() >= (double)(t->frames - 2)) gEngine->seekFrames(deck, 0.0);
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
    return t ? (double)t->frames / t->sampleRate : 0.0;
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
        if (t) {
            const dj::BpmResult r = dj::analyzeBpm(t->pcm.data(), t->frames, t->sampleRate);
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
