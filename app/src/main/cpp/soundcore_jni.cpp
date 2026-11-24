// Open Blue's way into the shared native sound engine (soundcore/, copied from
// virtual-car-sound/soundcore by its sync_to_app.sh): the same engine the Raspberry Pi's player
// runs. Kotlin side: SoundCore.kt (its Native object, hence the _00024Native in the names).
// Every call comes from the audio thread, so there's no locking.
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "soundcore/engine.h"

namespace {
struct Handle {
    sc::Engine engine;
    std::vector<float> l, r, out;
    Handle(int sr, const std::string& fx) : engine(sr, fx) {}
};
Handle* H(jlong h) { return reinterpret_cast<Handle*>(h); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_create(JNIEnv* env, jobject, jint sampleRate, jstring effectsDir) {
    const char* dir = effectsDir ? env->GetStringUTFChars(effectsDir, nullptr) : nullptr;
    std::string fx = dir ? dir : "";
    if (dir) env->ReleaseStringUTFChars(effectsDir, dir);
    return reinterpret_cast<jlong>(new Handle(sampleRate, fx));
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_destroy(JNIEnv*, jobject, jlong h) {
    delete H(h);
}

// sound: 0 Flat-6, 1 Hypercar, 2 Turbofan, 3 Warp bubble (sc::Sound)
JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_setSound(JNIEnv*, jobject, jlong h, jint sound) {
    if (sound >= 0 && sound < 4) H(h)->engine.setSound(static_cast<sc::Sound>(sound));
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_setVolume(JNIEnv*, jobject, jlong h, jfloat v) {
    H(h)->engine.setVolume(v);
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_setGearshifts(JNIEnv*, jobject, jlong h, jboolean on) {
    H(h)->engine.setGearshifts(on);
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_setCruiseSettle(JNIEnv*, jobject, jlong h, jboolean on) {
    H(h)->engine.setCruiseSettle(on);
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_skipChime(JNIEnv*, jobject, jlong h) {
    H(h)->engine.skipChime();
}

// One block: the car's inputs in, `frames` stereo samples out (interleaved into `out`); the
// engine's state into `readout`: rpm, gear, load, limiter (1/0).
JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_render(JNIEnv* env, jobject, jlong h, jfloat pedal, jboolean hasPedal,
                                                      jfloat speedMs, jfloat accel, jint motorRpm, jboolean reversing,
                                                      jfloat yawRate, jfloat revThrottle, jfloatArray out, jint frames,
                                                      jfloatArray readout) {
    Handle& s = *H(h);
    if ((int)s.l.size() < frames) { s.l.resize(frames); s.r.resize(frames); s.out.resize(2 * frames); }
    sc::Inputs in;
    in.pedal = pedal;
    in.hasPedal = hasPedal;
    in.speedMs = speedMs;
    in.accel = accel;
    in.motorRpm = motorRpm;
    in.reversing = reversing;
    in.yawRate = yawRate;
    in.revThrottle = revThrottle;
    s.engine.render(in, s.l.data(), s.r.data(), frames);
    for (int n = 0; n < frames; ++n) { s.out[2 * n] = s.l[n]; s.out[2 * n + 1] = s.r[n]; }
    env->SetFloatArrayRegion(out, 0, 2 * frames, s.out.data());
    if (readout) {
        const sc::Readout& ro = s.engine.readout();
        float vals[4] = {ro.rpm, (float)ro.gear, ro.load, ro.limiter ? 1.f : 0.f};
        env->SetFloatArrayRegion(readout, 0, 4, vals);
    }
}

// The tire layer on its own, for the app's other sounds: adds into l / r in place.
JNIEXPORT jlong JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_tiresCreate(JNIEnv*, jobject, jint sampleRate) {
    return reinterpret_cast<jlong>(new sc::Tires(sampleRate));
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_tiresDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<sc::Tires*>(h);
}

JNIEXPORT void JNICALL
Java_io_github_alamo3_openblue_sound_SoundCore_00024Native_tiresRender(JNIEnv* env, jobject, jlong h, jfloatArray l, jfloatArray r,
                                                                       jint frames, jfloat lateral, jfloat speedMs, jfloat volume) {
    if (env->GetArrayLength(l) < frames || env->GetArrayLength(r) < frames) return;
    float* pl = static_cast<float*>(env->GetPrimitiveArrayCritical(l, nullptr));
    float* pr = static_cast<float*>(env->GetPrimitiveArrayCritical(r, nullptr));
    if (pl && pr) reinterpret_cast<sc::Tires*>(h)->render(pl, pr, frames, lateral, speedMs, volume);
    if (pr) env->ReleasePrimitiveArrayCritical(r, pr, 0);
    if (pl) env->ReleasePrimitiveArrayCritical(l, pl, 0);
}

}  // extern "C"
