// Separacion de voz/instrumental en tiempo (casi) real con Spleeter 2 stems (ONNX): STFT, mascara suave, ISTFT por
// bloques y entrega progresiva. Solo la senal esta aqui; el modelo corre en Kotlin (ONNX Runtime). Header-only y sin
// Android para poder probarlo en PC.
#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstring>
#include <vector>

namespace stem {

constexpr int kNf = 4096;            // ventana FFT
constexpr int kHop = 1024;
constexpr int kBins = 1024;          // bins que ve el modelo (hasta ~11 kHz)
constexpr int kFr = 512;             // cuadros por bloque (~11.9 s)
constexpr int kHalf = kNf / 2 + 1;
constexpr int kTail = kNf - kHop;    // muestras de un bloque que aun esperan al siguiente
using cpx = std::complex<float>;

// FFT compleja radix 2 iterativa (tamano potencia de 2).
class Fft {
public:
    explicit Fft(int n) : n_(n), tw_(n / 2), rev_(n) {
        for (int i = 0; i < n / 2; ++i) {
            const double a = -2.0 * M_PI * i / n;
            tw_[i] = cpx((float)std::cos(a), (float)std::sin(a));
        }
        int bits = 0;
        while ((1 << bits) < n) ++bits;
        for (int i = 0; i < n; ++i) {
            int r = 0;
            for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
            rev_[i] = r;
        }
    }
    void forward(cpx* a) const {
        for (int i = 0; i < n_; ++i) if (i < rev_[i]) std::swap(a[i], a[rev_[i]]);
        for (int len = 2; len <= n_; len <<= 1) {
            const int half = len / 2, step = n_ / len;
            for (int i = 0; i < n_; i += len) {
                for (int j = 0; j < half; ++j) {
                    const cpx t = a[i + j + half] * tw_[j * step];
                    a[i + j + half] = a[i + j] - t;
                    a[i + j] += t;
                }
            }
        }
    }
private:
    int n_;
    std::vector<cpx> tw_;
    std::vector<int> rev_;
};

inline const Fft& plan() { static const Fft f(kNf); return f; }
inline const std::vector<float>& window() {
    static const std::vector<float> w = [] {
        std::vector<float> v(kNf);
        for (int i = 0; i < kNf; ++i) v[i] = (float)(0.5 - 0.5 * std::cos(2.0 * M_PI * i / kNf));   // hann periodica
        return v;
    }();
    return w;
}

// Resumen del trabajo: un Job separa una pista completa (estereo int16 intercalado a 44.1 kHz) bloque a bloque.
class Job {
public:
    // voc/inst: salidas del mismo largo que la mezcla (frames*2). avail: frames ya finales (para tocar mientras avanza).
    Job(const int16_t* mix, int64_t frames, int16_t* voc, int16_t* inst, std::atomic<int64_t>* avail)
        : mix_(mix), n_(frames), voc_(voc), inst_(inst), avail_(avail) {
        nFrames_ = (int)(1 + frames / kHop);
        patches_ = (nFrames_ + kFr - 1) / kFr;
        const size_t L = (size_t)(kFr - 1) * kHop + kNf;
        acc_[0].assign(L, 0.f); acc_[1].assign(L, 0.f); wsum_.assign(L, 0.f);
        spec_[0].resize((size_t)kFr * kHalf); spec_[1].resize((size_t)kFr * kHalf);
    }
    int patches() const { return patches_; }

    // STFT del bloque p; deja las magnitudes (primeros 1024 bins) en out: [2][kFr][kBins]. Guarda el espectro complejo.
    void input(int p, float* out) {
        const Fft& fft = plan();
        const std::vector<float>& win = window();
        std::vector<cpx> buf(kNf);
        const int real = std::min(kFr, nFrames_ - p * kFr);
        for (int ch = 0; ch < 2; ++ch) {
            for (int t = 0; t < kFr; ++t) {
                float* o = out + ((size_t)ch * kFr + t) * kBins;
                if (t >= real) { std::fill(o, o + kBins, 0.f); continue; }
                const int64_t base = (int64_t)(p * kFr + t) * kHop - kNf / 2;
                for (int i = 0; i < kNf; ++i) buf[i] = cpx(sample(ch, base + i) * win[i], 0.f);
                fft.forward(buf.data());
                cpx* sp = &spec_[ch][(size_t)t * kHalf];
                for (int f = 0; f < kHalf; ++f) sp[f] = buf[f];
                for (int f = 0; f < kBins; ++f) o[f] = std::abs(buf[f]);
            }
        }
    }

    // yv: salida del modelo de voz para el bloque p ([2][kFr][kBins]): magnitud estimada de la voz.
    // Devuelve false si la salida trae NaN/Inf (comprobado por bits: -ffast-math no deja usar isfinite).
    bool apply(int p, const float* yv) {
        for (size_t i = 0; i < (size_t)2 * kFr * kBins; ++i) {
            uint32_t a;
            std::memcpy(&a, yv + i, 4);
            if ((a & 0x7f800000u) == 0x7f800000u) return false;
        }
        const Fft& fft = plan();
        const std::vector<float>& win = window();
        const int real = std::min(kFr, nFrames_ - p * kFr);
        std::vector<cpx> buf(kNf);
        std::vector<float> mask(kHalf);
        for (int ch = 0; ch < 2; ++ch) {
            for (int t = 0; t < real; ++t) {
                const float* v = yv + ((size_t)ch * kFr + t) * kBins;
                const cpx* sp0 = &spec_[ch][(size_t)t * kHalf];
                double msum = 0.0;
                for (int f = 0; f < kBins; ++f) {       // mascara = voz estimada / mezcla, entre 0 y 1
                    mask[f] = std::clamp(v[f] / (std::abs(sp0[f]) + 1e-8f), 0.f, 1.f);
                    msum += mask[f];
                }
                const float fill = (float)(msum / kBins);
                for (int f = kBins; f < kHalf; ++f) mask[f] = fill;
                const cpx* sp = &spec_[ch][(size_t)t * kHalf];
                // IFFT = conj(FFT(conj(X))) / N con X hermitiano
                for (int f = 0; f < kHalf; ++f) {
                    const cpx x = sp[f] * mask[f];
                    buf[f] = std::conj(x);
                    if (f > 0 && f < kNf / 2) buf[kNf - f] = x;
                }
                fft.forward(buf.data());
                float* ac = acc_[ch].data() + (size_t)t * kHop;
                for (int i = 0; i < kNf; ++i) ac[i] += buf[i].real() * (1.f / kNf) * win[i];
                if (ch == 0) {
                    float* w = wsum_.data() + (size_t)t * kHop;
                    for (int i = 0; i < kNf; ++i) w[i] += win[i] * win[i];
                }
            }
        }
        finalize(p);
        return true;
    }

private:
    float sample(int ch, int64_t s) const {
        if (s < 0) s = -s;
        if (s >= n_) s = 2 * (n_ - 1) - s;
        if (s < 0) s = 0;
        return mix_[s * 2 + ch] * (1.f / 32768.f);
    }

    void finalize(int p) {
        const bool last = (p == patches_ - 1);
        const int64_t base = (int64_t)p * kFr * kHop - kNf / 2;     // muestra global del indice local 0
        const int64_t count = last ? (int64_t)acc_[0].size() : (int64_t)kFr * kHop;
        for (int64_t i = 0; i < count; ++i) {
            const int64_t s = base + i;
            if (s < 0 || s >= n_) continue;
            const float w = std::max(wsum_[i], 1e-8f);
            for (int ch = 0; ch < 2; ++ch) {
                const float v = std::clamp(acc_[ch][i] / w, -1.f, 1.f);
                const int vi = (int)std::lrintf(v * 32767.f);
                voc_[s * 2 + ch] = (int16_t)vi;
                inst_[s * 2 + ch] = (int16_t)std::clamp((int)mix_[s * 2 + ch] - vi, -32768, 32767);
            }
        }
        if (last) {
            avail_->store(n_, std::memory_order_release);
            return;
        }
        // lo que falta por completar pasa al inicio del siguiente bloque
        for (int ch = 0; ch < 2; ++ch) {
            std::memmove(acc_[ch].data(), acc_[ch].data() + (size_t)kFr * kHop, (size_t)kTail * sizeof(float));
            std::fill(acc_[ch].begin() + kTail, acc_[ch].end(), 0.f);
        }
        std::memmove(wsum_.data(), wsum_.data() + (size_t)kFr * kHop, (size_t)kTail * sizeof(float));
        std::fill(wsum_.begin() + kTail, wsum_.end(), 0.f);
        avail_->store(std::min<int64_t>(n_, base + count), std::memory_order_release);
    }

    const int16_t* mix_;
    int64_t n_;
    int16_t* voc_;
    int16_t* inst_;
    std::atomic<int64_t>* avail_;
    int nFrames_ = 0, patches_ = 0;
    std::vector<float> acc_[2], wsum_;
    std::vector<cpx> spec_[2];
};

// Remuestreo lineal de estereo intercalado int16.
inline std::vector<int16_t> resampleStereo(const int16_t* in, int64_t frames, int srcRate, int dstRate) {
    const int64_t outFrames = (int64_t)((double)frames * dstRate / srcRate);
    std::vector<int16_t> out((size_t)outFrames * 2);
    const double step = (double)srcRate / dstRate;
    for (int64_t j = 0; j < outFrames; ++j) {
        const double p = j * step;
        const int64_t i0 = (int64_t)p;
        const int64_t i1 = std::min<int64_t>(i0 + 1, frames - 1);
        const float f = (float)(p - (double)i0);
        for (int ch = 0; ch < 2; ++ch) {
            const float a = in[i0 * 2 + ch], b = in[i1 * 2 + ch];
            out[(size_t)j * 2 + ch] = (int16_t)std::lrintf(a + (b - a) * f);
        }
    }
    return out;
}

}  // namespace stem
