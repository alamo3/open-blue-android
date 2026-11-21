// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// Hypercar: an electric performance voice in layers (port of Open Blue's HypercarVoice.kt). The
// aggressive core follows the gearbox's revs (so it shifts); underneath, the car's real motor
// sings its own orders and never does. FM snarl under load, sub, spool whistle, air, regen pad.
#include <algorithm>
#include <cmath>

#include "dsp.h"
#include "voices.h"

namespace sc {

namespace {
const float DETUNE[3] = {std::pow(2.f, -6.f / 1200.f), 1.f, std::pow(2.f, 7.f / 1200.f)};
constexpr float PAN[3] = {0.75f, 0.5f, 0.25f};
constexpr float TRIM = 2.1f;
}  // namespace

class HypercarVoice : public LayeredVoice {
public:
    static constexpr float IDLE = 1000.f, REDLINE = 9000.f;

    explicit HypercarVoice(int sr)
        : LayeredVoice(sr), noise_(21), pinkL_(22), pinkR_(23), coreLevel_(0.015f, sr), edgeLevel_(0.03f, sr),
          subLevel_(0.05f, sr), motorLevel_(0.05f, sr), whistleLevel_(0.08f, sr), padLevel_(0.3f, sr) {
        for (int c = 0; c < 2; ++c) hp_[c].highPass(30.f, 0.707f, sr);
    }

    void render(float* out, float* outR, int frames) override {
        if (!has_) { std::fill(out, out + frames, 0.f); std::fill(outR, outR + frames, 0.f); return; }
        const EngineState& s = st_;
        float rpm = std::max(s.rpm, IDLE);
        float rpmN = clamp01((rpm - IDLE) / (REDLINE - IDLE));
        float load = clamp01(s.load), overrun = clamp01(s.overrun);
        float kmh = s.speedKmh, speedN = speedNorm(kmh);
        float fTarget = rpm / 60.f * 2.f;
        float f0 = lastF_ <= 0.f ? fTarget : lastF_;                 // glide the pitch across the block
        lastF_ = fTarget;

        float cutoff = lerp(650.f, 4200.f, 0.25f * rpmN + 0.75f * load * (0.4f + 0.6f * rpmN));
        for (int c = 0; c < 2; ++c) lp_[c].lowPass(cutoff, 2.2f, sr_);
        float coreT = (0.38f + 0.62f * std::pow(load, 1.2f)) * (0.6f + 0.4f * rpmN) * (1.f - 0.85f * overrun) *
                      (1.f - 0.7f * clamp01(s.shifting));
        float index = 4.f * std::pow(load, 1.5f) * (0.3f + 0.7f * rpmN);
        edgeHp_.highPass(300.f, 0.707f, sr_);
        float edgeT = load * load * (0.2f + 0.8f * rpmN);
        float subT = (0.25f + 0.75f * load) * (1.f - 0.6f * overrun);
        float motorHz = (motorRpm_ > 0.f ? motorRpm_ : kmh * 77.f) / 60.f;
        float torque = clamp01(load + 0.8f * overrun * speedN);
        float motorT = clamp01(kmh / 8.f) * (0.25f + 0.75f * torque);
        whistleBp_.bandPass(fTarget * 11.f, 6.f, sr_);
        float whistleT = rpmN * rpmN * load;
        for (int c = 0; c < 2; ++c) airBp_[c].bandPass(lerp(500.f, 1800.f, speedN), 0.6f, sr_);
        float air = std::pow(speedN, 1.6f) * 0.12f;
        float padF = lerp(110.f, 220.f, speedN);
        float padT = overrun * clamp01(kmh / 20.f);

        float vol = volume * TRIM;
        for (int i = 0; i < frames; ++i) {
            float f = f0 + (fTarget - f0) * i / frames;
            // core: three detuned saw + pulse voices across the stereo field
            float cl = 0.f, cr = 0.f;
            for (int v = 0; v < 3; ++v) {
                double dt = (double)(f * DETUNE[v] / sr_);
                sawPh_[v] = std::fmod(sawPh_[v] + dt, 1.0);
                double dt2 = dt * 2;
                pulsePh_[v] = std::fmod(pulsePh_[v] + dt2, 1.0);
                float pulse = 0.5f * (saw(pulsePh_[v], dt2) - saw(std::fmod(pulsePh_[v] + 0.35, 1.0), dt2));
                float tone = 0.6f * saw(sawPh_[v], dt) + 0.8f * pulse;
                cl += tone * PAN[v]; cr += tone * (1.f - PAN[v]);
            }
            float cg = coreLevel_.next(coreT) * 0.45f;
            float l = lp_[0].process(cl) * cg;
            float r = lp_[1].process(cr) * cg;
            // edge: FM under load
            fmMod_ += f * 3.41 / sr_;
            fmCar_ += f * 2.0 / sr_;
            float edge = edgeHp_.process(sin1(fmCar_ + index * sin1(fmMod_) / (2 * M_PI))) * edgeLevel_.next(edgeT) * 0.22f;
            l += edge * 0.55f; r += edge * 0.45f;
            // sub
            subPh_ += f / 2.0 / sr_;
            float sub = sin1(subPh_) * subLevel_.next(subT) * 0.35f * 0.5f;
            l += sub; r += sub;
            // the real motor
            motorPh_[0] += motorHz * 8.0 / sr_;
            motorPh_[1] += motorHz * 12.0 / sr_;
            float whine = (sin1(motorPh_[0]) + 0.45f * sin1(motorPh_[1])) * motorLevel_.next(motorT) * 0.06f * 0.5f;
            l += whine; r += whine;
            // spool whistle
            whistlePh_ += f * 11.0 / sr_;
            float wh = (sin1(whistlePh_) + 0.4f * whistleBp_.process(noise_.gauss())) * whistleLevel_.next(whistleT) * 0.05f;
            l += wh * 0.4f; r += wh * 0.6f;
            // air
            l += airBp_[0].process(pinkL_.next()) * air;
            r += airBp_[1].process(pinkR_.next()) * air;
            // regen pad
            padPh_[0] += padF / sr_; padPh_[1] += padF * 1.5 / sr_; padPh_[2] += padF * 2.0 / sr_;
            float pad = (sin1(padPh_[0]) + sin1(padPh_[1]) + sin1(padPh_[2])) / 3.f * padLevel_.next(padT) * 0.06f * 0.5f;
            l += pad; r += pad;

            out[i] = softClip(hp_[0].process(l), 1.3f) * vol;
            outR[i] = softClip(hp_[1].process(r), 1.3f) * vol;
        }
    }

private:
    Noise noise_;
    Pink pinkL_, pinkR_;
    double sawPh_[3] = {0, 0, 0}, pulsePh_[3] = {0, 0, 0};
    Biq lp_[2], edgeHp_, whistleBp_, airBp_[2], hp_[2];
    double fmMod_ = 0, fmCar_ = 0, subPh_ = 0, motorPh_[2] = {0, 0}, whistlePh_ = 0, padPh_[3] = {0, 0, 0};
    Smooth coreLevel_, edgeLevel_, subLevel_, motorLevel_, whistleLevel_, padLevel_;
    float lastF_ = 0;
};

std::unique_ptr<LayeredVoice> makeHypercar(int sr) { return std::make_unique<HypercarVoice>(sr); }

}  // namespace sc
