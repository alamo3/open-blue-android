// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// Turbofan: a fighter-style jet heard from the cockpit (port of Open Blue's JetVoice.kt). With
// gears the gearbox's revs set the target spool (rounded by the turbine's lag); without, the
// pedal does. Fan blade tone turning to buzzsaw, core whine, rumble, roar, an afterburner that
// lights with a thump and stays lit through the shifts, air.
#include <algorithm>
#include <cmath>
#include <vector>

#include "dsp.h"
#include "voices.h"

namespace sc {

namespace {
constexpr float IDLE_SPOOL = 0.22f, FAN_MAX_RPM = 10500.f, CORE_RATIO = 1.6f;
constexpr int FAN_BLADES = 18;
constexpr float SPOOL_UP_SEC = 1.6f, SPOOL_DOWN_SEC = 1.0f, GEARED_UP_SEC = 0.35f, GEARED_DOWN_SEC = 0.25f;
constexpr float BURNER_PEDAL = 0.9f, BURNER_SPOOL = 0.55f, TRIM = 6.7f;
}  // namespace

class JetVoice : public LayeredVoice {
public:
    static constexpr float IDLE = 1000.f, REDLINE = 7000.f;

    explicit JetVoice(int sr)
        : LayeredVoice(sr), noise_(31), pinks_{Pink(32), Pink(33), Pink(34), Pink(35), Pink(36), Pink(37)},
          litHold_(0.6f, sr), burner_(0.25f, sr) {
        for (int h = 0; h < FAN_BLADES; ++h) { buzzPh_[h] = 0; buzzAmp_[h] = h >= 6 ? Noise(40 + h).uniform(0.3f, 1.f) : 0.f; }
        fanLp_.lowPass(6000.f, 0.707f, sr);
        rumbleHp_.highPass(40.f, 0.707f, sr);
        fireBp_.bandPass(160.f, 0.6f, sr);
        crackleLp_.lowPass(25.f, 0.7f, sr);
        for (int c = 0; c < 2; ++c) hp_[c].highPass(28.f, 0.707f, sr);
        thump_.resize((size_t)(0.5f * sr));
        Biq lp; lp.lowPass(400.f, 0.707f, sr);
        Noise n(77);
        for (size_t i = 0; i < thump_.size(); ++i) {
            float x = (float)i / sr;
            thump_[i] = (sin1(48.0 * x) * 0.9f + lp.process(n.gauss())) * std::exp(-x / 0.12f) * 0.6f;
        }
    }

    void render(float* out, float* outR, int frames) override {
        if (!has_) { std::fill(out, out + frames, 0.f); std::fill(outR, outR + frames, 0.f); return; }
        const EngineState& s = st_;
        float dt = (float)frames / sr_;
        float kmh = s.speedKmh, speedN = speedNorm(kmh);
        float p = clamp01(pedal_);
        bool geared = s.gear >= 1;
        float target = geared ? IDLE_SPOOL + (1 - IDLE_SPOOL) * clamp01((s.rpm - IDLE) / (REDLINE - IDLE))
                              : std::max(IDLE_SPOOL + (1 - IDLE_SPOOL) * std::pow(p, 1.3f), IDLE_SPOOL + 0.35f * clamp01(kmh / 150.f));
        float tau = target > spool_ ? (geared ? GEARED_UP_SEC : SPOOL_UP_SEC) : (geared ? GEARED_DOWN_SEC : SPOOL_DOWN_SEC);
        float spool0 = spool_;
        spool_ += (target - spool_) * std::min(1.f, dt / tau * (0.6f + 0.8f * spool_));   // spools up faster as it winds up
        float spN = clamp01((spool_ - IDLE_SPOOL) / (1 - IDLE_SPOOL));

        float buzzAmt = clamp01((spN - 0.55f) / 0.35f);
        rumbleLp_.lowPass(lerp(90.f, 220.f, spN), 0.9f, sr_);
        float rumble = 0.10f + 0.25f * spN * spN;
        for (int c = 0; c < 2; ++c) roarBp_[c].bandPass(lerp(250.f, 900.f, spN), 0.5f, sr_);
        float roar = 0.08f + 0.37f * spN * spN * spN;
        bool litNow = p > BURNER_PEDAL && spool_ > BURNER_SPOOL;
        bool lit = geared ? litHold_.block(litNow ? 1.f : 0.f, frames) > 0.5f : litNow;
        if (lit && !wasLit_) thumpPos_ = 0;
        wasLit_ = lit;
        for (int c = 0; c < 2; ++c) airBp_[c].bandPass(lerp(600.f, 2200.f, speedN), 0.5f, sr_);
        float air = std::pow(speedN, 1.7f) * 0.10f;

        float vol = volume * TRIM;
        for (int i = 0; i < frames; ++i) {
            float sp = spool0 + (spool_ - spool0) * i / frames;
            float spn = clamp01((sp - IDLE_SPOOL) / (1 - IDLE_SPOOL));
            float shaft = sp * FAN_MAX_RPM / 60.f;
            // fan and buzzsaw
            fanPh_ += (double)shaft * FAN_BLADES / sr_;
            float fan = sin1(fanPh_) + 0.3f * sin1(fanPh_ * 2);
            fan *= 0.09f * (0.5f + 0.5f * spn);
            if (buzzAmt > 0.f) {
                float b = 0.f;
                for (int h = 6; h < FAN_BLADES; ++h) { buzzPh_[h] += (double)shaft * h / sr_; b += buzzAmp_[h] * sin1(buzzPh_[h] + h * 0.37); }
                fan += b * 0.012f * buzzAmt;
            } else {
                for (int h = 6; h < FAN_BLADES; ++h) buzzPh_[h] += (double)shaft * h / sr_;
            }
            fan = fanLp_.process(fan);
            float l = fan * 0.55f, r = fan * 0.45f;
            // core whine
            corePh_ += (double)shaft * CORE_RATIO * 29.f / sr_;
            float core = sin1(corePh_) * 0.02f * std::pow(spn, 1.5f) * 0.5f;
            l += core; r += core;
            // rumble and roar
            float rb = rumbleHp_.process(rumbleLp_.process(pinks_[0].next())) * rumble * 0.5f;
            l += rb + roarBp_[0].process(pinks_[1].next()) * roar;
            r += rb + roarBp_[1].process(pinks_[2].next()) * roar;
            // afterburner: ragged pulsing, its spikes capped
            float bn = burner_.next(lit ? 1.f : 0.f);
            if (bn > 1e-3f) {
                float crackle = std::min(1.5f, std::fabs(crackleLp_.process(noise_.gauss())) * 6.f);
                float fire = fireBp_.process(pinks_[3].next()) * (0.6f + crackle) * bn * 0.25f;
                l += fire; r += fire;
            }
            if (thumpPos_ >= 0) {
                l += thump_[thumpPos_]; r += thump_[thumpPos_];
                if (++thumpPos_ >= (int)thump_.size()) thumpPos_ = -1;
            }
            // air
            l += airBp_[0].process(pinks_[4].next()) * air;
            r += airBp_[1].process(pinks_[5].next()) * air;

            out[i] = softClip(hp_[0].process(l), 1.3f) * vol;
            outR[i] = softClip(hp_[1].process(r), 1.3f) * vol;
        }
    }

private:
    Noise noise_;
    Pink pinks_[6];
    float spool_ = IDLE_SPOOL;
    double fanPh_ = 0, corePh_ = 0, buzzPh_[FAN_BLADES];
    float buzzAmp_[FAN_BLADES];
    Biq fanLp_, rumbleLp_, rumbleHp_, roarBp_[2], fireBp_, crackleLp_, airBp_[2], hp_[2];
    Smooth litHold_, burner_;
    bool wasLit_ = false;
    std::vector<float> thump_;
    int thumpPos_ = -1;
};

std::unique_ptr<LayeredVoice> makeJet(int sr) { return std::make_unique<JetVoice>(sr); }

}  // namespace sc
