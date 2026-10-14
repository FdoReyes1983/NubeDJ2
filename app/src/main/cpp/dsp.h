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

}  // namespace dj
