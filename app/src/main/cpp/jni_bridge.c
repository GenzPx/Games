#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "thinair.h"

JNIEXPORT jlong JNICALL
Java_dev_hoshi_thinair_NativeSim_nativeCreate(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    TaState *s = (TaState *)calloc(1, sizeof(TaState));
    if (s) ta_state_init(s);
    return (jlong)s;
}

JNIEXPORT void JNICALL
Java_dev_hoshi_thinair_NativeSim_nativeDestroy(JNIEnv *env, jobject thiz, jlong ptr) {
    (void)env; (void)thiz;
    free((void *)ptr);
}

JNIEXPORT void JNICALL
Java_dev_hoshi_thinair_NativeSim_nativeTick(
    JNIEnv *env, jobject thiz, jlong ptr,
    jfloat dt, jfloat alt, jfloat speed, jfloat slope,
    jboolean climbing, jboolean resting, jfloat o2, jfloat wind,
    jfloat airTemp, jfloat wet, jfloat sheltered
) {
    (void)env; (void)thiz;
    TaState *s = (TaState *)ptr;
    if (!s) return;
    TaInput in;
    memset(&in, 0, sizeof(in));
    in.dt = dt;
    in.altitude_m = alt;
    in.speed_mps = speed;
    in.slope_deg = slope;
    in.climbing = climbing ? 1.0 : 0.0;
    in.resting = resting ? 1.0 : 0.0;
    in.o2_flow_lpm = o2;
    in.wind_mps = wind;
    in.air_temp_c = airTemp;
    in.wet = wet;
    in.sheltered = sheltered;
    ta_tick(s, &in);
}

JNIEXPORT jint JNICALL
Java_dev_hoshi_thinair_NativeSim_nativeFill(
    JNIEnv *env, jobject thiz, jlong ptr, jfloatArray out
) {
    (void)thiz;
    TaState *s = (TaState *)ptr;
    if (!s) return 0;
    jfloat buf[12];
    buf[0] = (jfloat)s->spo2;
    buf[1] = (jfloat)s->hr_bpm;
    buf[2] = (jfloat)s->stamina;
    buf[3] = (jfloat)s->core_c;
    buf[4] = (jfloat)s->move_scale;
    buf[5] = (jfloat)s->clarity;
    buf[6] = s->dead ? 1.f : 0.f;
    buf[7] = s->collapsed ? 1.f : 0.f;
    buf[8] = (jfloat)s->hape;
    buf[9] = (jfloat)s->hace;
    buf[10] = (jfloat)s->calories;
    buf[11] = (jfloat)s->water_l;
    (*env)->SetFloatArrayRegion(env, out, 0, 12, buf);
    return 12;
}

JNIEXPORT jstring JNICALL
Java_dev_hoshi_thinair_NativeSim_nativeCause(JNIEnv *env, jobject thiz, jlong ptr) {
    (void)thiz;
    TaState *s = (TaState *)ptr;
    const char *c = (s && s->cause) ? s->cause : "";
    return (*env)->NewStringUTF(env, c);
}
