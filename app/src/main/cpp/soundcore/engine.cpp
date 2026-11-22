// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
#include "engine.h"

#include <algorithm>
#include <cmath>
#include <numeric>

#include "dsp.h"

namespace sc {

namespace {
// output stage (as Open Blue's SoundVoice)
constexpr float OUTPUT_TRIM = 0.5f, CEILING = 0.89f, LOOKAHEAD_SEC = 0.003f, LIMIT_RELEASE_SEC = 0.25f;
constexpr float LIMITER_CUT_LEVEL = 0.35f, LIMITER_EASE_SEC = 0.004f;
constexpr float CORNER_LEAN = 0.45f, CORNER_FULL_MS2 = 3.9f;   // the lean: this far over by 0.4 g
constexpr float SPEED_FROM_KMH = 30.f, SPEED_FULL_KMH = 120.f, SPEED_GAIN_DB = 3.f, SPEED_BRIGHT_DB = 2.f;
// hill effort (as Effort.kt)
constexpr float EF_MIN_KMH = 18.f, EF_MIN_PEDAL = 0.15f, EF_LEARN_SEC = 90.f, EF_NOW_SEC = 3.f, EF_GAIN = 0.5f, EF_MAX = 0.8f;
// test drive (as TestDrive.kt)
constexpr float TD_GENTLE_PEDAL = 0.35f, TD_GENTLE_TO_KMH = 40.f, TD_HARD_PEDAL = 1.f, TD_TOP_KMH = 100.f;
constexpr float TD_IDLE_SEC = 1.5f, TD_LIFT_SEC = 2.5f, TD_BRAKE_MS2 = 3.f, TD_SETTLE_SEC = 2.f, TD_PEDAL_PER_SEC = 5.f;
constexpr float TD_BEND_LEFT_M = 25.f, TD_BEND_RIGHT_M = 100.f;   // a brisk left, then a right that has the tires squealing
const char* TD_STAGES[] = {"idle", "pulling away", "hard to 100 km/h", "off the pedal", "braking to a stop", "idle", "done"};
}  // namespace

// --- Effort ----------------------------------------------------------------------------------
float Effort::expectedPush(float pedal, float speedMs) { return std::pow(pedal, 0.8f) * std::min(3.9f, 160000.f / 2100.f / std::max(speedMs, 1.f)); }
float Effort::drag(float speedMs) { return 0.15f + 0.0004f * speedMs * speedMs; }

float Effort::step(float dt, float pedal, float speedMs, float accel) {
    float p = clamp01(pedal);
    bool usable = p >= EF_MIN_PEDAL && speedMs * 3.6f >= EF_MIN_KMH;
    if (usable) {
        float achieved = clampf((accel + drag(speedMs)) / expectedPush(p, speedMs), 0.f, 2.f);
        now_ += (achieved - now_) * (1.f - std::exp(-dt / EF_NOW_SEC));
        usual_ += (achieved - usual_) * (1.f - std::exp(-dt / EF_LEARN_SEC));
        learned_ += dt;
    }
    float confidence = std::min(1.f, learned_ / 20.f);
    float target = usable ? clampf((usual_ - now_) / std::max(usual_, 0.2f), -1.f, EF_MAX) * confidence : 0.f;
    strain_ += (target - strain_) * (1.f - std::exp(-dt / 1.f));
    return clamp01(p * (1.f + EF_GAIN * strain_));
}

// --- TestDrive ---------------------------------------------------------------------------------
const char* TestDrive::stage() const { return TD_STAGES[stage_]; }

bool TestDrive::step(float dt) {
    if (stage_ == 6) return false;
    inStage_ += dt;
    float want = stage_ == 1 ? TD_GENTLE_PEDAL : stage_ == 2 ? TD_HARD_PEDAL : 0.f;
    float brake = stage_ == 4 ? TD_BRAKE_MS2 : 0.f;
    pedal += clampf(want - pedal, -TD_PEDAL_PER_SEC * dt, TD_PEDAL_PER_SEC * dt);
    float push = std::pow(pedal, 0.8f) * std::min(3.9f, 160000.f / 2100.f / std::max(speed, 1.f));
    float regen = speed > 0.5f ? 1.7f * std::max(0.f, 1.f - pedal / 0.06f) : 0.f;
    float drag = (speed > 0.f || push > 0.15f) ? 0.15f + 0.0004f * speed * speed : 0.f;
    float before = speed;
    speed = std::max(0.f, speed + (push - drag - regen - brake) * dt);
    accel = dt > 0.f ? (speed - before) / dt : 0.f;
    float radius = (stage_ == 1 && kmh() >= 20.f && kmh() <= 35.f) ? TD_BEND_LEFT_M : (stage_ == 3 ? -TD_BEND_RIGHT_M : 0.f);
    float wantYaw = radius == 0.f ? 0.f : speed / radius;
    yawRate += (wantYaw - yawRate) * std::min(1.f, dt / 0.5f);
    bool next = (stage_ == 0 && inStage_ >= TD_IDLE_SEC) || (stage_ == 1 && kmh() >= TD_GENTLE_TO_KMH) ||
                (stage_ == 2 && kmh() >= TD_TOP_KMH) || (stage_ == 3 && inStage_ >= TD_LIFT_SEC) ||
                (stage_ == 4 && speed <= 0.f) || (stage_ == 5 && inStage_ >= TD_SETTLE_SEC);
    if (next) { stage_++; inStage_ = 0.f; }
    return stage_ != 6;
}

// --- Engine ----------------------------------------------------------------------------------
Engine::Engine(int sampleRate, const std::string& effectsDir)
    : sr_(sampleRate), effects_(sampleRate, effectsDir), tires_(sampleRate), la_(std::max(2, (int)(LOOKAHEAD_SEC * sampleRate))) {
    for (int s = 0; s < 4; ++s) voices_[s] = makeVoice((Sound)s, sampleRate);
    delayL_.reset(new float[la_ - 1]()); delayR_.reset(new float[la_ - 1]());
    minVal_.reset(new float[la_]()); minIdx_.reset(new long long[la_]());
    avgBuf_.reset(new float[la_]); std::fill(avgBuf_.get(), avgBuf_.get() + la_, 1.f);
    avgSum_ = la_;
    setSound(Sound::FLAT6);
}

Engine::~Engine() = default;

void Engine::setSound(Sound s) {
    if (dct_ && s == sound_) return;   // the same sound again (hosts resend settings): keep its gearbox as it is
    sound_ = s;
    const SoundInfo& in = info(s);
    dct_ = std::make_unique<Dct>(in.idle, in.redline);
    shiftsSeen_ = downSeen_ = -1;      // a different sound's gearbox: don't hear its count change as a shift
}

void Engine::startTestDrive() { testDrive_ = std::make_unique<TestDrive>(); }
void Engine::stopTestDrive() { testDrive_.reset(); }

void Engine::render(const Inputs& inRaw, float* l, float* r, int frames) {
    NoDenormals ftz;
    const float dt = (float)frames / sr_;
    const SoundInfo& si = info(sound_);
    Inputs in = inRaw;
    // a test drive stands in for the car while it runs
    if (testDrive_) {
        if (testDrive_->step(dt)) {
            in.pedal = testDrive_->pedal; in.hasPedal = true; in.speedMs = testDrive_->speed; in.accel = testDrive_->accel;
            in.motorRpm = testDrive_->motorRpm(); in.reversing = false; in.yawRate = testDrive_->yawRate;
        } else {
            testDrive_.reset();
        }
    }
    const float kmh = in.speedMs * 3.6f;
    // the pedal as the gearbox and the sound feel it: heavier uphill, lighter downhill
    float pedal = in.hasPedal ? (in.reversing ? 0.f : in.pedal) : clamp01(in.accel / 3.f);
    float effortPedal = in.hasPedal ? effort_.step(dt, pedal, in.speedMs, in.accel) : pedal;

    EngineState st;
    if (in.revThrottle >= 0.f) {
        st = neutral_.step(dt, in.revThrottle, si.idle, si.redline);
    } else if (gearshifts_) {
        st = dct_->step(dt, kmh, effortPedal, in.motorRpm);
    } else {
        // one fixed gear: revs follow speed, pushing harder adds revs on top
        directLoad_ += (effortPedal - directLoad_) * std::min(1.f, dt / 0.04f);
        float target = std::min(si.redline, si.idle + kmh * 24.f + 2400.f * directLoad_);
        if (directRpm_ <= 0.f) directRpm_ = si.idle;
        directRpm_ += (target - directRpm_) * std::min(1.f, dt / 0.06f);
        st.rpm = directRpm_; st.load = directLoad_; st.gear = -1; st.engaged = directLoad_ > 0.04f || kmh < 6.f;
        st.speedKmh = kmh; st.throttle = directLoad_;
        st.overrun = st.engaged ? 0.f : std::min(1.f, clamp01((directRpm_ - si.idle) / (si.redline - si.idle)) + 0.2f);
    }

    // fade in at the start and out at the end, so there's no click
    float target = active_ ? 1.f : 0.f;
    fade_ = fade_ < target ? std::min(target, fade_ + dt * 3.f) : std::max(target, fade_ - dt * 5.f);
    const float vol = volume_ * fade_;

    if (!chimed_) { chimed_ = true; effects_.chime(); }
    Effects::Launch launch = sound_ == Sound::TURBOFAN ? Effects::Launch::BOOM
                           : (sound_ == Sound::HYPERCAR ? Effects::Launch::EV : Effects::Launch::NONE);
    effects_.step(dt, kmh, in.hasPedal ? pedal : st.load, in.reversing, launch);
    // shifts made while driving hard get the shift sound; sounds without a torque cut dip under it
    if (st.gear >= 1 && si.hasGears) {
        float effortNow = in.hasPedal ? pedal : st.throttle;
        if (shiftsSeen_ >= 0 && st.shifts != shiftsSeen_) effects_.shift(true, effortNow, !si.isEngine);
        if (downSeen_ >= 0 && st.downshifts != downSeen_) effects_.shift(false, effortNow, false);
    }
    shiftsSeen_ = st.shifts;
    downSeen_ = st.downshifts;

    LayeredVoice& v = *voices_[(int)sound_];
    v.volume = vol;
    v.set(st, in.hasPedal ? pedal : st.throttle, in.accel, (float)std::abs(in.motorRpm));
    v.render(l, r, frames);

    // the rev limiter: engines chop on its fuel cuts themselves; the others stutter with it
    float cutTarget = (!si.isEngine && si.hasGears && st.cut) ? LIMITER_CUT_LEVEL : 1.f;
    if (cutTarget < 1.f || cutGain_ < 1.f) {
        float k = 1.f / (LIMITER_EASE_SEC * sr_);
        for (int n = 0; n < frames; ++n) { cutGain_ += (cutTarget - cutGain_) * k; l[n] *= cutGain_; r[n] *= cutGain_; }
        if (cutTarget == 1.f && cutGain_ > 0.999f) cutGain_ = 1.f;
    }
    // the tires: the cornering force (speed x yaw rate) has them scrubbing, then squealing
    tires_.render(l, r, frames, in.reversing ? 0.f : in.speedMs * in.yawRate, in.speedMs, vol);
    effects_.mix(l, r, frames, vol);
    speedMix(l, r, frames, kmh);
    lean(l, r, frames, in.speedMs * in.yawRate);
    limit(l, r, frames);

    readout_.rpm = st.rpm; readout_.kmh = kmh; readout_.load = st.load; readout_.gear = st.gear; readout_.limiter = st.limiter;
    readout_.tires = tires_.squeal();
    readout_.testDrive = testDrive_ ? testDrive_->stage() : nullptr;
}

// at speed the sound comes up a little to stay present over road and wind noise
void Engine::speedMix(float* l, float* r, int frames, float kmh) {
    float target = clamp01((kmh - SPEED_FROM_KMH) / (SPEED_FULL_KMH - SPEED_FROM_KMH));
    speedMix_ += (target - speedMix_) * std::min(1.f, frames / (1.f * sr_));
    if (speedMix_ < 1e-3f) return;
    tiltL_.highShelf(3000.f, 0.707f, SPEED_BRIGHT_DB * speedMix_, sr_);
    tiltR_.highShelf(3000.f, 0.707f, SPEED_BRIGHT_DB * speedMix_, sr_);
    float g = std::pow(10.f, SPEED_GAIN_DB * speedMix_ / 20.f);
    for (int n = 0; n < frames; ++n) { l[n] = tiltL_.process(l[n]) * g; r[n] = tiltR_.process(r[n]) * g; }
}

// leans the sound toward the outside of a corner, equal-power (+ lateral = cornering left -> leans right)
void Engine::lean(float* l, float* r, int frames, float lateral) {
    float target = clampf(lateral / CORNER_FULL_MS2, -1.f, 1.f) * CORNER_LEAN;
    float dt = (float)frames / sr_;
    float l0 = lean_;
    lean_ += (target - lean_) * std::min(1.f, dt / 0.2f);
    if (std::fabs(l0) < 1e-4f && std::fabs(lean_) < 1e-4f) return;
    const float sq2 = std::sqrt(2.f);
    for (int n = 0; n < frames; ++n) {
        float p = l0 + (lean_ - l0) * n / frames;
        float a = kPi / 4 * (1 + p);
        l[n] *= sq2 * std::cos(a);
        r[n] *= sq2 * std::sin(a);
    }
}

// look-ahead peak limiter, linked across both channels: the output runs ~3 ms late so the gain
// glides down before a peak instead of snapping on it; peaks never pass CEILING
void Engine::limit(float* l, float* r, int frames) {
    float rel = 1.f / (LIMIT_RELEASE_SEC * sr_);
    float g = limitGain_;
    const int dl = la_ - 1;
    for (int n = 0; n < frames; ++n) {
        float x = l[n] * OUTPUT_TRIM, y = r[n] * OUTPUT_TRIM;
        float peak = std::max(std::fabs(x), std::fabs(y));
        float need = peak > CEILING ? CEILING / peak : 1.f;
        if (minCount_ > 0 && minIdx_[minHead_] <= sampleIdx_ - la_) { minHead_ = (minHead_ + 1) % la_; minCount_--; }
        while (minCount_ > 0 && minVal_[(minHead_ + minCount_ - 1) % la_] >= need) minCount_--;
        minVal_[(minHead_ + minCount_) % la_] = need;
        minIdx_[(minHead_ + minCount_) % la_] = sampleIdx_;
        minCount_++;
        float m = minVal_[minHead_];
        avgSum_ += m - avgBuf_[avgPos_];
        avgBuf_[avgPos_] = m;
        avgPos_ = (avgPos_ + 1) % la_;
        float s = (float)(avgSum_ / la_);
        g = s < g ? s : g + (s - g) * rel;
        l[n] = delayL_[delayPos_] * g;
        r[n] = delayR_[delayPos_] * g;
        delayL_[delayPos_] = x;
        delayR_[delayPos_] = y;
        delayPos_ = (delayPos_ + 1) % dl;
        sampleIdx_++;
    }
    limitGain_ = g;
    if ((sampleIdx_ & 0xFFFF) < frames) avgSum_ = std::accumulate(avgBuf_.get(), avgBuf_.get() + la_, 0.0);
}

}  // namespace sc
