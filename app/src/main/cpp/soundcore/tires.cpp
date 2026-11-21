// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
#include "tires.h"

#include <algorithm>
#include <cmath>

namespace sc {

namespace {
constexpr float G = 9.81f;
constexpr float SCRUB_LEVEL = 1.f, SQUEAL_LEVEL = 12.7f;    // pink scrub rms ~0.3; a Q-40 band of white noise is very quiet
constexpr float SQUEAL_HZ = 950.f, SQUEAL_RISE_HZ = 450.f;  // its pitch, rising as it nears the limit
constexpr float OVERTONES[3] = {1.f, 2.03f, 2.98f}, OVERTONE_GAIN[3] = {1.f, 0.45f, 0.22f};
constexpr float INSIDE = 0.55f;                             // the inside tires, relative to the outside ones
constexpr float ATTACK_SEC = 0.12f, RELEASE_SEC = 0.35f;
}  // namespace

Tires::Tires(int sr) : sr_(sr), pitch_(1.5f, sr, 0x3301), warble_(7.f, sr, 0x3302), chatter_(19.f, sr, 0x3303) {
    scrubL_.bandPass(700.f, 0.6f, sr); scrubR_.bandPass(760.f, 0.6f, sr);
    scrubHpL_.highPass(250.f, 0.7f, sr); scrubHpR_.highPass(250.f, 0.7f, sr);
}

void Tires::render(float* l, float* r, int frames, float lateral, float speedMs, float volume) {
    const float dt = (float)frames / sr_;
    const float g = std::fabs(lateral) / G;
    const float atSpeed = smoothstep((speedMs * 3.6f - SPEED_FROM_KMH) / (SPEED_FULL_KMH - SPEED_FROM_KMH));
    const float wantScrub = smoothstep((g - SCRUB_FROM) / SCRUB_SPAN) * atSpeed;
    const float wantSqueal = smoothstep((g - SQUEAL_FROM) / SQUEAL_SPAN) * atSpeed;
    auto ease = [&](float& v, float want) { float sec = want > v ? ATTACK_SEC : RELEASE_SEC; v += (want - v) * (1.f - std::exp(-dt / sec)); };
    const float scrub0 = scrub_, squeal0 = squeal_;
    ease(scrub_, wantScrub);
    ease(squeal_, wantSqueal);
    if (lateral != 0.f) side_ += ((lateral > 0 ? 1.f : -1.f) - side_) * std::min(1.f, dt / 0.2f);
    if (scrub0 < 1e-4f && scrub_ < 1e-4f && squeal0 < 1e-4f && squeal_ < 1e-4f) return;   // straight, or slow: silent and free

    // the squeal's pitch: rises toward the limit, drifts slowly, warbles a little
    const float f = (SQUEAL_HZ + SQUEAL_RISE_HZ * squeal_) * (1.f + 0.05f * pitch_.next(frames) + 0.025f * warble_.next(frames));
    for (int k = 0; k < 3; ++k) tone_[k].bandPass(f * OVERTONES[k], 40.f, sr_);
    // outside louder than inside (side_ > 0: cornering left, the outside is the right)
    const float gl = side_ > 0 ? 1.f - (1.f - INSIDE) * side_ : 1.f;
    const float gr = side_ < 0 ? 1.f + (1.f - INSIDE) * side_ : 1.f;
    for (int n = 0; n < frames; ++n) {
        const float t = (float)n / frames;
        const float sc = (scrub0 + (scrub_ - scrub0) * t) * SCRUB_LEVEL * volume;
        const float sq = (squeal0 + (squeal_ - squeal0) * t) * SQUEAL_LEVEL * volume;
        // stick-slip chatter: the squeal stutters, more so as it starts
        float chat = chatter_.next();
        float gate = 0.65f + 0.35f * chat;
        float w = noise_.white(), tone = 0;
        for (int k = 0; k < 3; ++k) tone += tone_[k].process(w) * OVERTONE_GAIN[k];
        tone *= sq * gate;
        float sL = scrubHpL_.process(scrubL_.process(pinkL_.next())) * sc;
        float sR = scrubHpR_.process(scrubR_.process(pinkR_.next())) * sc;
        l[n] += (tone + sL) * gl;
        r[n] += (tone + sR) * gr;
    }
}

}  // namespace sc
