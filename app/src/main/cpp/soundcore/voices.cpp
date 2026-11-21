// Copied from virtual-car-sound/soundcore/src by sync_to_app.sh -- edit it there, not here.
#include "voices.h"

namespace sc {

std::unique_ptr<LayeredVoice> makeFlat6(int sr);
std::unique_ptr<LayeredVoice> makeHypercar(int sr);
std::unique_ptr<LayeredVoice> makeJet(int sr);
std::unique_ptr<LayeredVoice> makeWarp(int sr);

// name, idle, redline (the gearbox's rev range for the sound), engine?, gears?  -- as EngineSound
const SoundInfo& info(Sound s) {
    static const SoundInfo table[] = {
        {"Flat-6", 900.f, 8500.f, true, true},
        {"Hypercar", 1000.f, 9000.f, false, true},
        {"Turbofan", 1000.f, 7000.f, false, true},
        {"Warp bubble", 900.f, 6900.f, false, false},
    };
    return table[(int)s];
}

std::unique_ptr<LayeredVoice> makeVoice(Sound s, int sr) {
    switch (s) {
        case Sound::FLAT6: return makeFlat6(sr);
        case Sound::HYPERCAR: return makeHypercar(sr);
        case Sound::TURBOFAN: return makeJet(sr);
        case Sound::WARP: return makeWarp(sr);
    }
    return nullptr;
}

}  // namespace sc
