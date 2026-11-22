// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// Real-time DSP kit for the sound core: everything runs sample by sample, with filter
// coefficients updated once per block. A port of Open Blue's LayerDsp.kt (same maths, same
// noise generators), so the native core and the app sound the same.
#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#if defined(__SSE__) || defined(_M_X64)
#include <xmmintrin.h>
#endif

namespace sc {

// Flushes denormals (tiny numbers left in filters and resonators as a sound dies away) to zero
// while it's in scope. ARM processors -- a Raspberry Pi, a phone -- handle them very slowly by
// default: enough to make a Pi miss its audio deadlines, which comes out as buzzing and crackle.
class NoDenormals {
public:
    NoDenormals() {
#if defined(__aarch64__)
        asm volatile("mrs %0, fpcr" : "=r"(saved_));
        asm volatile("msr fpcr, %0" : : "r"(saved_ | (1ull << 24)));         // FZ
#elif defined(__arm__) && defined(__ARM_FP)
        uint32_t v;
        asm volatile("vmrs %0, fpscr" : "=r"(v));
        saved_ = v;
        asm volatile("vmsr fpscr, %0" : : "r"(v | (1u << 24)));              // FZ
#elif defined(__SSE__) || defined(_M_X64)
        saved_ = _mm_getcsr();
        _mm_setcsr((unsigned)saved_ | 0x8040);                                // FTZ | DAZ
#endif
    }
    ~NoDenormals() {
#if defined(__aarch64__)
        asm volatile("msr fpcr, %0" : : "r"(saved_));
#elif defined(__arm__) && defined(__ARM_FP)
        asm volatile("vmsr fpscr, %0" : : "r"((uint32_t)saved_));
#elif defined(__SSE__) || defined(_M_X64)
        _mm_setcsr((unsigned)saved_);
#endif
    }
    NoDenormals(const NoDenormals&) = delete;
    NoDenormals& operator=(const NoDenormals&) = delete;
private:
    uint64_t saved_ = 0;
};

constexpr float kPi = 3.14159265358979f;

inline float clamp01(float x) { return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); }
inline float clampf(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
inline float lerp(float a, float b, float t) { return a + (b - a) * clamp01(t); }
inline float smoothstep(float t) { float x = clamp01(t); return x * x * (3 - 2 * x); }

// sin(2*pi*phase) for a phase in cycles, by table
class SineTable {
public:
    static constexpr int N = 4096;
    SineTable() { for (int i = 0; i <= N; ++i) t_[i] = (float)std::sin(2.0 * M_PI * i / N); }
    float operator()(double phase) const {
        double p = phase - std::floor(phase);
        double x = p * N;
        int i = (int)x;
        float f = (float)(x - i);
        return t_[i] + (t_[i + 1] - t_[i]) * f;
    }
private:
    std::array<float, N + 1> t_;
};
inline const SineTable& sineTable() { static const SineTable t; return t; }
inline float sin1(double phase) { return sineTable()(phase); }

// PolyBLEP residual for a unit step at phase 0 (t in [0, 1), dt = f / sr)
inline double blep(double t, double dt) {
    if (t < dt) { double u = t / dt; return u + u - u * u - 1; }
    if (t > 1 - dt) { double u = (t - 1) / dt; return u * u + u + u + 1; }
    return 0.0;
}
// band-limited sawtooth at phase t (cycles, in [0, 1)) and step dt
inline float saw(double t, double dt) { return (float)(2 * t - 1 - blep(t, dt)); }

inline float softClip(float x, float drive) { return std::tanh(x * drive) / drive; }

// one-pole smoother: follows its target with a time constant
class Smooth {
public:
    Smooth(float sec, int sr, float v = 0.f) : k_(1.f - std::exp(-1.f / (sec * sr))), value(v) {}
    float next(float target) { value += (target - value) * k_; return value; }
    float block(float target, int frames) { value += (target - value) * (1.f - std::pow(1.f - k_, (float)frames)); return value; }
private:
    float k_;
public:
    float value;
};

// fast noise (xorshift, as the Kotlin: white uniform in -1..1, gauss with unit spread)
class Noise {
public:
    explicit Noise(int32_t seed) : s_(seed == 0 ? 0x2545F491 : seed) {}
    float white() {
        uint32_t s = (uint32_t)s_;
        s ^= s << 13; s ^= s >> 17; s ^= s << 5;
        s_ = (int32_t)s;
        return (float)s_ * (1.f / 2147483647.f);
    }
    float gauss() { return white() + white() + white(); }
    float uniform(float a, float b) { return a + (b - a) * (white() * 0.5f + 0.5f); }
    bool chance(float p) { return white() * 0.5f + 0.5f < p; }
private:
    int32_t s_;
};

// pink noise (Paul Kellet's economy filter), matched to the prototypes' level (rms ~0.3)
class Pink {
public:
    explicit Pink(int32_t seed) : n_(seed) {}
    float next() {
        float w = n_.white();
        b0_ = 0.99765f * b0_ + w * 0.0990460f;
        b1_ = 0.96300f * b1_ + w * 0.2965164f;
        b2_ = 0.57000f * b2_ + w * 1.0526913f;
        return (b0_ + b1_ + b2_ + w * 0.1848f) * 0.18f;
    }
private:
    Noise n_;
    float b0_ = 0, b1_ = 0, b2_ = 0;
};

// RBJ biquad: call a setter (once per block is plenty), then process
class Biq {
public:
    Biq& lowPass(float f, float q, int sr) { double w = W(f, sr), c = std::cos(w), al = std::sin(w) / (2 * q);
        norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al); return *this; }
    Biq& highPass(float f, float q, int sr) { double w = W(f, sr), c = std::cos(w), al = std::sin(w) / (2 * q);
        norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al); return *this; }
    Biq& bandPass(float f, float q, int sr) { double w = W(f, sr), c = std::cos(w), al = std::sin(w) / (2 * q);
        norm(al, 0, -al, 1 + al, -2 * c, 1 - al); return *this; }
    Biq& peak(float f, float q, float db, int sr) { double w = W(f, sr), c = std::cos(w), al = std::sin(w) / (2 * q), a = std::pow(10.0, db / 40.0);
        norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a); return *this; }
    Biq& lowShelf(float f, float q, float db, int sr) {
        double w = W(f, sr), c = std::cos(w), al = std::sin(w) / (2 * q), a = std::pow(10.0, db / 40.0), s = 2 * std::sqrt(a) * al;
        norm(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
             (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s);
        return *this;
    }
    Biq& highShelf(float f, float q, float db, int sr) {
        double w = W(f, sr), c = std::cos(w), al = std::sin(w) / (2 * q), a = std::pow(10.0, db / 40.0), s = 2 * std::sqrt(a) * al;
        norm(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
             (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s);
        return *this;
    }
    float process(float x) {
        float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }
private:
    static double W(float f, int sr) { double ff = f < 10.0 ? 10.0 : (f > sr * 0.45 ? sr * 0.45 : (double)f); return 2 * M_PI * ff / sr; }
    void norm(double c0, double c1, double c2, double d0, double d1, double d2) {
        b0_ = (float)(c0 / d0); b1_ = (float)(c1 / d0); b2_ = (float)(c2 / d0); a1_ = (float)(d1 / d0); a2_ = (float)(d2 / d0);
    }
    float b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0, z1_ = 0, z2_ = 0;
};

// two-pole resonator: struck with an impulse it rings as exp(-t/decay)*sin(2*pi*f*t) at ~unit amplitude
class Resonator {
public:
    void set(float f, float decaySec, int sr) {
        double r = std::exp(-1.0 / (decaySec * sr));
        double ff = f < 10.0 ? 10.0 : (f > sr * 0.45 ? sr * 0.45 : (double)f);
        double w = 2 * M_PI * ff / sr;
        c1_ = (float)(2 * r * std::cos(w)); c2_ = (float)(-r * r); g_ = (float)std::sin(w);
    }
    float process(float x) { float y = x * g_ + c1_ * y1_ + c2_ * y2_; y2_ = y1_; y1_ = y; return y; }
private:
    float c1_ = 0, c2_ = 0, g_ = 0, y1_ = 0, y2_ = 0;
};

// random wander in about -1..1, gliding to a new random target roughly every 1/hz s
class Wander {
public:
    Wander(float hz, int sr, int32_t seed) : hz_(hz), sr_(sr), n_(seed) { to_ = n_.white(); }
    float next(int dtSamples = 1) {
        t_ += hz_ * dtSamples / sr_;
        if (t_ >= 1.f) { t_ -= 1.f; from_ = to_; to_ = n_.white(); }
        float s = 0.5f - 0.5f * std::cos(kPi * t_);
        return from_ + (to_ - from_) * s;
    }
private:
    float hz_; int sr_; Noise n_; float from_ = 0, to_ = 0, t_ = 0;
};

}  // namespace sc
