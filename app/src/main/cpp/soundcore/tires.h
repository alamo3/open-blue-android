// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// The tires, from the cornering force (speed x yaw rate = the centripetal acceleration the
// tires hold the car to): a broadband scrub as they start working hard, then a squeal -- the
// tread's stick-slip singing in a narrow band, warbling and chattering -- as they near the limit
// of grip. Only at speed. Louder from the outside of the turn, where the loaded tires are.
#pragma once

#include "dsp.h"

namespace sc {

class Tires {
public:
    // grip used (g): the scrub fades in over SCRUB_FROM .. +SCRUB_SPAN, the squeal over SQUEAL_FROM .. +SQUEAL_SPAN
    static constexpr float SCRUB_FROM = 0.30f, SCRUB_SPAN = 0.25f, SQUEAL_FROM = 0.45f, SQUEAL_SPAN = 0.30f;
    // ...and only at speed: nothing below SPEED_FROM_KMH, all of it by SPEED_FULL_KMH
    static constexpr float SPEED_FROM_KMH = 15.f, SPEED_FULL_KMH = 45.f;

    explicit Tires(int sr);
    // adds the tires into l / r; lateral m/s^2 (+ = cornering left)
    void render(float* l, float* r, int frames, float lateral, float speedMs, float volume);
    float squeal() const { return squeal_; }    // 0..1, as heard now
    float scrub() const { return scrub_; }

private:
    int sr_;
    float scrub_ = 0, squeal_ = 0, side_ = 0;   // side: -1 (outside = left) .. +1 (outside = right)
    Noise noise_{0x7123};
    Pink pinkL_{0x51}, pinkR_{0x52};
    Biq scrubL_, scrubR_, scrubHpL_, scrubHpR_;
    Biq tone_[3];
    Wander pitch_, warble_, chatter_;
    double block_ = 0;
};

}  // namespace sc
