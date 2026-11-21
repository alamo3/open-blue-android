// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
#include "dct.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>

#include "dsp.h"

namespace sc {

constexpr float Dct::RATIOS[];

float Dct::finalDrive() const {
    float wheelPerKmh = motorPerKmh_ / EV_REDUCTION;
    return (redline / GEAR1_TOP_KMH) / (wheelPerKmh * RATIOS[0]);
}

float Dct::gearRpm(int g, float kmh) const {
    float wheelRpm = kmh * motorPerKmh_ / EV_REDUCTION;
    return wheelRpm * finalDrive() * RATIOS[g - 1];
}

float Dct::upRpm(float p) const {
    float light = lerp(0.22f, 0.50f, sport_);
    float frac = light + (0.97f - light) * std::pow(clamp01(p), lerp(1.6f, 1.0f, sport_));
    return frac * redline;
}

float Dct::lugRpm(float p) const { return lerp(idleRpm * 1.25f, 0.40f * redline, p) * lerp(1.f, 1.25f, sport_); }

EngineState Dct::step(float dt, float kmh, float pedalIn, int motorRpm) {
    const float pedal = clamp01(pedalIn);
    const float r = redline;
    // idle: fast just after starting, settling over a minute, and never perfectly steady
    age_ += dt;
    idlePhase_ += dt / 1.2f;
    if (idlePhase_ >= 1.f) {
        idlePhase_ -= 1.f;
        idleFrom_ = idleTo_;
        idleTo_ = std::uniform_real_distribution<float>(-1.f, 1.f)(idleRnd_);
    }
    float wander = idleFrom_ + (idleTo_ - idleFrom_) * (0.5f - 0.5f * std::cos(kPi * idlePhase_));
    const float idle = idleRpm * (1.f + COLD_IDLE * std::exp(-age_ / COLD_IDLE_SEC)) + IDLE_WANDER_RPM * wander;
    if (motorRpm != 0 && motorRpm != lastMotor_ && kmh > 15.f)
        motorPerKmh_ += (std::abs(motorRpm) / kmh - motorPerKmh_) * RATIO_LEARN;
    lastMotor_ = motorRpm;
    if (lastKmh_ >= 0.f && dt > 0.f) {
        float a = (kmh - lastKmh_) / 3.6f / dt;
        accel_ += (a - accel_) * std::min(1.f, dt / 0.25f);
    }
    lastKmh_ = kmh;
    const float prevAvg = pedalAvg_;
    pedalT_ += (pedal - pedalT_) * std::min(1.f, dt / TORQUE_SMOOTH_SEC);
    pedalAvg_ += (pedal - pedalAvg_) * std::min(1.f, dt / 0.3f);
    sinceShift_ += dt;
    hold_ = std::max(0.f, hold_ - dt);

    // driving style: a deep pedal, a pedal stamped on, hard acceleration
    if (dt > 0.f) pedalRate_ += ((pedal - lastPedal_) / dt - pedalRate_) * std::min(1.f, dt / 0.1f);
    lastPedal_ = pedal;
    float inst = clamp01(std::max({(pedal - 0.35f) / 0.55f, (pedalRate_ - 1.5f) / 3.f * pedal, (accel_ - 1.f) / 3.f}));
    sport_ += (inst - sport_) * std::min(1.f, dt / (inst > sport_ ? 1.5f : 30.f));

    // fast off: a quick lift holds the gear rather than upshifting
    if (prevAvg > 0.45f && pedal < 0.1f && prevAvg - pedal > 0.35f) hold_ = FAST_OFF_HOLD_SEC;

    const bool braking = pedal < 0.04f && accel_ < -BRAKE_DECEL;
    const int g = gear_;
    if (shiftT_ <= 0.f && kmh > 2.f) {
        float here = gearRpm(g, kmh);
        // kickdown: a deep, fast press -- or floored and well down the revs (not just after a shift)
        bool kick = (pedal > 0.8f && pedal - pedalAvg_ > KICKDOWN_JUMP) ||
                    (pedal > 0.9f && here < 0.55f * r && g > 1 && sinceShift_ >= MIN_GEAR_SEC);
        int target = g;
        if (kick) {
            // the lowest gear that lands in the power band (may skip several)
            float limit = std::min(0.75f * r, 0.9f * upRpm(pedal));
            for (int c = 1; c < g; ++c) if (gearRpm(c, kmh) < limit) { target = c; break; }
        } else if (sinceShift_ >= MIN_GEAR_SEC) {
            bool slowing = accel_ < -0.5f && pedal < 0.2f;
            bool lifting = pedalRate_ < -1.5f;             // mid-lift: never upshift on the way off
            // floored, it holds the gear into the rev limiter and bounces off it before the upshift
            bool upReady = pedal >= LIMITER_PEDAL ? limiterT_ >= LIMITER_HOLD_SEC : here >= upRpm(pedalAvg_);
            if (g < GEARS && hold_ <= 0.f && !slowing && !lifting && upReady &&
                gearRpm(g + 1, kmh) >= lugRpm(pedalAvg_) * 1.1f) {       // lands clear of the downshift line
                target = g + 1;
            } else if (g > 1) {
                float lower = gearRpm(g - 1, kmh);
                float want = lugRpm(pedalAvg_);
                if (braking) want = std::max(want, lerp(0.30f, 0.55f, sport_) * r);   // downshift on the brakes
                if (here < want && lower < std::min(0.88f * r, 0.92f * upRpm(pedalAvg_) + 0.2f * r)) target = g - 1;
            }
        }
        if (hold_ > 0.f && here > 0.97f * r && g < GEARS) target = g + 1;   // held, but not into the limiter
        if (target != g) beginShift(target, pedal);
    }
    if (kmh <= 2.f && shiftT_ <= 0.f) gear_ = 1;
    const int gr = gear_;

    // engine speed: the wheels through the gear train; clutch slip pulling away
    float wheel = gearRpm(gr, kmh);
    float target = std::max(wheel, idle);
    if (gr == 1 && !clutchLocked_ && pedal > 0.02f) {
        float launch = lerp(idle * 1.3f, lerp(0.45f, 0.60f, sport_) * r, std::pow(pedal, 1.2f));
        if (kmh > 2.f) slipT_ += dt;
        float closing = std::min(1.f, slipT_ / CLUTCH_CLOSE_SEC);
        float bite = std::max(wheel, idle * 1.3f);
        float flare = 1.f + LAUNCH_FLARE * pedal * std::max(0.f, 1.f - slipT_ / 0.3f);   // the clutch takes up
        float held = (launch - (launch - bite) * smoothstep(closing) * (1.f - pedal)) * flare;
        target = std::max(target, held);
        if (wheel >= 0.98f * held) clutchLocked_ = true;
    }
    if (kmh < 2.f) { clutchLocked_ = false; slipT_ = 0.f; }

    bool limiter = false, cut = false;
    if (target >= r) { target = r; limiter = pedal > 0.5f; }
    if (limiter) {
        limiterT_ += dt;
        cut = (int)(limiterT_ * LIMITER_HZ * 2) % 2 == 1;
        if (cut) target = r * (1.f - LIMITER_DIP);
    } else {
        limiterT_ = 0.f;
    }

    float shifting = 0.f, blip = 0.f;
    if (shiftT_ > 0.f) {
        float frac = 1.f - shiftT_ / shiftLen_;
        float k = smoothstep(frac);
        if (shiftUp_) {
            // the torque handover: a dip, deep and sharp at full load, barely there when light
            float depth = lerp(0.25f, 0.9f, pedalT_);
            shifting = depth * (frac < 0.35f ? frac / 0.35f : 1.f - (frac - 0.35f) / 0.65f);
            rpm_ = shiftFromRpm_ + (target - shiftFromRpm_) * k;
        } else {
            // rev-matching blip; under power the revs flare past the new gear: the shift lands
            // this far above it and inertia settles it back
            blip = std::sin(kPi * std::min(1.f, frac / 0.7f));
            float over = lerp(0.03f, 0.08f, sport_) * (1.f - pedalT_) + POWER_FLARE * pedalT_;
            rpm_ = shiftFromRpm_ + (target * (1.f + over) - shiftFromRpm_) * k;
        }
        shiftT_ -= dt;
    } else {
        rpm_ += (target - rpm_) * std::min(1.f, dt / INERTIA_SEC);
    }

    float load = pedalT_ * (1.f - shifting);
    if (blip > 0.f && pedal < 0.3f) load = std::max(load, 0.45f * blip);   // the blip is a throttle stab
    bool engaged = pedalT_ > 0.04f || kmh < OVERRUN_KMH;
    if (engaged) {
        pullPeak_ = std::max(pullPeak_, load);
    } else {
        if (pullPeak_ > 0.45f && rpm_ > 0.5f * r) liftOffs_++;              // lifting off a hard pull: pops
        pullPeak_ = 0.f;
    }
    float rpmNorm = clamp01((rpm_ - idle) / (r - idle));
    EngineState s;
    s.rpm = rpm_; s.load = std::max(0.f, load); s.gear = gr; s.engaged = engaged; s.shifts = shifts_;
    s.limiter = limiter; s.liftOffs = liftOffs_; s.speedKmh = kmh; s.overrun = engaged ? 0.f : std::min(1.f, rpmNorm + 0.2f);
    s.shifting = shifting; s.cut = cut; s.downshifts = downshifts_; s.throttle = pedalT_;
    return s;
}

void Dct::beginShift(int to, float pedal) {
    bool up = to > gear_;
    if (up) {
        shiftLen_ = lerp(0.30f, 0.10f, std::max(pedal, 0.6f * sport_));
        shifts_++;
    } else {
        shiftLen_ = lerp(0.35f, 0.16f, sport_) + 0.05f * (std::abs(to - gear_) - 1);
        downshifts_++;
    }
    shiftFromRpm_ = rpm_;
    shiftUp_ = up;
    gear_ = to;
    shiftT_ = shiftLen_;
    sinceShift_ = 0.f;
}

EngineState NeutralRev::step(float dt, float throttle, float idle, float redline) {
    if (rpm_ <= 0.f) rpm_ = idle;
    float t = clamp01(throttle);
    bool was = engaged_;
    engaged_ = t > 0.02f;
    if (was && !engaged_ && load_ > 0.3f && rpm_ > 2800.f) liftOffs_++;
    load_ += ((engaged_ ? t : 0.f) - load_) * std::min(1.f, dt * 10.f);
    float target = t >= 0.95f ? redline : idle + (redline - idle) * t;
    bool limiter = false, cut = false;
    if (t >= 0.95f && rpm_ >= redline * 0.95f) {
        limiter = true;
        limT_ += dt;
        cut = (int)(limT_ * Dct::LIMITER_HZ * 2) % 2 == 1;
        if (cut) target = redline * (1.f - Dct::LIMITER_DIP);
    } else {
        limT_ = 0.f;
    }
    float d = target - rpm_;
    float stepMax = (d > 0 ? 6000.f : (cut ? 9000.f : 3500.f)) * dt;
    rpm_ = std::fabs(d) <= stepMax ? target : rpm_ + (d > 0 ? stepMax : -stepMax);
    EngineState s;
    s.rpm = rpm_; s.load = load_; s.gear = 0; s.engaged = engaged_; s.limiter = limiter; s.liftOffs = liftOffs_;
    s.speedKmh = (rpm_ - idle) / (redline - idle) * 160.f; s.cut = cut; s.throttle = load_;
    return s;
}

}  // namespace sc
