// JNI bridge to the FMOD Studio runtime (libfmod.so / libfmodstudio.so in jniLibs).
//
// FMOD's SDK headers may not be redistributed, so this declares just the few C API functions
// it uses itself (signatures as documented at fmod.com/docs, FMOD Engine 2.03). Handles are
// passed to Kotlin as opaque longs. See FmodNative.kt for the Kotlin side.
//
// FMOD Studio by Firelight Technologies Pty Ltd.

#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>

#define TAG "OpenBlueFmod"

extern "C" {
typedef int FMOD_RESULT;
typedef int FMOD_BOOL;
typedef struct FMOD_SYSTEM FMOD_SYSTEM;
typedef struct FMOD_CHANNELGROUP FMOD_CHANNELGROUP;
typedef struct FMOD_DSP FMOD_DSP;
typedef struct FMOD_STUDIO_SYSTEM FMOD_STUDIO_SYSTEM;
typedef struct FMOD_STUDIO_BANK FMOD_STUDIO_BANK;
typedef struct FMOD_STUDIO_BUS FMOD_STUDIO_BUS;
typedef struct FMOD_STUDIO_EVENTDESCRIPTION FMOD_STUDIO_EVENTDESCRIPTION;
typedef struct FMOD_STUDIO_EVENTINSTANCE FMOD_STUDIO_EVENTINSTANCE;
typedef struct {
    unsigned int Data1;
    unsigned short Data2, Data3;
    unsigned char Data4[8];
} FMOD_GUID;

FMOD_RESULT FMOD_Studio_System_Create(FMOD_STUDIO_SYSTEM **system, unsigned int headerversion);
FMOD_RESULT FMOD_Studio_System_GetCoreSystem(FMOD_STUDIO_SYSTEM *system, FMOD_SYSTEM **core);
FMOD_RESULT FMOD_Studio_System_Initialize(FMOD_STUDIO_SYSTEM *system, int maxchannels,
                                          unsigned int studioflags, unsigned int flags, void *extra);
FMOD_RESULT FMOD_Studio_System_Update(FMOD_STUDIO_SYSTEM *system);
FMOD_RESULT FMOD_Studio_System_Release(FMOD_STUDIO_SYSTEM *system);
FMOD_RESULT FMOD_Studio_System_LoadBankFile(FMOD_STUDIO_SYSTEM *system, const char *filename,
                                            unsigned int flags, FMOD_STUDIO_BANK **bank);
FMOD_RESULT FMOD_Studio_System_GetEventByID(FMOD_STUDIO_SYSTEM *system, const FMOD_GUID *id,
                                            FMOD_STUDIO_EVENTDESCRIPTION **event);
FMOD_RESULT FMOD_Studio_System_GetBusByID(FMOD_STUDIO_SYSTEM *system, const FMOD_GUID *id,
                                          FMOD_STUDIO_BUS **bus);
FMOD_RESULT FMOD_Studio_Bus_SetVolume(FMOD_STUDIO_BUS *bus, float volume);
FMOD_RESULT FMOD_Studio_EventDescription_CreateInstance(FMOD_STUDIO_EVENTDESCRIPTION *event,
                                                        FMOD_STUDIO_EVENTINSTANCE **instance);
FMOD_RESULT FMOD_Studio_EventInstance_Start(FMOD_STUDIO_EVENTINSTANCE *instance);
FMOD_RESULT FMOD_Studio_EventInstance_Stop(FMOD_STUDIO_EVENTINSTANCE *instance, int mode);
FMOD_RESULT FMOD_Studio_EventInstance_Release(FMOD_STUDIO_EVENTINSTANCE *instance);
FMOD_RESULT FMOD_Studio_EventInstance_SetParameterByName(FMOD_STUDIO_EVENTINSTANCE *instance,
                                                         const char *name, float value,
                                                         FMOD_BOOL ignoreseekspeed);
FMOD_RESULT FMOD_System_SetDSPBufferSize(FMOD_SYSTEM *system, unsigned int bufferlength, int numbuffers);
FMOD_RESULT FMOD_System_CreateDSPByType(FMOD_SYSTEM *system, int type, FMOD_DSP **dsp);
FMOD_RESULT FMOD_System_GetMasterChannelGroup(FMOD_SYSTEM *system, FMOD_CHANNELGROUP **group);
FMOD_RESULT FMOD_ChannelGroup_AddDSP(FMOD_CHANNELGROUP *group, int index, FMOD_DSP *dsp);
FMOD_RESULT FMOD_ChannelGroup_SetVolume(FMOD_CHANNELGROUP *group, float volume);
FMOD_RESULT FMOD_DSP_SetParameterFloat(FMOD_DSP *dsp, int index, float value);
}

namespace {
constexpr unsigned int FMOD_VERSION = 0x00020315;
constexpr int DSP_TYPE_LIMITER = 11;          // FMOD_DSP_TYPE_LIMITER
constexpr int LIMITER_RELEASETIME = 0;        // ms
constexpr int LIMITER_CEILING = 1;            // dB
constexpr int STOP_ALLOWFADEOUT = 0;
constexpr int STOP_IMMEDIATE = 1;

bool ok(FMOD_RESULT r, const char *what) {
    if (r != 0) __android_log_print(ANDROID_LOG_WARN, TAG, "%s failed: FMOD error %d", what, r);
    return r == 0;
}

bool parseGuid(JNIEnv *env, jstring s, FMOD_GUID *g) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    unsigned int d[11];
    int n = std::sscanf(c, "{%8x-%4x-%4x-%2x%2x-%2x%2x%2x%2x%2x%2x}",
                        &d[0], &d[1], &d[2], &d[3], &d[4], &d[5], &d[6], &d[7], &d[8], &d[9], &d[10]);
    env->ReleaseStringUTFChars(s, c);
    if (n != 11) return false;
    g->Data1 = d[0];
    g->Data2 = (unsigned short) d[1];
    g->Data3 = (unsigned short) d[2];
    for (int i = 0; i < 8; i++) g->Data4[i] = (unsigned char) d[3 + i];
    return true;
}

template <typename T> T *ptr(jlong h) { return reinterpret_cast<T *>(h); }
}  // namespace

#define JNI(name) extern "C" JNIEXPORT
#define FN(name) Java_io_github_alamo3_openblue_sound_FmodNative_##name

// Creates and starts the Studio system: low-latency mix blocks, a limiter on the master.
// Returns the system handle, 0 on failure.
JNI() jlong JNICALL FN(create)(JNIEnv *, jobject, jint blockFrames) {
    FMOD_STUDIO_SYSTEM *sys = nullptr;
    if (!ok(FMOD_Studio_System_Create(&sys, FMOD_VERSION), "create")) return 0;
    FMOD_SYSTEM *core = nullptr;
    FMOD_Studio_System_GetCoreSystem(sys, &core);
    ok(FMOD_System_SetDSPBufferSize(core, (unsigned int) blockFrames, 4), "dsp buffer");
    if (!ok(FMOD_Studio_System_Initialize(sys, 64, 0, 0, nullptr), "initialize")) {
        FMOD_Studio_System_Release(sys);
        return 0;
    }
    FMOD_CHANNELGROUP *master = nullptr;
    FMOD_DSP *limiter = nullptr;
    if (ok(FMOD_System_GetMasterChannelGroup(core, &master), "master group") &&
        ok(FMOD_System_CreateDSPByType(core, DSP_TYPE_LIMITER, &limiter), "limiter")) {
        FMOD_DSP_SetParameterFloat(limiter, LIMITER_CEILING, -0.5f);
        FMOD_DSP_SetParameterFloat(limiter, LIMITER_RELEASETIME, 150.0f);
        ok(FMOD_ChannelGroup_AddDSP(master, 0, limiter), "add limiter");
    }
    return reinterpret_cast<jlong>(sys);
}

JNI() void JNICALL FN(release)(JNIEnv *, jobject, jlong sys) {
    if (sys) FMOD_Studio_System_Release(ptr<FMOD_STUDIO_SYSTEM>(sys));
}

JNI() jboolean JNICALL FN(update)(JNIEnv *, jobject, jlong sys) {
    return FMOD_Studio_System_Update(ptr<FMOD_STUDIO_SYSTEM>(sys)) == 0;
}

JNI() jlong JNICALL FN(loadBank)(JNIEnv *env, jobject, jlong sys, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    FMOD_STUDIO_BANK *bank = nullptr;
    bool good = ok(FMOD_Studio_System_LoadBankFile(ptr<FMOD_STUDIO_SYSTEM>(sys), p, 0, &bank), p);
    env->ReleaseStringUTFChars(path, p);
    return good ? reinterpret_cast<jlong>(bank) : 0;
}

JNI() jlong JNICALL FN(event)(JNIEnv *env, jobject, jlong sys, jstring guid) {
    FMOD_GUID g;
    FMOD_STUDIO_EVENTDESCRIPTION *d = nullptr;
    if (!parseGuid(env, guid, &g)) return 0;
    return ok(FMOD_Studio_System_GetEventByID(ptr<FMOD_STUDIO_SYSTEM>(sys), &g, &d), "event")
           ? reinterpret_cast<jlong>(d) : 0;
}

JNI() jlong JNICALL FN(instance)(JNIEnv *, jobject, jlong desc) {
    FMOD_STUDIO_EVENTINSTANCE *i = nullptr;
    return ok(FMOD_Studio_EventDescription_CreateInstance(ptr<FMOD_STUDIO_EVENTDESCRIPTION>(desc), &i),
              "instance") ? reinterpret_cast<jlong>(i) : 0;
}

JNI() void JNICALL FN(start)(JNIEnv *, jobject, jlong inst) {
    ok(FMOD_Studio_EventInstance_Start(ptr<FMOD_STUDIO_EVENTINSTANCE>(inst)), "start");
}

JNI() void JNICALL FN(stop)(JNIEnv *, jobject, jlong inst, jboolean immediate) {
    FMOD_Studio_EventInstance_Stop(ptr<FMOD_STUDIO_EVENTINSTANCE>(inst),
                                   immediate ? STOP_IMMEDIATE : STOP_ALLOWFADEOUT);
}

JNI() void JNICALL FN(releaseInstance)(JNIEnv *, jobject, jlong inst) {
    FMOD_Studio_EventInstance_Release(ptr<FMOD_STUDIO_EVENTINSTANCE>(inst));
}

JNI() void JNICALL FN(setParam)(JNIEnv *env, jobject, jlong inst, jstring name, jfloat value) {
    const char *n = env->GetStringUTFChars(name, nullptr);
    FMOD_Studio_EventInstance_SetParameterByName(ptr<FMOD_STUDIO_EVENTINSTANCE>(inst), n, value, 0);
    env->ReleaseStringUTFChars(name, n);
}

// Fire and forget: a new instance of [desc] with one parameter set, released once it ends.
JNI() void JNICALL FN(oneShot)(JNIEnv *env, jobject, jlong desc, jstring name, jfloat value) {
    FMOD_STUDIO_EVENTINSTANCE *i = nullptr;
    if (!ok(FMOD_Studio_EventDescription_CreateInstance(ptr<FMOD_STUDIO_EVENTDESCRIPTION>(desc), &i),
            "one-shot")) return;
    if (name) {
        const char *n = env->GetStringUTFChars(name, nullptr);
        FMOD_Studio_EventInstance_SetParameterByName(i, n, value, 0);
        env->ReleaseStringUTFChars(name, n);
    }
    FMOD_Studio_EventInstance_Start(i);
    FMOD_Studio_EventInstance_Release(i);
}

JNI() void JNICALL FN(setBusVolume)(JNIEnv *env, jobject, jlong sys, jstring guid, jfloat volume) {
    FMOD_GUID g;
    FMOD_STUDIO_BUS *bus = nullptr;
    if (parseGuid(env, guid, &g) && FMOD_Studio_System_GetBusByID(ptr<FMOD_STUDIO_SYSTEM>(sys), &g, &bus) == 0)
        FMOD_Studio_Bus_SetVolume(bus, volume);
}

// Overall output level (volume, fades and ducking), applied on the core master group.
JNI() void JNICALL FN(setMasterVolume)(JNIEnv *, jobject, jlong sys, jfloat volume) {
    FMOD_SYSTEM *core = nullptr;
    FMOD_CHANNELGROUP *master = nullptr;
    if (FMOD_Studio_System_GetCoreSystem(ptr<FMOD_STUDIO_SYSTEM>(sys), &core) == 0 &&
        FMOD_System_GetMasterChannelGroup(core, &master) == 0)
        FMOD_ChannelGroup_SetVolume(master, volume);
}
