/*
 * Stand-ins for effect plugins that FMOD Studio 1.x shipped and FMOD 2.x no longer has.
 *
 * Banks built with FMOD Studio 1.08 (e.g. Assetto Corsa car mods) reference these by name.
 * FMOD 2.x on desktop skips a missing plugin, but on Android it refuses to load the whole
 * bank (FMOD_ERR_PLUGIN_RESOURCE, "DSP plugin returned an error"). Registering a plugin with
 * the same name and the same parameter list (count and types, as the bank stores them) lets
 * the bank load:
 *
 *   FMOD Distance Filter   float, float, data(3D attributes)   passes audio through: it only
 *                                                              muffles with distance, and the
 *                                                              listener is in the car
 *   FMOD Gain              float (dB), bool                    applies the gain
 *   FMOD Lowpass Simple    float (cutoff Hz)                   two one-pole stages in series
 *
 * The plugin structures are declared here to match the FMOD 2.03 plugin API (fmod_dsp.h,
 * plugin SDK version 110); FMOD's own headers may not be redistributed. Plain C, so the
 * on-device test harness can use it too.
 */
#ifndef OPENBLUE_FMOD_LEGACY_PLUGINS_H
#define OPENBLUE_FMOD_LEGACY_PLUGINS_H

#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct { int type; struct { int numpoints; float *v; float *p; } pw; } LpMapping;
typedef struct { float min, max, defaultval; LpMapping mapping; } LpFloatDesc;
typedef struct { int defaultval; const char *valuenames; } LpBoolDesc;
typedef struct {
    int type;                       /* 0 float, 1 int, 2 bool, 3 data */
    char name[16];
    char label[16];
    const char *description;
    union { LpFloatDesc floatdesc; LpBoolDesc booldesc; int datatype; char pad[64]; };
} LpParamDesc;
typedef struct {
    unsigned int pluginsdkversion;
    char name[32];
    unsigned int version;
    int numinputbuffers, numoutputbuffers;
    void *create, *release, *reset, *read, *process, *setposition;
    int numparameters;
    LpParamDesc **paramdesc;
    void *setparameterfloat, *setparameterint, *setparameterbool, *setparameterdata;
    void *getparameterfloat, *getparameterint, *getparameterbool, *getparameterdata;
    void *shouldiprocess;
    void *userdata;
    void *sys_register, *sys_deregister, *sys_mix;
} LpDspDesc;
typedef struct { void *alloc, *realloc, *free; int (*getsamplerate)(void *state, int *rate); } LpStateFunctions;
typedef struct {
    void *instance;
    void *plugindata;
    unsigned int channelmask;
    int source_speakermode;
    float *sidechaindata;
    int sidechainchannels;
    LpStateFunctions *functions;
    int systemobject;
} LpDspState;

#define LP_MAX_CH 16
typedef struct {
    float p[4];                     /* float parameters */
    int b[4];                       /* bool parameters */
    float z1[LP_MAX_CH], z2[LP_MAX_CH];   /* filter state per channel */
    int rate;
} LpInstance;

static LpInstance *lp_inst(void *st) { return (LpInstance *) ((LpDspState *) st)->plugindata; }

static int lp_create(void *st) {
    LpInstance *i = (LpInstance *) calloc(1, sizeof(LpInstance));
    if (!i) return 1;
    LpDspState *s = (LpDspState *) st;
    i->rate = 48000;
    if (s->functions && s->functions->getsamplerate) s->functions->getsamplerate(st, &i->rate);
    i->p[0] = 22000.0f;             /* lowpass cutoff: open; gain: 0 dB is p[0] = 0 below */
    s->plugindata = i;
    return 0;
}
static int lp_create_gain(void *st) { int r = lp_create(st); if (!r) lp_inst(st)->p[0] = 0.0f; return r; }
static int lp_release(void *st) { free(((LpDspState *) st)->plugindata); return 0; }
static int lp_reset(void *st) {
    LpInstance *i = lp_inst(st);
    memset(i->z1, 0, sizeof i->z1);
    memset(i->z2, 0, sizeof i->z2);
    return 0;
}

static int lp_setf(void *st, int idx, float v) { if (idx >= 0 && idx < 4) lp_inst(st)->p[idx] = v; return 0; }
static int lp_getf(void *st, int idx, float *v, char *s) {
    if (v) *v = (idx >= 0 && idx < 4) ? lp_inst(st)->p[idx] : 0.0f;
    if (s) s[0] = 0;
    return 0;
}
static int lp_setb(void *st, int idx, int v) { if (idx >= 0 && idx < 4) lp_inst(st)->b[idx] = v; return 0; }
static int lp_getb(void *st, int idx, int *v, char *s) {
    if (v) *v = (idx >= 0 && idx < 4) ? lp_inst(st)->b[idx] : 0;
    if (s) s[0] = 0;
    return 0;
}
static int lp_seti(void *st, int idx, int v) { return 0; }
static int lp_geti(void *st, int idx, int *v, char *s) { if (v) *v = 0; if (s) s[0] = 0; return 0; }
static char lp_data_buf[64];
static int lp_setd(void *st, int idx, void *data, unsigned int len) { return 0; }
static int lp_getd(void *st, int idx, void **data, unsigned int *len, char *s) {
    if (data) *data = lp_data_buf;
    if (len) *len = 0;
    if (s) s[0] = 0;
    return 0;
}

static int lp_read_through(void *st, float *in, float *out, unsigned int len, int inch, int *outch) {
    memcpy(out, in, sizeof(float) * len * (unsigned) inch);
    *outch = inch;
    return 0;
}
static int lp_read_gain(void *st, float *in, float *out, unsigned int len, int inch, int *outch) {
    float g = powf(10.0f, lp_inst(st)->p[0] / 20.0f);
    unsigned int n = len * (unsigned) inch;
    for (unsigned int k = 0; k < n; k++) out[k] = in[k] * g;
    *outch = inch;
    return 0;
}
static int lp_read_lowpass(void *st, float *in, float *out, unsigned int len, int inch, int *outch) {
    LpInstance *i = lp_inst(st);
    float fc = i->p[0];
    float nyq = 0.5f * (float) i->rate;
    if (fc >= nyq * 0.95f) return lp_read_through(st, in, out, len, inch, outch);
    if (fc < 10.0f) fc = 10.0f;
    float a = 1.0f - expf(-2.0f * 3.14159265f * fc / (float) i->rate);   /* one-pole coefficient */
    int ch = inch < LP_MAX_CH ? inch : LP_MAX_CH;
    for (unsigned int k = 0; k < len; k++) {
        for (int c = 0; c < inch; c++) {
            float x = in[k * inch + c];
            if (c < ch) {
                i->z1[c] += a * (x - i->z1[c]);
                i->z2[c] += a * (i->z1[c] - i->z2[c]);
                x = i->z2[c];
            }
            out[k * inch + c] = x;
        }
    }
    *outch = inch;
    return 0;
}

static LpParamDesc lp_pf0, lp_pf1, lp_pdata, lp_pbool, lp_pgain, lp_pcut;
static LpParamDesc *lp_distance_params[3], *lp_gain_params[2], *lp_lowpass_params[1];
static LpDspDesc lp_distance, lp_gain, lp_lowpass;

static void lp_float(LpParamDesc *p, const char *name, float lo, float hi, float dflt) {
    memset(p, 0, sizeof *p);
    p->type = 0;
    strncpy(p->name, name, sizeof p->name - 1);
    p->description = "";
    p->floatdesc.min = lo;
    p->floatdesc.max = hi;
    p->floatdesc.defaultval = dflt;
}

static void lp_base(LpDspDesc *d, const char *name, void *create, void *read, int n, LpParamDesc **params) {
    memset(d, 0, sizeof *d);
    d->pluginsdkversion = 110;
    strncpy(d->name, name, sizeof d->name - 1);
    d->version = 0x00010000;
    d->numinputbuffers = 1;
    d->numoutputbuffers = 1;
    d->create = create;
    d->release = (void *) lp_release;
    d->reset = (void *) lp_reset;
    d->read = read;
    d->numparameters = n;
    d->paramdesc = params;
    d->setparameterfloat = (void *) lp_setf;
    d->getparameterfloat = (void *) lp_getf;
    d->setparameterint = (void *) lp_seti;
    d->getparameterint = (void *) lp_geti;
    d->setparameterbool = (void *) lp_setb;
    d->getparameterbool = (void *) lp_getb;
    d->setparameterdata = (void *) lp_setd;
    d->getparameterdata = (void *) lp_getd;
}

/* The plugin descriptions to register (FMOD_System_RegisterDSP), [count] of them. */
static LpDspDesc **legacy_plugins(int *count) {
    static LpDspDesc *all[3];
    lp_float(&lp_pf0, "Cutoff", 0.0f, 100000.0f, 1500.0f);
    lp_float(&lp_pf1, "Max Distance", 0.0f, 100000.0f, 1000.0f);
    memset(&lp_pdata, 0, sizeof lp_pdata);
    lp_pdata.type = 3;              /* data */
    strcpy(lp_pdata.name, "3D Attributes");
    lp_pdata.description = "";
    lp_pdata.datatype = -2;         /* FMOD_DSP_PARAMETER_DATA_TYPE_3DATTRIBUTES */
    lp_distance_params[0] = &lp_pf0;
    lp_distance_params[1] = &lp_pf1;
    lp_distance_params[2] = &lp_pdata;
    lp_base(&lp_distance, "FMOD Distance Filter", (void *) lp_create, (void *) lp_read_through, 3, lp_distance_params);

    lp_float(&lp_pgain, "Gain", -80.0f, 30.0f, 0.0f);
    memset(&lp_pbool, 0, sizeof lp_pbool);
    lp_pbool.type = 2;              /* bool */
    strcpy(lp_pbool.name, "Flag");
    lp_pbool.description = "";
    lp_gain_params[0] = &lp_pgain;
    lp_gain_params[1] = &lp_pbool;
    lp_base(&lp_gain, "FMOD Gain", (void *) lp_create_gain, (void *) lp_read_gain, 2, lp_gain_params);

    lp_float(&lp_pcut, "Cutoff", 10.0f, 22000.0f, 22000.0f);
    lp_lowpass_params[0] = &lp_pcut;
    lp_base(&lp_lowpass, "FMOD Lowpass Simple", (void *) lp_create, (void *) lp_read_lowpass, 1, lp_lowpass_params);

    all[0] = &lp_distance;
    all[1] = &lp_gain;
    all[2] = &lp_lowpass;
    *count = 3;
    return all;
}

#endif
