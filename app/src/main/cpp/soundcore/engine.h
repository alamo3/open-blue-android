// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// The sound engine: everything between the car's data and the speaker, shared by every host
// (the Pi's player, Open Blue through JNI, the offline renderer). Per block: the car's inputs in,
// stereo out. Inside: the gearbox (or revving in place), hill effort, the chosen layered voice,
// the drive effects, the limiter stutter, a speed-aware mix, the cornering lean, and a
// look-ahead limiter. A test drive can stand in for the car.
#pragma once

#include <memory>
#include <string>

#include "dct.h"
#include "dsp.h"
#include "effects.h"
#include "voices.h"

namespace sc {

struct Inputs {
    float pedal = 0;            // accelerator 0..1 (the car's own)
    bool hasPedal = true;       // false: only the phone's estimate (accel stands in)
    float speedMs = 0;          // road speed, m/s
    float accel = 0;            // forward acceleration, m/s^2
    int motorRpm = 0;           // the car's motor rpm (0: unknown)
    bool reversing = false;
    float yawRate = 0;          // rad/s, + = turning left (for the cornering lean)
    float revThrottle = -1;     // >= 0: revving in place (the "Rev it" slider), the gearbox set aside
};

struct Readout {
    float rpm = 0, kmh = 0, load = 0;
    int gear = 0;
    bool limiter = false;
    const char* testDrive = nullptr;   // the test drive's stage while one runs
};

// how hard the car is working beyond the pedal: uphill it pulls less for the same pedal (port of Effort.kt)
class Effort {
public:
    float step(float dt, float pedal, float speedMs, float accel);
    float strain() const { return strain_; }
    static float expectedPush(float pedal, float speedMs);
    static float drag(float speedMs);
private:
    float strain_ = 0, usual_ = 1, now_ = 1, learned_ = 0;
};

// a scripted drive for trying a sound while parked (port of TestDrive.kt)
class TestDrive {
public:
    bool step(float dt);                 // false once it's over
    float speed = 0, pedal = 0, accel = 0, yawRate = 0;
    float kmh() const { return speed * 3.6f; }
    int motorRpm() const { return (int)(kmh() * 77.5f); }
    const char* stage() const;
private:
    int stage_ = 0;                      // idle, pulling away, hard to 100, off the pedal, braking, idle, done
    float inStage_ = 0;
};

class Engine {
public:
    Engine(int sampleRate, const std::string& effectsDir = "");
    ~Engine();

    void setSound(Sound s);
    Sound sound() const { return sound_; }
    void setVolume(float v) { volume_ = v; }
    void setGearshifts(bool on) { gearshifts_ = on; }
    void setActive(bool on) { active_ = on; }      // fades in / out (no clicks)
    void skipChime() { chimed_ = true; }           // e.g. a host that already chimed, or switched sound mid-drive
    void startTestDrive();
    void stopTestDrive();
    bool testDriving() const { return testDrive_ != nullptr; }

    // one block: renders frames stereo samples into l / r
    void render(const Inputs& in, float* l, float* r, int frames);
    const Readout& readout() const { return readout_; }

private:
    void speedMix(float* l, float* r, int frames, float kmh);
    void lean(float* l, float* r, int frames, float lateral);
    void limit(float* l, float* r, int frames);

    int sr_;
    Sound sound_ = Sound::FLAT6;
    std::unique_ptr<LayeredVoice> voices_[4];
    Effects effects_;
    std::unique_ptr<Dct> dct_;
    NeutralRev neutral_;
    Effort effort_;
    std::unique_ptr<TestDrive> testDrive_;
    float volume_ = 0.8f, fade_ = 0;
    bool gearshifts_ = true, active_ = true, chimed_ = false;
    int shiftsSeen_ = -1, downSeen_ = -1;
    float directLoad_ = 0, directRpm_ = 0;
    float cutGain_ = 1, speedMix_ = 0, lean_ = 0;
    Biq tiltL_, tiltR_;
    // look-ahead limiter
    int la_;
    std::unique_ptr<float[]> delayL_, delayR_, minVal_, avgBuf_;
    std::unique_ptr<long long[]> minIdx_;
    int delayPos_ = 0, minHead_ = 0, minCount_ = 0, avgPos_ = 0;
    double avgSum_;
    long long sampleIdx_ = 0;
    float limitGain_ = 1;
    Readout readout_;
};

}  // namespace sc
