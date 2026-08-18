/* Thin Air — physiology & weather core.
 * HoshiDev Expedition Systems
 *
 * This C library is the simulation source of truth.
 * Keep web/js/physiology.js numerically aligned.
 */
#pragma once

#include "ta_generated.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    double altitude_m;
    double speed_mps;
    double slope_deg;
    double climbing;      /* 0 or 1 */
    double resting;       /* 0..1 */
    double o2_flow_lpm;   /* supplemental */
    double wind_mps;
    double air_temp_c;
    double wet;           /* 0..1 clothing wetness */
    double sheltered;     /* 0..1 */
    double dt;
} TaInput;

typedef struct {
    double spo2;          /* % */
    double hr_bpm;
    double rr_bpm;        /* respiratory rate */
    double vo2max;        /* ml/kg/min remaining */
    double stamina;       /* 0..1 */
    double core_c;
    double skin_c;
    double calories;      /* kcal remaining */
    double water_l;
    double acclimatization; /* 0..1 */
    double hape;          /* 0..1 lung fluid */
    double hace;          /* 0..1 brain edema */
    double clarity;       /* 0..1 cognition */
    double frostbite;     /* 0..1 extremities */
    double death_zone_h;  /* hours accumulated above 8000 */
    double pressure_hpa;
    double pio2_mmhg;
    double move_scale;    /* 0..1 multiplier on locomotion */
    int    collapsed;
    int    dead;
    const char *cause;
} TaState;

void ta_state_init(TaState *s);
void ta_tick(TaState *s, const TaInput *in);

double ta_pressure_hpa(double alt_m);
double ta_air_temp_c(double alt_m, double hour, double weather);
double ta_wind_chill(double temp_c, double wind_mps);
double ta_expected_spo2(double alt_m, double acclimatization);

#ifdef __cplusplus
}
#endif
