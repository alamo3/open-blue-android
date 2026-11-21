// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// Warp bubble: a spaceship's field drive from inside the ship (port of Open Blue's WarpVoice.kt).
// No gears: motion from acceleration, size from speed. Continuous layers (Shepard-Risset drone,
// unstable field, throbbing bubble, tunnel rush, comb shimmer, widening stereo) plus random
// events (streaks, hull groans, pressure booms, arcing) and driving events (launch from rest:
// inflate, then punch through or deflate; a jump on a hard press; a collapse on a lift; a drop
// out of warp braking through 20 km/h). Each event is a small real-time generator.
#include <algorithm>
#include <cmath>
#include <vector>

#include "dsp.h"
#include "voices.h"

namespace sc {

namespace {
constexpr float BASE_HZ = 55.f, GLIDE_OCT_PER_MS2 = 0.06f, LAUNCH_PEDAL = 0.75f, LAUNCH_MAX_KMH = 15.f;
constexpr float INFLATE_SEC = 1.2f, TRIM = 5.2f;
constexpr int OCTAVES = 7, MAX_EVENTS = 24;
enum class Kind { STREAK, GROAN, BOOM, ARC, SWELL, JUMP, INFLATE, PUNCH, DEFLATE, COLLAPSE, DROP };

struct Ev {
    explicit Ev(int seed) : n(1000 + seed), pink(2000 + seed) {}
    Kind kind = Kind::STREAK;
    bool active = false;
    int t = 0, len = 0;            // samples since start, length
    float gain = 0, pan = 0.5f, p0 = 0;
    float f[3] = {0, 0, 0};
    double ph[3] = {0, 0, 0};
    Biq bp[3], lp;
    Noise n;
    Pink pink;
    int delay = 0;                 // samples before it starts (staggered bursts)
    float clickP = 0;
    bool punch = false;            // INFLATE / SWELL: still on the pedal at the end?
};
}  // namespace

class WarpVoice : public LayeredVoice {
public:
    explicit WarpVoice(int sr)
        : LayeredVoice(sr), rnd_(41), wanderPitch_(0.6f, sr, 42), wanderLevel_(0.4f, sr, 43), wanderThrob_(0.3f, sr, 44),
          wanderTurb_(1.2f, sr, 45), wanderTunnel_(0.5f, sr, 46), pinkL_(47), pinkR_(48), noise_(49),
          droneLevel_(0.05f, sr), bubbleLevel_(0.2f, sr), shimmerLevel_(0.3f, sr), accelS_(0.3f, sr) {
        events_.reserve(MAX_EVENTS);
        for (int i = 0; i < MAX_EVENTS; ++i) events_.emplace_back(i);
        shimmerHp_.highPass(2500.f, 0.707f, sr);
        sparkleLp_.lowPass(12.f, 0.707f, sr);
        for (int c = 0; c < 2; ++c) hp_[c].highPass(25.f, 0.707f, sr);
    }

    void render(float* out, float* outR, int frames) override {
        if (!has_) { std::fill(out, out + frames, 0.f); std::fill(outR, outR + frames, 0.f); return; }
        const EngineState& s = st_;
        float dt = (float)frames / sr_;
        float kmh = s.speedKmh, speedN = speedNorm(kmh);
        float p = clamp01(pedal_), load = clamp01(s.load);
        float acc = accelS_.block(accel_, frames);
        pedalRate_ += ((p - lastPedal_) / dt - pedalRate_) * std::min(1.f, dt / 0.04f);

        // driving events
        sinceLaunch_ += dt; sinceJump_ += dt; sinceCollapse_ += dt;
        if (lastPedal_ < LAUNCH_PEDAL && p >= LAUNCH_PEDAL && kmh < LAUNCH_MAX_KMH && sinceLaunch_ > 4.f) {
            sinceLaunch_ = 0.f;
            spawn(Kind::INFLATE, INFLATE_SEC, 1.f);
        } else if (lastPedal_ < 0.6f && p >= 0.6f && pedalRate_ > 2.f && sinceJump_ > 3.f && sinceLaunch_ > 3.f) {
            sinceJump_ = 0.f;
            spawn(Kind::SWELL, 1.3f, 0.22f);
        }
        if (pedalRate_ < -2.5f && sinceCollapse_ > 1.f) { sinceCollapse_ = 0.f; spawn(Kind::COLLAPSE, 1.3f, 0.5f); }
        if (lastKmh_ >= 20.f && kmh < 20.f && acc < -1.5f) spawn(Kind::DROP, 1.6f, 0.5f);
        lastPedal_ = p; lastKmh_ = kmh;
        for (auto& e : events_) if (e.active && (e.kind == Kind::INFLATE || e.kind == Kind::SWELL))
            e.punch = p > (e.kind == Kind::INFLATE ? 0.7f : 0.8f);

        // random events (rates per second)
        float stress = clamp01(std::fabs(acc) / 3.f);
        float heavy = clamp01((load - 0.5f) / 0.5f) * (0.3f + 0.7f * speedN);
        if (rnd_.chance((0.15f + 2.2f * std::pow(speedN, 1.2f)) * dt)) streak(rnd_.uniform(0.25f, 0.9f) * (1.25f - 0.6f * speedN));
        if (rnd_.chance((0.02f + 0.5f * std::pow(stress, 1.5f)) * dt)) groan(stress);
        if (rnd_.chance((0.03f + 0.10f * speedN) * dt)) boom();
        if (rnd_.chance(1.8f * heavy * dt)) arc();
        if (flickerT_ < 0 && rnd_.chance((0.05f + 0.25f * speedN * (0.4f + 0.6f * load)) * dt)) {
            flickerT_ = 0; flickerLen_ = (int)(rnd_.uniform(0.06f, 0.18f) * sr_); flickerDepth_ = rnd_.uniform(0.4f, 0.8f);
        }

        // per-block settings
        float centre = lerp(3.f, 4.2f, speedN);
        float droneT = 0.10f + 0.08f * speedN + 0.10f * load;
        float bubbleT = 0.25f + 0.35f * load + 0.2f * speedN;
        float bubbleHz = lerp(38.f, 52.f, speedN);
        float throbHz = lerp(0.8f, 7.f, speedN);
        float tunnelHz = lerp(300.f, 1400.f, speedN) * (1.f + 0.2f * wanderTunnel_.next(frames));
        for (int c = 0; c < 2; ++c) tunnelBp_[c].bandPass(tunnelHz, 0.5f, sr_);
        float turb = clampf(1.f + 0.45f * wanderTurb_.next(frames), 0.2f, 2.f) * (0.01f + 0.10f * std::pow(speedN, 1.3f));
        float combHz = BASE_HZ * std::pow(2.f, 3.f + (float)(glide_ - std::floor(glide_)));
        const float harm[4] = {8.f, 12.f, 16.f, 24.f};
        for (int k = 0; k < 4; ++k) shimmerBp_[k].bandPass(combHz * harm[k], 18.f, sr_);
        float shimmerT = 0.02f + 0.10f * speedN + 0.08f * load;
        float width = lerp(0.8f, 1.8f, speedN);
        for (auto& e : events_) if (e.active) retune(e);
        // the drone's partials change slowly: pitch, envelope and pan per block
        double wobble = std::pow(2.0, 18.0 * wanderPitch_.next(frames) / 1200.0);
        glide_ += acc * GLIDE_OCT_PER_MS2 * dt;
        lfoT_ += dt;
        for (int k = 0; k < OCTAVES; ++k) {
            double pos = std::fmod(std::fmod(k + glide_, (double)OCTAVES) + OCTAVES, (double)OCTAVES);
            partialInc_[k] = BASE_HZ * std::pow(2.0, pos) * wobble / sr_;
            partialEnv_[k] = (float)std::exp(-0.5 * std::pow((pos - centre) / 1.25, 2));
            partialPan_[k] = 0.5f + 0.25f * sin1((0.05 + 0.02 * k) * lfoT_ + k / (2 * M_PI));
        }

        float vol = volume * TRIM;
        for (int i = 0; i < frames; ++i) {
            // the field: wander and flicker
            float field = 1.f + 0.22f * wanderLevel_.next();
            if (flickerT_ >= 0) {
                float sh = (float)std::sin(M_PI * flickerT_ / flickerLen_);
                field *= 1.f - flickerDepth_ * sh * sh;
                if (++flickerT_ >= flickerLen_) flickerT_ = -1;
            }
            // drone
            float dl = 0.f, dr = 0.f;
            for (int k = 0; k < OCTAVES; ++k) {
                dronePh_[k] += partialInc_[k];
                float x = (sin1(dronePh_[k]) + 0.15f * sin1(dronePh_[k] * 3)) * partialEnv_[k];
                dl += x * partialPan_[k]; dr += x * (1.f - partialPan_[k]);
            }
            float dg = droneLevel_.next(droneT) * field * 0.5f;
            float l = dl * dg, r = dr * dg;
            // bubble
            throbPh_ += throbHz * (1.f + 0.15f * wanderThrob_.next()) / sr_;
            float th = sin1(throbPh_);
            bubblePh_ += bubbleHz / sr_;
            float bub = sin1(bubblePh_) * (0.55f + 0.45f * th * th) * bubbleLevel_.next(bubbleT) * 0.32f * field * 0.5f;
            l += bub; r += bub;
            // tunnel
            l += tunnelBp_[0].process(pinkL_.next()) * turb;
            r += tunnelBp_[1].process(pinkR_.next()) * turb;
            // shimmer
            float nz = shimmerHp_.process(noise_.gauss());
            float sh = 0.f;
            for (int k = 0; k < 4; ++k) sh += shimmerBp_[k].process(nz);
            sh *= std::fabs(sparkleLp_.process(noise_.gauss())) * 4.f * shimmerLevel_.next(shimmerT) * 0.45f;
            l += sh * 0.6f; r += sh * 0.4f;
            // events
            for (auto& e : events_) if (e.active) {
                if (e.delay > 0) { e.delay--; continue; }
                float v = eventSample(e);
                l += v * e.pan; r += v * (1.f - e.pan);
                if (++e.t >= e.len) finish(e);
            }
            // width
            float mid = (l + r) * 0.5f, side = (l - r) * 0.5f * width;
            out[i] = softClip(hp_[0].process(mid + side), 1.4f) * vol;
            outR[i] = softClip(hp_[1].process(mid - side), 1.4f) * vol;
        }
    }

private:
    Ev* spawn(Kind kind, float len, float gain, float pan = 0.5f) {
        for (auto& e : events_) if (!e.active) {
            e.kind = kind; e.active = true; e.t = 0; e.len = std::max(1, (int)(len * sr_));
            e.gain = gain; e.pan = pan; e.punch = false; e.delay = 0;
            for (double& p : e.ph) p = 0.0;
            return &e;
        }
        return nullptr;
    }
    void streak(float dur) {
        float p0 = rnd_.chance(0.5f) ? rnd_.uniform(0.f, 0.3f) : rnd_.uniform(0.7f, 1.f);
        Ev* e = spawn(Kind::STREAK, dur, rnd_.uniform(0.03f, 0.08f));
        if (!e) return;
        e->p0 = p0; e->f[0] = rnd_.uniform(700.f, 3800.f);
        e->gain *= 0.4f + 0.6f * speedNorm(st_.speedKmh);
    }
    void groan(float stress) {
        Ev* e = spawn(Kind::GROAN, rnd_.uniform(0.6f, 1.6f), rnd_.uniform(0.15f, 0.3f) * (0.4f + 0.6f * stress), rnd_.uniform(0.3f, 0.7f));
        if (!e) return;
        for (float& f : e->f) f = rnd_.uniform(70.f, 380.f);
        e->lp.lowPass(600.f, 0.707f, sr_);
    }
    void boom() {
        Ev* e = spawn(Kind::BOOM, 2.4f, 0.5f);
        if (!e) return;
        e->f[0] = rnd_.uniform(28.f, 44.f);
        e->lp.lowPass(140.f, 0.707f, sr_);
    }
    void arc() {
        Ev* e = spawn(Kind::ARC, rnd_.uniform(0.08f, 0.35f), 0.25f, rnd_.uniform(0.2f, 0.8f));
        if (!e) return;
        e->clickP = rnd_.uniform(0.002f, 0.01f);
        e->bp[0].bandPass(rnd_.uniform(3500.f, 7000.f), 3.f, sr_);
        e->lp.highPass(2500.f, 0.707f, sr_);
    }
    // what follows an event that ends: the punch after an inflate or a swell, if still pressing
    void finish(Ev& e) {
        e.active = false;
        if (e.kind == Kind::INFLATE) {
            if (e.punch) {
                spawn(Kind::PUNCH, 2.2f, 1.f);
                for (int k = 0; k < 10; ++k) {               // a burst of star streaks, outward both ways
                    Ev* s = spawn(Kind::STREAK, rnd_.uniform(0.25f, 0.6f), rnd_.uniform(0.04f, 0.09f));
                    if (!s) break;
                    s->p0 = k % 2 == 0 ? 0.85f : 0.15f;
                    s->f[0] = rnd_.uniform(900.f, 4200.f);
                    s->delay = (int)(rnd_.uniform(0.f, 1.2f) * sr_);
                }
            } else {
                spawn(Kind::DEFLATE, 1.0f, 1.f);
            }
        } else if (e.kind == Kind::SWELL && e.punch) {
            spawn(Kind::JUMP, 2.5f, 0.6f);
        }
    }
    void retune(Ev& e) {
        float u = clamp01((float)e.t / e.len);
        switch (e.kind) {
            case Kind::STREAK: e.bp[0].bandPass(e.f[0] * lerp(1.35f, 0.65f, u), 7.f, sr_); break;
            case Kind::GROAN: for (int k = 0; k < 3; ++k) e.bp[k].bandPass(e.f[k] * lerp(1.f, 0.9f, u), 25.f, sr_); break;
            case Kind::SWELL: e.bp[0].bandPass(lerp(400.f, 3000.f, u), 1.f, sr_); break;
            case Kind::JUMP: e.bp[0].bandPass(2500.f, 0.5f, sr_); e.lp.lowPass(lerp(1800.f, 200.f, u), 0.707f, sr_); break;
            case Kind::INFLATE: e.bp[0].bandPass(lerp(200.f, 2600.f, u), 1.2f, sr_); e.lp.highPass(5000.f, 0.707f, sr_); break;
            case Kind::PUNCH: e.bp[0].bandPass(3000.f, 0.6f, sr_); e.bp[1].bandPass(lerp(200.f, 4000.f, u * 2.2f / 1.6f), 1.f, sr_); break;
            case Kind::DEFLATE: e.bp[0].bandPass(lerp(2000.f, 200.f, u), 1.f, sr_); break;
            case Kind::COLLAPSE: e.bp[0].bandPass(1400.f, 0.9f, sr_); e.lp.lowPass(lerp(6000.f, 300.f, u), 0.7f, sr_); break;
            default: break;
        }
    }
    float eventSample(Ev& e) {
        float ts = (float)e.t / sr_, u = (float)e.t / e.len;
        switch (e.kind) {
            case Kind::STREAK: {
                float fc = e.f[0] * lerp(1.35f, 0.65f, u);
                e.ph[0] += fc * 0.5 / sr_;
                float env = std::pow((float)std::sin(M_PI * u), 3.f);
                e.pan = lerp(e.p0, 1.f - e.p0, u);
                return (e.bp[0].process(e.n.gauss()) + sin1(e.ph[0]) * 0.25f) * env * e.gain;
            }
            case Kind::GROAN: {
                float dur = (float)e.len / sr_;
                float exc = e.lp.process(e.n.gauss()) * (1.f - std::exp(-ts / 0.05f)) * std::exp(-ts / (dur * 0.4f));
                return (e.bp[0].process(exc) + e.bp[1].process(exc) + e.bp[2].process(exc)) * e.gain;
            }
            case Kind::BOOM: {
                float env = (1.f - std::exp(-ts / 0.12f)) * std::exp(-ts / 0.7f);
                e.ph[0] += (double)e.f[0] / sr_;
                return (sin1(e.ph[0]) * 0.8f + e.lp.process(e.n.gauss()) * 0.6f) * env * e.gain;
            }
            case Kind::ARC: {
                float click = e.n.chance(e.clickP) ? 1.f : 0.f;
                return e.lp.process(e.bp[0].process(click) + 0.15f * e.n.gauss()) * (float)std::sin(M_PI * u) * e.gain;
            }
            case Kind::SWELL: {
                e.ph[0] += lerp(70.f, 700.f, u * u) / sr_;
                return (sin1(e.ph[0]) * 0.5f + e.bp[0].process(e.n.gauss()) * 0.6f) * u * u * e.gain;
            }
            case Kind::JUMP: {
                e.ph[0] += 36.0 / sr_;
                float flash = e.bp[0].process(e.n.gauss()) * std::exp(-ts / 0.15f) * 0.35f;
                float tail = e.lp.process(e.pink.next()) * std::exp(-ts / 0.8f) * 0.35f;
                float thud = sin1(e.ph[0]) * (1.f - std::exp(-ts / 0.01f)) * std::exp(-ts / 0.45f) * 0.9f;
                return (flash + tail + thud) * e.gain;
            }
            case Kind::INFLATE: {
                e.ph[0] += lerp(30.f, 56.f, u) / sr_;
                float g = u * u;
                return (sin1(e.ph[0]) * g * 0.9f + e.bp[0].process(e.n.gauss()) * g * 0.35f +
                        e.lp.process(e.n.gauss()) * u * u * u * 0.12f) * e.gain;
            }
            case Kind::PUNCH: {
                e.ph[0] += lerp(90.f, 28.f, ts / 0.8f) / sr_;
                float sub = sin1(e.ph[0]) * (1.f - std::exp(-ts / 0.008f)) * std::exp(-ts / 0.6f);
                float flash = e.bp[0].process(e.n.gauss()) * std::exp(-ts / 0.12f) * 0.35f;
                float tunnel = e.bp[1].process(e.pink.next()) * std::pow((float)std::sin(M_PI * std::min(1.f, ts / 2.2f)), 1.5f) * 0.35f;
                return (sub + flash + tunnel) * e.gain;
            }
            case Kind::DEFLATE: {
                e.ph[0] += lerp(56.f, 24.f, u) / sr_;
                return (sin1(e.ph[0]) * 0.8f + e.bp[0].process(e.n.gauss()) * 0.3f) * std::exp(-ts / 0.35f) * e.gain;
            }
            case Kind::COLLAPSE: {
                e.pan = lerp(0.77f, 0.23f, u);
                return e.lp.process(e.bp[0].process(e.n.gauss())) * std::exp(-ts / 0.35f) * e.gain;
            }
            case Kind::DROP: {
                e.ph[0] += lerp(600.f, 50.f, u) / sr_;
                e.ph[1] += 40.0 / sr_;
                float d = (ts - 0.9f) / 0.12f;
                return (sin1(e.ph[0]) * std::exp(-ts / 0.5f) * 0.4f + sin1(e.ph[1]) * std::exp(-d * d) * 0.8f) * e.gain;
            }
        }
        return 0.f;
    }

    std::vector<Ev> events_;
    Noise rnd_;
    double dronePh_[OCTAVES] = {0}, partialInc_[OCTAVES] = {0};
    float partialEnv_[OCTAVES] = {0}, partialPan_[OCTAVES] = {0};
    double glide_ = 0, lfoT_ = 0;
    Wander wanderPitch_, wanderLevel_, wanderThrob_, wanderTurb_, wanderTunnel_;
    int flickerT_ = -1, flickerLen_ = 1;
    float flickerDepth_ = 0;
    double bubblePh_ = 0, throbPh_ = 0;
    Biq tunnelBp_[2];
    Pink pinkL_, pinkR_;
    Biq shimmerHp_, shimmerBp_[4], sparkleLp_, hp_[2];
    Noise noise_;
    Smooth droneLevel_, bubbleLevel_, shimmerLevel_, accelS_;
    float lastPedal_ = 0, pedalRate_ = 0, lastKmh_ = 0;
    float sinceLaunch_ = 1e9f, sinceJump_ = 1e9f, sinceCollapse_ = 1e9f;
};

std::unique_ptr<LayeredVoice> makeWarp(int sr) { return std::make_unique<WarpVoice>(sr); }

}  // namespace sc
