// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// The drive effects (port of Open Blue's DriveEffects.kt): the start-up chime, launches (the EV
// build and bang, the sonic boom), the hydraulic shift sound on shifts made while driving hard,
// the reversing loop with the main sound ducked. The sounds are WAV files from a directory
// (Open Blue's assets/effects); without them it plays nothing and only ducks.
#pragma once

#include <map>
#include <string>
#include <vector>

namespace sc {

class Effects {
public:
    enum class Launch { NONE, EV, BOOM };

    Effects(int sr, const std::string& dir);
    bool loaded() const { return !clips_.empty(); }

    // once per block: pedal 0..1, whether reversing, the launch this sound makes
    void step(float dt, float speedKmh, float pedal, bool reversing, Launch launch);
    // a gearbox shift: the sound only when driven hard (pedal 55 % -> full at 85 %); duck: dip the main sound under an upshift
    void shift(bool up, float effort, bool duck);
    void chime() { fire("chime"); }
    // ducks l / r (the main sound) and mixes the effects in at volume
    void mix(float* l, float* r, int frames, float volume);
    float duck() const { return duck_; }

private:
    struct Clip { std::vector<float> l, r; double pos = -1; float level = 1; };
    void fire(const std::string& name, float level = 1.f);
    static float sample(const std::vector<float>& pcm, double pos);

    int sr_;
    std::map<std::string, Clip> clips_;
    float duck_ = 1, duckNow_ = 1, duckT_ = 0, reverseLevel_ = 0;
    bool reverse_ = false;
    float prevPedal_ = -1, pedalRate_ = 0, cooldown_ = 0, lastPedal_ = 1, buildLeft_ = 0;
};

// a 16-bit PCM WAV as left / right floats (mono: both the same); false if it isn't one
bool readWavStereo(const std::string& path, std::vector<float>& l, std::vector<float>& r, int* rate = nullptr);

}  // namespace sc
