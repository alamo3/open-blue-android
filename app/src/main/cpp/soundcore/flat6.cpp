// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// Flat-6: a layered boxer six (port of Open Blue's Flat6Voice.kt). Each firing strikes its
// bank's formant resonators (the right bank tuned and weighted apart: the boxer's rasp), through
// pipes and a muffler that opens with load; intake howl, mechanical detail, overrun crackle;
// the gearbox's torque handover and limiter cuts drop the firing out.
#include <algorithm>
#include <cmath>
#include <vector>

#include "dsp.h"
#include "voices.h"

namespace sc {

namespace {
constexpr int FIRING_ORDER[6] = {0, 5, 1, 3, 2, 4};
constexpr float CYL_GAIN[6] = {1.00f, 0.93f, 1.05f, 0.97f, 1.02f, 0.95f};
constexpr float F_HZ[4] = {170.f, 480.f, 1250.f, 3100.f};
constexpr float F_DECAY[4] = {0.0045f, 0.0018f, 0.0009f, 0.00035f};
constexpr float F_LO[4] = {1.00f, 0.45f, 0.10f, 0.02f};
constexpr float F_HI[4] = {1.00f, 0.85f, 0.55f, 0.22f};
constexpr float RIGHT_TUNE = 1.10f, WEAK_LOW = 0.66f, WEAK_HIGH = 0.86f;
constexpr float TRIM = 4.9f, POP_SEC = 0.03f;
constexpr float RASP = 0.06f;            // the turbulent rasp on each pulse, at full load
}  // namespace

class Flat6Voice : public LayeredVoice {
public:
    static constexpr float IDLE = 900.f, REDLINE = 8500.f;

    explicit Flat6Voice(int sr)
        : LayeredVoice(sr), noise_(11), pink_(12), attackK_(1.f - std::exp(-1.f / (0.00025f * sr))),
          level_(0.02f, sr), intakeLevel_(0.05f, sr) {
        for (int c = 0; c < 2; ++c) { shelf_[c].lowShelf(140.f, 0.7f, 1.f, sr); hp_[c].highPass(28.f, 0.707f, sr); }
        int n = (int)(POP_SEC * sr);
        for (int k = 0; k < 6; ++k) {
            pops_[k].resize(n);
            Biq bp; bp.bandPass(1800.f, 0.7f, sr);
            Noise nz(100 + k);
            for (int i = 0; i < n; ++i) {
                float t = (float)i / sr;
                pops_[k][i] = bp.process(nz.gauss()) * std::exp(-t / 0.006f) + sin1(85.0 * t) * std::exp(-t / 0.012f) * 0.8f;
            }
            popPos_[k] = -1;
        }
    }

    void render(float* out, float* outR, int frames) override {
        if (!has_) { std::fill(out, out + frames, 0.f); std::fill(outR, outR + frames, 0.f); return; }
        const EngineState& s = st_;
        float rpm = std::max(s.rpm, IDLE * 0.95f);
        float rpmN = clamp01((rpm - IDLE) / (REDLINE - IDLE));
        float load = clamp01(s.load);
        float overrun = clamp01(s.overrun);
        float fireHz = rpm / 20.f;                               // 6 cylinders, 3 firings a rev
        float bright = clamp01(0.15f + 0.85f * load * (0.5f + 0.5f * rpmN)) * (1.f - 0.6f * overrun);
        float weak = lerp(WEAK_LOW, WEAK_HIGH, rpmN);
        float fireAmp = 1.f - 0.85f * clamp01(s.shifting) * (load > 0.3f ? 1.f : 0.f);
        if (s.cut) fireAmp = 0.12f;
        float formantGain[4];
        for (int f = 0; f < 4; ++f) formantGain[f] = F_LO[f] + (F_HI[f] - F_LO[f]) * bright;
        for (int b = 0; b < 2; ++b) for (int f = 0; f < 4; ++f) res_[b][f].set(F_HZ[f] * (b == 1 ? RIGHT_TUNE : 1.f), F_DECAY[f], sr_);
        grit_.bandPass(2200.f, 0.8f, sr_);
        float mute = lerp(2200.f, 8000.f, load * (0.35f + 0.65f * rpmN)) * (1.f - 0.45f * overrun);
        for (int c = 0; c < 2; ++c) {
            tailpipe_[c].peak(92.f, 2.5f, 3.f, sr_);
            header_[c].peak(270.f, 2.f, 3.f, sr_);
            muffler_[c].lowPass(mute, 0.8f, sr_);
        }
        float lvlTarget = (0.45f + 0.55f * std::pow(load, 1.3f)) * (0.55f + 0.45f * rpmN) * (1.f - overrun) +
                          0.10f * overrun * (0.4f + 0.6f * rpmN);
        intakeBp_.bandPass(lerp(700.f, 2600.f, rpmN), 1.1f, sr_);
        float intakeTarget = std::pow(load, 1.4f) * (0.25f + 0.75f * std::pow(rpmN, 1.3f)) * 0.55f;
        tickBp_.bandPass(5200.f, 2.f, sr_);
        float pulseTau = std::max(0.25f / fireHz, 0.0006f);

        if (liftOffsSeen_ >= 0 && s.liftOffs != liftOffsSeen_) crackleLeft_ = 1.6f;   // crackle after a hard lift
        liftOffsSeen_ = s.liftOffs;
        float popRate = (crackleLeft_ / 1.6f) * overrun * (8.f + 30.f * rpmN);
        crackleLeft_ = std::max(0.f, crackleLeft_ - (float)frames / sr_);

        float vol = volume * TRIM;
        for (int i = 0; i < frames; ++i) {
            // firing: strike the bank's resonators
            firePhase_ += fireHz / sr_;
            float hitL = 0.f, hitR = 0.f;
            if (firePhase_ >= 1.0) {
                firePhase_ -= 1.0;
                firing_++;
                sinceFire_ = 0.f;
                int cyl = FIRING_ORDER[firing_ % 6];
                bool right = cyl % 2 == 1;
                float g = CYL_GAIN[cyl] * (right ? weak : 1.f) * (1.f + 0.10f * noise_.gauss()) * fireAmp;
                (right ? hitR : hitL) = g;
            } else {
                sinceFire_ += 1.f / sr_;
            }
            // each firing is a smoothed impulse (~0.25 ms onset, unit area): the pulse's soft attack
            attack_[0] += (hitL / attackK_ - attack_[0]) * attackK_;
            attack_[1] += (hitR / attackK_ - attack_[1]) * attackK_;
            float bankL = exhaust(0, attack_[0] * attackK_, formantGain);
            float bankR = exhaust(1, attack_[1] * attackK_, formantGain);
            // the rasp: turbulent flow riding on each exhaust pulse, added to it. (It used to
            // multiply the whole exhaust by noise -- heard as a distorted, rattling buzz.)
            float pulseEnv = std::exp(-sinceFire_ / pulseTau);
            float rasp = grit_.process(noise_.gauss()) * pulseEnv * RASP * load;
            float l = bankL * 0.62f + bankR * 0.38f + rasp;
            float r = bankL * 0.38f + bankR * 0.62f + rasp;
            l = muffler_[0].process(header_[0].process(tailpipe_[0].process(l)));
            r = muffler_[1].process(header_[1].process(tailpipe_[1].process(r)));
            float lv = level_.next(lvlTarget);
            l *= lv; r *= lv;
            // intake
            float roar = intakeBp_.process(pink_.next()) * (0.45f + 0.55f * pulseEnv) * intakeLevel_.next(intakeTarget);
            l += roar * 0.5f; r += roar * 0.5f;
            // mechanical: valvetrain tick on each pulse. (A pure 'timing chain' tone at 21x the
            // revs used to sit here: at a cruise it stood ~4 dB over the engine's own harmonics
            // around it -- heard as an electric-motor whine. A real chain is texture, not a tone.)
            float mech = tickBp_.process(noise_.gauss()) * pulseEnv * pulseEnv * pulseEnv * 0.05f * (0.2f + rpmN);
            l += mech * 0.5f; r += mech * 0.5f;
            // overrun crackle
            if (popRate > 0.f && noise_.chance(popRate / sr_)) {
                for (int k = 0; k < 6; ++k) if (popPos_[k] < 0) { popPos_[k] = 0; popGain_[k] = noise_.uniform(0.4f, 1.f) * 0.35f; break; }
            }
            popPan_ += 3.0 / sr_ / (2 * M_PI);
            for (int k = 0; k < 6; ++k) if (popPos_[k] >= 0) {
                float v = pops_[k][popPos_[k]] * popGain_[k];
                float pan = 0.5f + 0.2f * sin1(popPan_);
                l += v * pan; r += v * (1.f - pan);
                if (++popPos_[k] >= (int)pops_[k].size()) popPos_[k] = -1;
            }
            // in the cabin
            l = hp_[0].process(shelf_[0].process(l));
            r = hp_[1].process(shelf_[1].process(r));
            out[i] = softClip(l, 1.6f) * vol;
            outR[i] = softClip(r, 1.6f) * vol;
        }
    }

private:
    float exhaust(int bank, float hit, const float* gain) {
        float y = 0.f;
        for (int f = 0; f < 4; ++f) y += res_[bank][f].process(hit) * gain[f];
        return y;
    }

    Noise noise_;
    Pink pink_;
    Resonator res_[2][4];
    float attack_[2] = {0, 0};
    float attackK_;
    Biq grit_, tailpipe_[2], header_[2], muffler_[2], intakeBp_, tickBp_, shelf_[2], hp_[2];
    Smooth level_, intakeLevel_;
    double firePhase_ = 0, popPan_ = 0;
    long long firing_ = 0;
    float sinceFire_ = 1e9f;
    int liftOffsSeen_ = -1;
    float crackleLeft_ = 0;
    std::vector<float> pops_[6];
    int popPos_[6];
    float popGain_[6] = {0};
};

std::unique_ptr<LayeredVoice> makeFlat6(int sr) { return std::make_unique<Flat6Voice>(sr); }

}  // namespace sc
