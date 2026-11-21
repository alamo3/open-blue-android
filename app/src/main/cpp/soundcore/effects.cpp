// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
#include "effects.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>

namespace sc {

namespace {
constexpr float LAUNCH_PEDAL_RATE = 2.f, LAUNCH_MIN_PEDAL = 0.65f, LAUNCH_MAX_KMH = 50.f, LAUNCH_COOLDOWN_SEC = 4.f;
constexpr float LAUNCH_BUILD_PEDAL = 0.75f, LAUNCH_BANG_PEDAL = 0.9f, EV_LAUNCH_COOLDOWN_SEC = 5.f;
constexpr float SHIFT_DUCK = 0.75f, SHIFT_DUCK_SEC = 0.18f, REVERSE_DUCK = 0.2f;
constexpr float SHIFT_FROM_PEDAL = 0.55f, SHIFT_FULL_PEDAL = 0.85f, SHIFT_GAIN = 3.f, LAUNCH_GAIN = 1.8f;
constexpr int ASSET_RATE = 48000;
// crossed on purpose: the "down" take sounds right going up, and the other way round
const char* SHIFT_UP = "shift_cin_down";
const char* SHIFT_DOWN = "shift_cin_up";
}  // namespace

bool readWavStereo(const std::string& path, std::vector<float>& l, std::vector<float>& r, int* rate) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (b.size() < 44 || std::memcmp(b.data(), "RIFF", 4) != 0 || std::memcmp(b.data() + 8, "WAVE", 4) != 0) return false;
    auto u32 = [&](size_t p) { return (uint32_t)b[p] | (uint32_t)b[p + 1] << 8 | (uint32_t)b[p + 2] << 16 | (uint32_t)b[p + 3] << 24; };
    auto i16 = [&](size_t p) { return (int16_t)((uint16_t)b[p] | (uint16_t)b[p + 1] << 8); };
    size_t p = 12;
    int channels = 1, bits = 16;
    while (p + 8 <= b.size()) {
        uint32_t len = u32(p + 4);
        if (std::memcmp(b.data() + p, "fmt ", 4) == 0) {
            channels = (int)(uint16_t)i16(p + 10);
            if (rate) *rate = (int)u32(p + 12);
            bits = (int)(uint16_t)i16(p + 22);
        } else if (std::memcmp(b.data() + p, "data", 4) == 0) {
            if (bits != 16 || channels < 1) return false;
            size_t n = std::min<size_t>(len, b.size() - p - 8) / (2 * channels);
            l.resize(n);
            r.resize(n);
            size_t q = p + 8;
            for (size_t i = 0; i < n; ++i) {
                l[i] = i16(q) / 32768.f;
                r[i] = channels >= 2 ? i16(q + 2) / 32768.f : l[i];
                q += 2 * channels;
            }
            return true;
        }
        p += 8 + len + (len & 1);
    }
    return false;
}

Effects::Effects(int sr, const std::string& dir) : sr_(sr) {
    if (dir.empty()) return;
    for (const char* n : {"launch_build", "launch_bang", "boom", SHIFT_UP, SHIFT_DOWN, "reverse", "chime"}) {
        Clip c;
        if (readWavStereo(dir + "/" + n + ".wav", c.l, c.r)) clips_[n] = std::move(c);
    }
    // the two shift takes were made at different levels: bring both to the louder one's rms
    auto rms = [](const Clip& c) {
        double e = 0;
        for (size_t i = 0; i < c.l.size(); ++i) e += (c.l[i] * c.l[i] + c.r[i] * c.r[i]) / 2.0;
        return (float)std::sqrt(e / std::max<size_t>(1, c.l.size()));
    };
    auto up = clips_.find(SHIFT_UP), down = clips_.find(SHIFT_DOWN);
    if (up != clips_.end() && down != clips_.end()) {
        float a = rms(up->second), b = rms(down->second), target = std::max(a, b);
        for (auto [c, lv] : {std::pair<Clip*, float>{&up->second, a}, {&down->second, b}}) {
            if (lv <= 1e-6f) continue;
            float g = target / lv;
            for (float& x : c->l) x *= g;
            for (float& x : c->r) x *= g;
        }
    }
}

void Effects::fire(const std::string& name, float level) {
    auto it = clips_.find(name);
    if (it != clips_.end()) { it->second.pos = 0; it->second.level = level; }
}

void Effects::step(float dt, float speedKmh, float pedal, bool reversing, Launch launch) {
    reverse_ = reversing;
    if (prevPedal_ >= 0.f && dt > 0.f) pedalRate_ += ((pedal - prevPedal_) / dt - pedalRate_) * std::min(1.f, dt / 0.08f);
    prevPedal_ = pedal;
    cooldown_ = std::max(0.f, cooldown_ - dt);
    bool wasBelow = lastPedal_ < LAUNCH_BUILD_PEDAL;
    lastPedal_ = pedal;
    if (launch == Launch::EV) {
        // hard acceleration from low speed: the build, played to the end...
        if (wasBelow && pedal >= LAUNCH_BUILD_PEDAL && speedKmh < LAUNCH_MAX_KMH && cooldown_ == 0.f && !reverse_) {
            cooldown_ = EV_LAUNCH_COOLDOWN_SEC;
            fire("launch_build");
            auto it = clips_.find("launch_build");
            buildLeft_ = it != clips_.end() ? it->second.l.size() / (float)ASSET_RATE : 0.f;
        }
    } else if (launch == Launch::BOOM && pedalRate_ > LAUNCH_PEDAL_RATE && pedal > LAUNCH_MIN_PEDAL &&
               speedKmh < LAUNCH_MAX_KMH && cooldown_ == 0.f && !reverse_) {
        cooldown_ = LAUNCH_COOLDOWN_SEC;
        fire("boom");
    }
    // ...then the bang, only if the pedal is (nearly) floored when the build ends
    if (buildLeft_ > 0.f) {
        buildLeft_ -= dt;
        if (buildLeft_ <= 0.f && pedal >= LAUNCH_BANG_PEDAL && !reverse_) fire("launch_bang");
    }
    duckT_ = std::max(0.f, duckT_ - dt);
    duck_ = (reverse_ ? REVERSE_DUCK : 1.f) * (duckT_ > 0.f ? SHIFT_DUCK : 1.f);
}

void Effects::shift(bool up, float effort, bool duck) {
    float x = std::clamp((effort - SHIFT_FROM_PEDAL) / (SHIFT_FULL_PEDAL - SHIFT_FROM_PEDAL), 0.f, 1.f);
    float level = x * x * (3 - 2 * x);
    if (level <= 0.f) return;
    fire(up ? SHIFT_UP : SHIFT_DOWN, level);
    if (up && duck) duckT_ = SHIFT_DUCK_SEC;
}

float Effects::sample(const std::vector<float>& pcm, double pos) {
    size_t i = (size_t)pos;
    float f = (float)(pos - i);
    float a = pcm[i % pcm.size()], b = pcm[(i + 1) % pcm.size()];
    return a + (b - a) * f;
}

void Effects::mix(float* l, float* r, int frames, float volume) {
    double step = ASSET_RATE / (double)sr_;
    float revTarget = reverse_ ? 1.f : 0.f;
    float k = 1.f / (0.15f * sr_);
    for (int n = 0; n < frames; ++n) {
        duckNow_ += (duck_ - duckNow_) * std::min(1.f, k * 2.5f);
        reverseLevel_ += (revTarget - reverseLevel_) * k * 3.f;
        float x = l[n] * duckNow_, xr = r[n] * duckNow_;
        for (auto& [name, c] : clips_) {
            if (name == "reverse") {
                if (reverseLevel_ < 1e-4f) continue;
                if (c.pos < 0) c.pos = 0;
                float g = reverseLevel_ * volume;
                x += sample(c.l, c.pos) * g; xr += sample(c.r, c.pos) * g;
                c.pos = std::fmod(c.pos + step, (double)c.l.size());
            } else if (c.pos >= 0) {
                float g = volume * ((name == SHIFT_UP || name == SHIFT_DOWN) ? SHIFT_GAIN
                                    : (name == "launch_build" || name == "launch_bang" || name == "boom") ? LAUNCH_GAIN : 1.f);
                x += sample(c.l, c.pos) * g * c.level; xr += sample(c.r, c.pos) * g * c.level;
                c.pos += step;
                if (c.pos >= c.l.size() - 1) c.pos = -1;
            }
        }
        l[n] = x; r[n] = xr;
    }
    if (reverseLevel_ < 1e-4f) { auto it = clips_.find("reverse"); if (it != clips_.end()) it->second.pos = -1; }
}

}  // namespace sc
