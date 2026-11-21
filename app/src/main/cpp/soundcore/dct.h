// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
// The virtual 7-speed dual-clutch gearbox and its control unit (a port of Open Blue's Dct.kt):
// gear train from the car's speed (calibrated by its motor rpm), adaptive shift schedule,
// kickdown, fast-off hold, braking downshifts, rev limiter, launch clutch, rev flares, idle
// character. step() gives the EngineState every voice reads.
#pragma once

#include <random>

namespace sc {

struct EngineState {
    float rpm = 0;
    float load = 0;
    int gear = -1;              // 1..7 with gears, -1 direct drive, 0 revving in place
    bool engaged = false;
    int shifts = 0;             // counts upshifts
    bool limiter = false;
    int liftOffs = 0;           // counts lift-offs from a hard pull (pops)
    float speedKmh = 0;
    float overrun = 0;          // 0..1 engine braking off the pedal
    float shifting = 0;         // 0..1 through a shift's torque cut
    bool cut = false;           // the limiter's fuel cut is on right now
    int downshifts = 0;
    float throttle = 0;         // the (barely) smoothed pedal the gearbox read
};

class Dct {
public:
    static constexpr int GEARS = 7;
    static constexpr float RATIOS[GEARS] = {3.91f, 2.29f, 1.65f, 1.29f, 1.08f, 0.88f, 0.62f};
    static constexpr float EV_REDUCTION = 10.65f;
    static constexpr float MOTOR_RPM_PER_KMH = 77.f;
    static constexpr float RATIO_LEARN = 0.1f;
    static constexpr float GEAR1_TOP_KMH = 62.f;
    static constexpr float MIN_GEAR_SEC = 0.5f;
    static constexpr float FAST_OFF_HOLD_SEC = 2.5f;
    static constexpr float KICKDOWN_JUMP = 0.125f;
    static constexpr float BRAKE_DECEL = 2.5f;
    static constexpr float CLUTCH_CLOSE_SEC = 1.2f;
    static constexpr float INERTIA_SEC = 0.06f;
    static constexpr float TORQUE_SMOOTH_SEC = 0.04f;
    static constexpr float OVERRUN_KMH = 6.f;
    static constexpr float LIMITER_HZ = 12.f;
    static constexpr float LIMITER_PEDAL = 0.8f;
    static constexpr float LIMITER_HOLD_SEC = 0.5f;
    static constexpr float LIMITER_DIP = 0.04f;
    static constexpr float COLD_IDLE = 0.35f;
    static constexpr float COLD_IDLE_SEC = 20.f;
    static constexpr float IDLE_WANDER_RPM = 20.f;
    static constexpr float POWER_FLARE = 0.05f;
    static constexpr float LAUNCH_FLARE = 0.06f;

    Dct(float idle = 900.f, float redline = 7000.f) : idleRpm(idle), redline(redline), rpm_(idle) {}

    float idleRpm, redline;
    int gear() const { return gear_; }
    float sport() const { return sport_; }
    float motorRpmPerKmh() const { return motorPerKmh_; }
    float gearRpm(int g, float kmh) const;

    // one control step: dt seconds, road speed, pedal 0..1, the motor rpm the car last reported (0: unknown)
    EngineState step(float dt, float kmh, float pedalIn, int motorRpm = 0);

private:
    float finalDrive() const;
    float upRpm(float p) const;
    float lugRpm(float p) const;
    void beginShift(int to, float pedal);

    int gear_ = 1;
    float sport_ = 0;
    float motorPerKmh_ = MOTOR_RPM_PER_KMH;
    float rpm_;
    float pedalT_ = 0, pedalAvg_ = 0, lastPedal_ = 0, pedalRate_ = 0;
    float lastKmh_ = -1, accel_ = 0;
    int lastMotor_ = 0;
    float sinceShift_ = 1e9f, shiftT_ = 0, shiftLen_ = 0, shiftFromRpm_ = 0;
    bool shiftUp_ = true;
    float hold_ = 0;
    bool clutchLocked_ = false;
    float slipT_ = 0, limiterT_ = 0;
    int shifts_ = 0, downshifts_ = 0, liftOffs_ = 0;
    float pullPeak_ = 0, age_ = 0;
    std::mt19937 idleRnd_{7};
    float idleFrom_ = 0, idleTo_ = 0, idlePhase_ = 1;
};

// revving in place (the "Rev it" slider, outside the gearbox): from 95 % the revs sit on the
// limiter and bounce off it (as EngineModel.neutral)
class NeutralRev {
public:
    EngineState step(float dt, float throttle, float idle, float redline);
private:
    float rpm_ = 0, load_ = 0, limT_ = 0;
    bool engaged_ = false;
    int liftOffs_ = 0;
};

}  // namespace sc
