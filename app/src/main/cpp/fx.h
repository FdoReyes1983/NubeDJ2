// Efectos por deck: ECHO, REVERB, FLANGER, PHASER, GATE. Header-only, sin dependencias de Android.
// Un solo efecto activo por deck; X e Y vienen de un pad XY (0..1). Y = cantidad (mezcla), X = tiempo / velocidad.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace dj {

enum FxType { FX_OFF = 0, FX_ECHO = 1, FX_REVERB = 2, FX_FLANGER = 3, FX_PHASER = 4, FX_GATE = 5 };

class DeckEffects {
public:
    DeckEffects() {
        // memoria reservada una sola vez (sirve hasta 96 kHz): nada de asignaciones en el hilo de audio
        echoSize_ = 96000 * 3 / 2 + 16;
        flSize_ = 4096;
        for (int c = 0; c < 2; ++c) {
            echo_[c].assign((size_t)echoSize_, 0.f);
            fl_[c].assign((size_t)flSize_, 0.f);
            for (int i = 0; i < 4; ++i) comb_[c][i].b.assign((size_t)(kCombLen[i] + c * 23), 0.f);
            for (int i = 0; i < 2; ++i) ap_[c][i].b.assign((size_t)(kApLen[i] + c * 23), 0.f);
        }
    }

    // buf: estereo intercalado float, se procesa en el lugar. beatSec = duracion de 1 beat (0 = BPM desconocido).
    void process(float* buf, int n, float sr, int type, bool on, float x, float y, float beatSec) {
        if (type != lastType_) { clear(); lastType_ = type; }
        const bool active = on && type != FX_OFF;
        x = std::clamp(x, 0.f, 1.f);
        y = std::clamp(y, 0.f, 1.f);
        const bool tail = (type == FX_ECHO || type == FX_REVERB);   // estos dejan cola al soltar
        if (active) held_ = y;
        const float sendT = (tail ? (active ? 1.f : 0.f) : 1.f);
        const float wetT = tail ? (active ? y : held_) : (active ? y : 0.f);
        const float step = 1.f / (0.02f * sr);
        if (!active) {   // sin efecto: no gastar CPU cuando ya no queda nada que procesar
            if (tail) {
                idle_ += n;
                if (idle_ > (long)(8.f * sr)) {          // la cola ya se apago
                    if (!clean_) { clear(); clean_ = true; }
                    return;
                }
            } else if (wet_ <= 0.f) {
                return;
            }
        } else {
            idle_ = 0;
            clean_ = false;
        }

        // parametros por bloque
        float echoT = 0.3f;
        if (type == FX_ECHO) {
            if (beatSec > 0.f) {
                static const float kBeats[4] = {0.25f, 0.5f, 0.75f, 1.f};
                echoT = beatSec * kBeats[std::min(3, (int)(x * 4.f))];
                while (echoT > 1.4f) echoT *= 0.5f;
            } else {
                echoT = 0.12f + 0.6f * x;
            }
        }
        const float rvFb = 0.70f + 0.26f * x;
        const float rate = (type == FX_FLANGER) ? 0.08f * std::pow(30.f, x)      // 0.08 .. 2.4 Hz
                         : (type == FX_PHASER) ? 0.10f * std::pow(30.f, x)       // 0.1 .. 3 Hz
                                               : 0.f;
        float gateCycle = 0.25f;
        if (type == FX_GATE) {
            static const float kCyc[4] = {1.f, 0.5f, 0.25f, 0.125f};
            const float c = kCyc[std::min(3, (int)(x * 4.f))];
            gateCycle = beatSec > 0.f ? beatSec * c : 0.5f * c;
        }

        for (int i = 0; i < n; ++i) {
            send_ += std::clamp(sendT - send_, -step, step);
            wet_ += std::clamp(wetT - wet_, -step, step);
            const float inL = buf[2 * i], inR = buf[2 * i + 1];
            float oL = inL, oR = inR;
            switch (type) {
                case FX_ECHO: {
                    echoTimeSm_ += (echoT - echoTimeSm_) * 0.0004f;
                    const float d = std::clamp(echoTimeSm_ * sr, 2.f, (float)(echoSize_ - 4));
                    float dl[2];
                    for (int c = 0; c < 2; ++c) {
                        float rp = (float)echoW_ - d;
                        if (rp < 0.f) rp += (float)echoSize_;
                        const int i0 = (int)rp;
                        const int i1 = (i0 + 1) % echoSize_;
                        const float f = rp - (float)i0;
                        dl[c] = echo_[c][i0] + (echo_[c][i1] - echo_[c][i0]) * f;
                        echoLp_[c] += 0.35f * (dl[c] - echoLp_[c]);        // repeticiones algo mas oscuras
                        echo_[c][echoW_] = (c == 0 ? inL : inR) * send_ + echoLp_[c] * 0.55f + 1e-15f;
                    }
                    echoW_ = (echoW_ + 1) % echoSize_;
                    oL = inL + dl[0] * wet_;
                    oR = inR + dl[1] * wet_;
                    break;
                }
                case FX_REVERB: {
                    const float mono = 0.5f * (inL + inR) * send_ * 0.03f;
                    float out[2];
                    for (int c = 0; c < 2; ++c) {
                        float s = 0.f;
                        for (int k = 0; k < 4; ++k) {
                            Comb& cb = comb_[c][k];
                            const float v = cb.b[(size_t)cb.i];
                            cb.lp += 0.65f * (v - cb.lp);                  // amortiguacion de agudos
                            cb.b[(size_t)cb.i] = mono + cb.lp * rvFb + 1e-15f;
                            if (++cb.i >= (int)cb.b.size()) cb.i = 0;
                            s += v;
                        }
                        for (int k = 0; k < 2; ++k) {
                            AP& a = ap_[c][k];
                            const float v = a.b[(size_t)a.i];
                            const float o = -s + v;
                            a.b[(size_t)a.i] = s + v * 0.5f;
                            if (++a.i >= (int)a.b.size()) a.i = 0;
                            s = o;
                        }
                        out[c] = s;
                    }
                    oL = inL + out[0] * 3.f * wet_;
                    oR = inR + out[1] * 3.f * wet_;
                    break;
                }
                case FX_FLANGER: {
                    lfo_ += 6.2831853f * rate / sr;
                    if (lfo_ > 6.2831853f) lfo_ -= 6.2831853f;
                    const float d = (0.0025f + 0.002f * std::sin(lfo_)) * sr;
                    float dl[2];
                    for (int c = 0; c < 2; ++c) {
                        const float dd = c == 0 ? d : (0.0025f + 0.002f * std::sin(lfo_ + 1.2f)) * sr;
                        float rp = (float)flW_ - dd;
                        if (rp < 0.f) rp += (float)flSize_;
                        const int i0 = (int)rp;
                        const int i1 = (i0 + 1) % flSize_;
                        const float f = rp - (float)i0;
                        dl[c] = fl_[c][i0] + (fl_[c][i1] - fl_[c][i0]) * f;
                        fl_[c][flW_] = (c == 0 ? inL : inR) + dl[c] * 0.55f + 1e-15f;
                    }
                    flW_ = (flW_ + 1) % flSize_;
                    oL = inL + dl[0] * wet_;
                    oR = inR + dl[1] * wet_;
                    break;
                }
                case FX_PHASER: {
                    lfo_ += 6.2831853f * rate / sr;
                    if (lfo_ > 6.2831853f) lfo_ -= 6.2831853f;
                    if ((i & 15) == 0) {
                        const float fq = 400.f * std::pow(2.f, 2.5f * (0.5f + 0.5f * std::sin(lfo_)));  // 400 .. 2260 Hz
                        const float t = std::tan(3.14159265f * fq / sr);
                        apCoef_ = (t - 1.f) / (t + 1.f);
                    }
                    float o[2];
                    for (int c = 0; c < 2; ++c) {
                        float s = (c == 0 ? inL : inR) + phFb_[c] * 0.5f;
                        for (int k = 0; k < 4; ++k) {          // pasa-todo de 1er orden
                            const float y1 = apCoef_ * s + phZ_[c][k];
                            phZ_[c][k] = s - apCoef_ * y1 + 1e-15f;
                            s = y1;
                        }
                        phFb_[c] = s;
                        o[c] = s;
                    }
                    // mezcla dry + allpass (muescas): out = in*(1-w/2) + ap*(w/2)  -> notches profundos con w = 1
                    oL = inL * (1.f - 0.5f * wet_) + o[0] * (0.5f * wet_);
                    oR = inR * (1.f - 0.5f * wet_) + o[1] * (0.5f * wet_);
                    break;
                }
                case FX_GATE: {
                    gatePh_ += 1.f / (gateCycle * sr);
                    if (gatePh_ >= 1.f) gatePh_ -= 1.f;
                    const float target = gatePh_ < 0.5f ? 1.f : 0.f;
                    gateG_ += (target - gateG_) * 0.012f;        // bordes de ~2 ms
                    const float g = 1.f - wet_ + wet_ * gateG_;
                    oL = inL * g;
                    oR = inR * g;
                    break;
                }
                default: break;
            }
            buf[2 * i] = oL;
            buf[2 * i + 1] = oR;
        }
    }

private:
    struct Comb { std::vector<float> b; int i = 0; float lp = 0.f; };
    struct AP { std::vector<float> b; int i = 0; };
    static constexpr int kCombLen[4] = {1116, 1188, 1277, 1356};
    static constexpr int kApLen[2] = {556, 441};

    void clear() {
        for (int c = 0; c < 2; ++c) {
            std::fill(echo_[c].begin(), echo_[c].end(), 0.f);
            std::fill(fl_[c].begin(), fl_[c].end(), 0.f);
            for (auto& k : comb_[c]) { std::fill(k.b.begin(), k.b.end(), 0.f); k.lp = 0.f; }
            for (auto& k : ap_[c]) std::fill(k.b.begin(), k.b.end(), 0.f);
            echoLp_[c] = 0.f; phFb_[c] = 0.f;
            for (int k = 0; k < 4; ++k) phZ_[c][k] = 0.f;
        }
        send_ = wet_ = held_ = 0.f;
        gateG_ = 1.f;
    }

    std::vector<float> echo_[2], fl_[2];
    int echoSize_ = 0, flSize_ = 0, echoW_ = 0, flW_ = 0;
    float echoTimeSm_ = 0.3f, echoLp_[2] = {0.f, 0.f};
    Comb comb_[2][4];
    AP ap_[2][2];
    float lfo_ = 0.f, apCoef_ = 0.f, phFb_[2] = {0.f, 0.f}, phZ_[2][4] = {};
    float gatePh_ = 0.f, gateG_ = 1.f;
    float send_ = 0.f, wet_ = 0.f, held_ = 0.f;
    int lastType_ = 0;
    long idle_ = 0;
    bool clean_ = true;
};

}  // namespace dj
