// DSP por deck: EQ de 3 bandas (cruces Linkwitz-Riley) + filtro DJ (LP a la izquierda, HP a la derecha).
// Header-only y sin dependencias de Android, para poder probarlo en cualquier PC.
#pragma once
#include <algorithm>
#include <cmath>

namespace dj {

constexpr double kPi = 3.14159265358979323846;

struct BiquadCoef {
    float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f;
};

inline BiquadCoef normalize(double b0, double b1, double b2, double a0, double a1, double a2) {
    BiquadCoef c;
    c.b0 = (float)(b0 / a0);
    c.b1 = (float)(b1 / a0);
    c.b2 = (float)(b2 / a0);
    c.a1 = (float)(a1 / a0);
    c.a2 = (float)(a2 / a0);
    return c;
}

// Formulas del "Audio EQ Cookbook" (RBJ).
inline BiquadCoef lowpass(double fs, double f0, double Q) {
    const double w = 2.0 * kPi * f0 / fs, cs = std::cos(w), al = std::sin(w) / (2.0 * Q);
    return normalize((1 - cs) / 2, 1 - cs, (1 - cs) / 2, 1 + al, -2 * cs, 1 - al);
}
inline BiquadCoef highpass(double fs, double f0, double Q) {
    const double w = 2.0 * kPi * f0 / fs, cs = std::cos(w), al = std::sin(w) / (2.0 * Q);
    return normalize((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al);
}

// Forma transpuesta II, estado independiente por canal (0 = L, 1 = R).
struct Biquad {
    float z1[2] = {0.f, 0.f}, z2[2] = {0.f, 0.f};
    // Offset de ~-300 dB: mantiene el estado fuera del rango "denormal", que en ARM es muy lento
    // cuando el filtro se queda sin senal (deck detenido) y puede causar cortes de audio.
    static constexpr float kAntiDenormal = 1e-15f;
    inline float process(int ch, float x, const BiquadCoef& c) {
        const float y = c.b0 * x + z1[ch];
        z1[ch] = c.b1 * x - c.a1 * y + z2[ch] + kAntiDenormal;
        z2[ch] = c.b2 * x - c.a2 * y + kAntiDenormal;
        return y;
    }
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0.f; }
};

// Perilla -1..1 -> dB. Centro = 0 dB, +6 dB arriba, hasta -36 dB abajo y "kill" al tope.
inline float knobToDb(float k) {
    k = std::clamp(k, -1.f, 1.f);
    if (k <= -0.98f) return -80.f;
    return k >= 0.f ? 6.f * k : 36.f * k;
}

// Perilla -1..1 -> ganancia lineal
inline float knobToGain(float k) { return std::pow(10.f, knobToDb(k) / 20.f); }

// EQ de 3 bandas con cruces Linkwitz-Riley de 4to orden (250 Hz y 3 kHz) + filtro DJ.
// La banda grave pasa por un all-pass al cruce alto para que la suma sea plana en magnitud.
class DeckFx {
public:
    void process(float* buf, int frames, float fs, float kLo, float kMid, float kHi, float kFlt) {
        update(fs, kFlt);
        const float tLo = knobToGain(kLo), tMid = knobToGain(kMid), tHi = knobToGain(kHi);
        for (int i = 0; i < frames; ++i) {
            gLo_ += (tLo - gLo_) * 0.003f;      // suavizado: evita "zipper noise" al mover perillas
            gMid_ += (tMid - gMid_) * 0.003f;
            gHi_ += (tHi - gHi_) * 0.003f;
            for (int ch = 0; ch < 2; ++ch) {
                const float x = buf[2 * i + ch];
                float low = lp1b_.process(ch, lp1a_.process(ch, x, cLp1_), cLp1_);
                float hp = hp1b_.process(ch, hp1a_.process(ch, x, cHp1_), cHp1_);
                const float mid = lp2b_.process(ch, lp2a_.process(ch, hp, cLp2_), cLp2_);
                const float high = hp2b_.process(ch, hp2a_.process(ch, hp, cHp2_), cHp2_);
                const float lowAp = apL_.process(ch, apLa_.process(ch, low, cLp2_), cLp2_) +
                                    apH_.process(ch, apHa_.process(ch, low, cHp2_), cHp2_);
                float y = gLo_ * lowAp + gMid_ * mid + gHi_ * high;
                if (fltOn_) y = flt_.process(ch, y, cFlt_);
                buf[2 * i + ch] = y;
            }
        }
    }

private:
    void update(float fs, float kFlt) {
        if (first_ || fs != fs_) {
            const double q = 0.70710678;
            cLp1_ = lowpass(fs, 250.0, q);
            cHp1_ = highpass(fs, 250.0, q);
            cLp2_ = lowpass(fs, 3000.0, q);
            cHp2_ = highpass(fs, 3000.0, q);
        }
        if (first_ || fs != fs_ || kFlt != kFlt_) {
            const bool on = std::fabs(kFlt) >= 0.03f;
            if (on) {
                if (kFlt < 0.f) cFlt_ = lowpass(fs, 20000.0 * std::pow(0.01, -kFlt), 0.9);   // 20 kHz -> 200 Hz
                else            cFlt_ = highpass(fs, 20.0 * std::pow(400.0, kFlt), 0.9);     // 20 Hz -> 8 kHz
            } else {
                flt_.reset();
            }
            fltOn_ = on;
            kFlt_ = kFlt;
        }
        fs_ = fs;
        first_ = false;
    }

    // LR4 = dos Butterworth en cascada ("a" y "b")
    Biquad lp1a_, lp1b_, hp1a_, hp1b_, lp2a_, lp2b_, hp2a_, hp2b_;
    Biquad apLa_, apL_, apHa_, apH_, flt_;
    BiquadCoef cLp1_, cHp1_, cLp2_, cHp2_, cFlt_;
    float gLo_ = 1.f, gMid_ = 1.f, gHi_ = 1.f, kFlt_ = 0.f, fs_ = 0.f;
    bool fltOn_ = false, first_ = true;
};

// Quitar voz / dejar solo la voz (version rapida, sin IA): trabaja con el canal central (L+R)/2, donde casi siempre
// va la voz principal. Se limita a la banda 150 Hz - 7 kHz para no tocar el bombo/bajo ni los platillos.
//   modo 1 = SIN VOZ: se resta esa banda central de L y R (queda lo que esta a los lados + graves).
//   modo 2 = SOLO VOZ: se deja solo la banda central, en mono.
// El cambio de modo se mezcla con suavizado para evitar clicks.
class VocalFx {
public:
    void process(float* buf, int frames, float fs, int mode) {
        if (first_ || fs != fs_) {
            const double q = 0.70710678;
            cLo150_ = lowpass(fs, 150.0, q);
            cHi150_ = highpass(fs, 150.0, q);
            cLo7_ = lowpass(fs, 7000.0, q);
            cHi7_ = highpass(fs, 7000.0, q);
            fs_ = fs;
            first_ = false;
        }
        const float tw = mode != 0 ? 1.f : 0.f;
        if (mode != 0) mode_ = mode;
        if (w_ < 0.0005f && tw == 0.f) {          // apagado del todo: sin costo y sin estado viejo
            if (w_ != 0.f) { w_ = 0.f; resetAll(); }
            return;
        }
        for (int i = 0; i < frames; ++i) {
            w_ += (tw - w_) * 0.0015f;
            const float l = buf[2 * i], r = buf[2 * i + 1];
            const float m = 0.5f * (l + r);
            // banda de voz: pasa-banda 150 Hz - 7 kHz (LR4) del canal central
            const float band = bHpB_.process(0, bHpA_.process(0, bLpB_.process(0, bLpA_.process(0, m, cLo7_), cLo7_), cHi150_), cHi150_);
            float pl, pr;
            if (mode_ == 2) {
                pl = pr = 1.15f * band;
            } else {
                // L y R pasan por all-pass con el MISMO desfase que el pasa-banda (LR4: baja + alta = all-pass),
                // asi la resta cancela de verdad el centro dentro de la banda
                pl = allpass(0, l) - 0.95f * band;
                pr = allpass(1, r) - 0.95f * band;
            }
            buf[2 * i] = l + (pl - l) * w_;
            buf[2 * i + 1] = r + (pr - r) * w_;
        }
    }

private:
    inline float allpass(int ch, float x) {
        const float a = aLo150B_.process(ch, aLo150A_.process(ch, x, cLo150_), cLo150_) +
                        aHi150B_.process(ch, aHi150A_.process(ch, x, cHi150_), cHi150_);
        return aLo7B_.process(ch, aLo7A_.process(ch, a, cLo7_), cLo7_) +
               aHi7B_.process(ch, aHi7A_.process(ch, a, cHi7_), cHi7_);
    }
    void resetAll() {
        bLpA_.reset(); bLpB_.reset(); bHpA_.reset(); bHpB_.reset();
        aLo150A_.reset(); aLo150B_.reset(); aHi150A_.reset(); aHi150B_.reset();
        aLo7A_.reset(); aLo7B_.reset(); aHi7A_.reset(); aHi7B_.reset();
    }

    Biquad bLpA_, bLpB_, bHpA_, bHpB_;                         // pasa-banda del centro (estado del canal 0)
    Biquad aLo150A_, aLo150B_, aHi150A_, aHi150B_, aLo7A_, aLo7B_, aHi7A_, aHi7B_;   // all-pass por canal
    BiquadCoef cLo150_, cHi150_, cLo7_, cHi7_;
    float w_ = 0.f, fs_ = 0.f;
    int mode_ = 1;
    bool first_ = true;
};

}  // namespace dj
