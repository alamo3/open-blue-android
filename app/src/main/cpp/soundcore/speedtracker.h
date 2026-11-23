// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// The car's speed between its readings, for a smooth sound (as Open Blue's SpeedTracker.kt).
// The car reports speed in whole km/h, ~30 times a second: held, or even smoothed, that steps the
// revs at every reading. This tracks speed and acceleration with an alpha-beta filter (each
// reading nudges both by how far it is from the prediction), predicts between readings, and
// glides the result -- about 25x smoother than smoothing the readings, with no more lag.
#pragma once

#include <algorithm>
#include <cmath>

namespace sc {

class SpeedTracker {
public:
    explicit SpeedTracker(float alpha = 0.1f, float beta = 0.005f, float glideSec = 0.03f)
        : alpha_(alpha), beta_(beta), glide_(glideSec) {}

    // a reading from the car: kmh (whole km/h) at time t (seconds, any steady clock)
    void reading(float kmh, double t) {
        float d = (float)(t - at_);
        if (x_ < 0.f || d > 1.f) { x_ = kmh; v_ = 0.f; at_ = t; out_ = kmh; stopped_ = kmh < 0.5f; return; }
        if (d <= 0.f) return;
        float predicted = x_ + v_ * d, r = kmh - predicted;
        x_ = predicted + alpha_ * r;
        v_ += beta_ * r / d;
        at_ = t;
        stopped_ = kmh < 0.5f && x_ < 1.5f;          // standing still: exactly zero, not drifting
        if (stopped_) { x_ = 0.f; v_ = 0.f; }
    }

    // the speed now (km/h), stepped once per audio block of dt seconds
    float next(double now, float dt) {
        if (x_ < 0.f) return 0.f;
        float ahead = std::clamp((float)(now - at_), 0.f, 0.25f);
        float est = stopped_ ? 0.f : std::max(0.f, x_ + v_ * ahead);
        out_ += (est - out_) * (1.f - std::exp(-dt / glide_));
        if (stopped_ && out_ < 0.05f) out_ = 0.f;   // settled at a standstill
        return std::max(0.f, out_);
    }

    float accel() const { return v_ / 3.6f; }          // m/s^2
    void reset() { x_ = -1.f; v_ = 0.f; out_ = 0.f; stopped_ = true; }

private:
    float alpha_, beta_, glide_;
    float x_ = -1.f, v_ = 0.f, out_ = 0.f;
    double at_ = 0;
    bool stopped_ = true;
};

}  // namespace sc
