// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// The layered voices: several layers, each following its own control (gearbox revs and load,
// pedal, acceleration, speed, the car's own motor rpm). Ports of Open Blue's Flat6Voice,
// HypercarVoice, JetVoice and WarpVoice.
#pragma once

#include <memory>

#include "dct.h"

namespace sc {

enum class Sound { FLAT6, HYPERCAR, TURBOFAN, WARP };

struct SoundInfo {
    const char* name;
    float idle, redline;
    bool isEngine;     // makes its own fuel cuts / torque dips (no stutter from the output stage)
    bool hasGears;     // follows the gearbox (shift sound, limiter stutter)
};
const SoundInfo& info(Sound s);

class LayeredVoice {
public:
    explicit LayeredVoice(int sr) : sr_(sr) {}
    virtual ~LayeredVoice() = default;
    float volume = 0.8f;
    // once per block: the gearbox / engine state, the car's pedal, acceleration (m/s^2), motor rpm
    void set(const EngineState& s, float pedal, float accel, float motorRpm) {
        st_ = s; has_ = true; pedal_ = pedal; accel_ = accel; motorRpm_ = motorRpm;
    }
    // renders frames stereo samples into l / r (overwriting them)
    virtual void render(float* l, float* r, int frames) = 0;
protected:
    static float speedNorm(float kmh) { return kmh / 160.f < 0 ? 0 : (kmh / 160.f > 1 ? 1 : kmh / 160.f); }
    int sr_;
    EngineState st_;
    bool has_ = false;
    float pedal_ = 0, accel_ = 0, motorRpm_ = 0;
};

std::unique_ptr<LayeredVoice> makeVoice(Sound s, int sr);

}  // namespace sc
