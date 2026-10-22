// Ayudas para la separacion de voz con IA (modelo MDX-Net, Kim_Vocal_2): STFT / ISTFT del formato que espera el
// modelo, FFT de tamano 7680 (2^9 * 3 * 5) y remuestreo. Header-only y sin Android, para probarlo en PC.
#pragma once
#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <vector>

namespace sep {

constexpr int kNfft = 7680;
constexpr int kHop = 1024;
constexpr int kDimF = 3072;                 // bins de frecuencia que usa el modelo (de 3841)
constexpr int kDimT = 256;                  // cuadros de tiempo por bloque
constexpr int kChunk = kHop * (kDimT - 1);  // 261120 muestras por bloque
constexpr int kTrim = kNfft / 2;
using cpx = std::complex<float>;

// FFT compleja de radix mixto (estilo kissfft, butterfly generico).
class Fft {
public:
    explicit Fft(int n) : n_(n), tw_(n) {
        for (int i = 0; i < n; ++i) {
            const double a = -2.0 * M_PI * i / n;
            tw_[i] = cpx((float)std::cos(a), (float)std::sin(a));
        }
        int m = n;
        for (int p : {5, 3, 2}) {
            while (m % p == 0) { factors_.push_back(p); m /= p; factors_.push_back(m); }
        }
    }
    // in/out de n elementos (distintos)
    void forward(const cpx* in, cpx* out) const { work(out, in, 1, factors_.data()); }

private:
    void work(cpx* fout, const cpx* f, int fstride, const int* factors) const {
        const int p = *factors++;
        const int m = *factors++;
        cpx* beg = fout;
        cpx* end = fout + p * m;
        if (m == 1) {
            do { *fout = *f; f += fstride; } while (++fout != end);
        } else {
            do { work(fout, f, fstride * p, factors); f += fstride; } while ((fout += m) != end);
        }
        fout = beg;
        cpx scratch[8];
        for (int u = 0; u < m; ++u) {
            int k = u;
            for (int q1 = 0; q1 < p; ++q1) { scratch[q1] = fout[k]; k += m; }
            k = u;
            for (int q1 = 0; q1 < p; ++q1) {
                int twidx = 0;
                cpx acc = scratch[0];
                for (int q = 1; q < p; ++q) {
                    twidx += fstride * k;
                    if (twidx >= n_) twidx -= n_;
                    acc += scratch[q] * tw_[twidx];
                }
                fout[k] = acc;
                k += m;
            }
        }
    }
    int n_;
    std::vector<cpx> tw_;
    std::vector<int> factors_;
};

inline const Fft& plan() { static const Fft f(kNfft); return f; }
inline const std::vector<float>& window() {
    static const std::vector<float> w = [] {
        std::vector<float> v(kNfft);
        for (int i = 0; i < kNfft; ++i) v[i] = (float)(0.5 - 0.5 * std::cos(2.0 * M_PI * i / kNfft));   // hann periodica
        return v;
    }();
    return w;
}

// pcm: estereo intercalado int16 (frames cuadros). Bloque que empieza en startFrame (puede ser negativo o pasarse
// del final: se rellena con ceros). out: [4][kDimF][kDimT] = {L real, L imag, R real, R imag}.
inline void stftChunk(const int16_t* pcm, int64_t frames, int64_t startFrame, float* out) {
    const Fft& fft = plan();
    const std::vector<float>& win = window();
    std::vector<float> x(kChunk + 2 * kTrim);
    std::vector<cpx> in(kNfft), spec(kNfft);
    for (int ch = 0; ch < 2; ++ch) {
        // bloque de entrada + relleno reflejado de kTrim muestras a cada lado (como torch.stft center=True)
        std::vector<float> c(kChunk);
        for (int i = 0; i < kChunk; ++i) {
            const int64_t s = startFrame + i;
            c[i] = (s >= 0 && s < frames) ? pcm[s * 2 + ch] * (1.f / 32768.f) : 0.f;
        }
        for (int i = 0; i < kTrim; ++i) x[i] = c[kTrim - i];
        for (int i = 0; i < kChunk; ++i) x[kTrim + i] = c[i];
        for (int i = 0; i < kTrim; ++i) x[kTrim + kChunk + i] = c[kChunk - 2 - i];
        for (int t = 0; t < kDimT; ++t) {
            const float* seg = x.data() + (size_t)t * kHop;
            for (int i = 0; i < kNfft; ++i) in[i] = cpx(seg[i] * win[i], 0.f);
            fft.forward(in.data(), spec.data());
            for (int f = 0; f < kDimF; ++f) {
                out[((size_t)(ch * 2 + 0) * kDimF + f) * kDimT + t] = spec[f].real();
                out[((size_t)(ch * 2 + 1) * kDimF + f) * kDimT + t] = spec[f].imag();
            }
        }
    }
}

// model: [4][kDimF][kDimT] (salida del modelo). out: 2 * kChunk, plano ([L...][R...]).
inline void istftChunk(const float* model, float* out) {
    const Fft& fft = plan();
    const std::vector<float>& win = window();
    const int total = kChunk + 2 * kTrim;
    std::vector<cpx> spec(kNfft), tmp(kNfft);
    std::vector<float> acc(total), wsum(total);
    for (int ch = 0; ch < 2; ++ch) {
        std::fill(acc.begin(), acc.end(), 0.f);
        std::fill(wsum.begin(), wsum.end(), 0.f);
        for (int t = 0; t < kDimT; ++t) {
            // espectro hermitiano; la IFFT se hace como conj(FFT(conj(X))) / N
            std::fill(spec.begin(), spec.end(), cpx(0.f, 0.f));
            for (int f = 0; f < kDimF; ++f) {
                const float re = model[((size_t)(ch * 2 + 0) * kDimF + f) * kDimT + t];
                const float im = model[((size_t)(ch * 2 + 1) * kDimF + f) * kDimT + t];
                spec[f] = cpx(re, -im);                                  // conj
                if (f > 0) spec[kNfft - f] = cpx(re, im);                // conj de la mitad espejo
            }
            fft.forward(spec.data(), tmp.data());
            float* a = acc.data() + (size_t)t * kHop;
            float* w = wsum.data() + (size_t)t * kHop;
            for (int i = 0; i < kNfft; ++i) {
                a[i] += tmp[i].real() * (1.f / kNfft) * win[i];
                w[i] += win[i] * win[i];
            }
        }
        float* o = out + (size_t)ch * kChunk;
        for (int i = 0; i < kChunk; ++i) {
            const float w = wsum[kTrim + i];
            o[i] = acc[kTrim + i] / std::max(w, 1e-8f);
        }
    }
}

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

}  // namespace sep
