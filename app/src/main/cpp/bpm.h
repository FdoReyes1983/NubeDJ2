// Deteccion de BPM y primer beat (tempo constante). Header-only, sin dependencias de Android.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace dj {

struct BpmResult {
    float bpm = 0.f;          // 0 = no se pudo detectar
    double firstBeat = 0.0;   // segundos hasta el primer beat
};

// pcm: estereo intercalado int16.
inline BpmResult analyzeBpm(const int16_t* pcm, int64_t frames, int sr) {
    constexpr float kPi = 3.14159265358979f;
    BpmResult res;
    const int hop = std::max(1, sr / 200);            // 200 cuadros de envolvente por segundo
    const double fps = (double)sr / hop;
    const int64_t n = frames / hop;
    if (n < (int64_t)(fps * 8.0)) return res;         // menos de 8 s: no se analiza

    // 1) Envolventes: graves (LP 200 Hz) y transitorios (|x[i]-x[i-1]|)
    std::vector<float> e1((size_t)n), e2((size_t)n);
    const float a = 1.f - std::exp(-2.f * kPi * 200.f / (float)sr);
    float lp = 0.f, prevx = 0.f;
    for (int64_t b = 0; b < n; ++b) {
        double s1 = 0.0, s2 = 0.0;
        const int64_t base = b * hop;
        for (int i = 0; i < hop; ++i) {
            const int64_t idx = base + i;
            const float x = 0.5f * ((float)pcm[idx * 2] + (float)pcm[idx * 2 + 1]) * (1.f / 32768.f);
            lp += a * (x - lp);
            s1 += std::fabs(lp);
            s2 += std::fabs(x - prevx);
            prevx = x;
        }
        e1[b] = (float)(s1 / hop);
        e2[b] = (float)(s2 / hop);
    }

    // 2) Flujo de onsets: log-compresion + diferencia positiva
    auto flux = [n](std::vector<float>& e) {
        double m = 0.0;
        for (float v : e) m += v;
        m /= (double)n;
        if (m < 1e-9) { std::fill(e.begin(), e.end(), 0.f); return; }
        for (auto& v : e) v = std::log1p(10.f * v / (float)m);
        float prev = e[0];
        for (int64_t i = 0; i < n; ++i) {
            const float c = e[i];
            e[i] = std::max(0.f, c - prev);
            prev = c;
        }
    };
    flux(e1);
    flux(e2);
    // Los graves (bombo) pesan 2.5x: el bombo ancla el beat y evita confundirlo con golpes
    // sincopados de caja/hi-hat (p.ej. dembow o cumbia, donde salia 4/3 del tempo real).
    std::vector<float> o((size_t)n, 0.f);
    for (int64_t i = 0; i < n; ++i) o[i] = 2.5f * e1[i] + e2[i];
    {
        std::vector<float> os(o);
        for (int64_t i = 1; i + 1 < n; ++i) os[i] = 0.25f * o[i - 1] + 0.5f * o[i] + 0.25f * o[i + 1];
        o.swap(os);
    }
    double mean = 0.0;
    for (float v : o) mean += v;
    mean /= (double)n;
    std::vector<float> oc((size_t)n);
    for (int64_t i = 0; i < n; ++i) oc[i] = o[i] - (float)mean;

    // 3) Autocorrelacion entre 70 y 180 BPM, con un leve sesgo hacia ~100 BPM
    const int lagMin = std::max(2, (int)(fps * 60.0 / 180.0));
    const int lagMax = (int)(fps * 60.0 / 70.0) + 1;
    if ((int64_t)lagMax + 2 >= n) return res;
    std::vector<double> ac((size_t)lagMax + 2, 0.0);
    for (int lag = lagMin - 1; lag <= lagMax + 1; ++lag) {
        double s = 0.0;
        for (int64_t i = 0; i + lag < n; ++i) s += (double)oc[i] * (double)oc[i + lag];
        ac[lag] = s / (double)(n - lag);
    }
    int bestLag = -1;
    double bestVal = 0.0;
    for (int lag = lagMin; lag <= lagMax; ++lag) {
        const double bpm = 60.0 * fps / lag;
        const double z = std::log2(bpm / 100.0) / 0.7;
        const double v = ac[lag] * std::exp(-0.5 * z * z);
        if (v > bestVal) { bestVal = v; bestLag = lag; }
    }
    if (bestLag < 0) return res;
    {   // corregir el error "cada 3 beats": si a 2/3 del lag la correlacion es casi igual, ese es el tempo real
        const int alt = (int)std::lround(bestLag * 2.0 / 3.0);
        if (alt >= lagMin && alt <= lagMax && ac[alt] >= 0.9 * ac[bestLag] && ac[alt] > 0.0) bestLag = alt;
    }
    double lagF = bestLag;
    {
        const double y0 = ac[bestLag - 1], y1 = ac[bestLag], y2 = ac[bestLag + 1];
        const double den = y0 - 2.0 * y1 + y2;
        if (std::fabs(den) > 1e-12) lagF += 0.5 * (y0 - y2) / den;
    }
    const double coarse = 60.0 * fps / lagF;

    // 4) Refinamiento fino de BPM (paso 0.02) y fase del primer beat
    double bestScore = -1e30, bestBpm = coarse, bestPh = 0.0;
    for (double bpm = coarse - 1.5; bpm <= coarse + 1.5001; bpm += 0.02) {
        const double P = fps * 60.0 / bpm;
        const int phases = std::max(1, (int)P);
        for (int ph = 0; ph < phases; ++ph) {
            double s = 0.0;
            int cnt = 0;
            for (double t = ph; t < (double)(n - 1); t += P) {
                const int64_t i = (int64_t)t;
                const double f = t - (double)i;
                s += (double)o[i] * (1.0 - f) + (double)o[i + 1] * f;
                ++cnt;
            }
            if (cnt < 4) continue;
            s /= (double)cnt;
            if (s > bestScore) { bestScore = s; bestBpm = bpm; bestPh = ph; }
        }
    }
    res.bpm = (float)bestBpm;
    res.firstBeat = (bestPh + 0.5) / fps;
    return res;
}

}  // namespace dj
