#include "thinair.h"

#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static double clampf(double v, double lo, double hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
}

static double saturating(double v) {
    return clampf(v, 0.0, 1.0);
}

/* International Standard Atmosphere, valid to ~11 km */
double ta_pressure_hpa(double alt_m) {
    double x = 1.0 - 2.25577e-5 * alt_m;
    if (x < 0.05) x = 0.05;
    return 1013.25 * pow(x, 5.25588);
}

/* Inspired PO2 after subtracting water vapor at body temperature. */
double ta_pio2_mmhg(double alt_m) {
    double p_mmhg = ta_pressure_hpa(alt_m) * 0.750061683;
    double pio2 = 0.2094 * (p_mmhg - 47.0);
    return pio2 < 1.0 ? 1.0 : pio2;
}

/* Fitted to published recreational / expedition pulse-ox readings. */
double ta_expected_spo2(double alt_m, double acclimatization) {
    /* Unacclimatized curve: 98 @ 0, ~80 @ 5300, ~58 @ 8000, ~50 @ 8849 */
    double k = alt_m / 1000.0;
    double raw = 98.0 - 0.90 * k - 0.55 * k * k + 0.008 * k * k * k;
    /* Acclimatization recovers a few points below ~7500 m, none in death zone. */
    double room = clampf((7500.0 - alt_m) / 7500.0, 0.0, 1.0);
    raw += acclimatization * 7.5 * room;
    return clampf(raw, 38.0, 99.0);
}

double ta_air_temp_c(double alt_m, double hour, double weather) {
    /* Tropospheric lapse ~6.5 C/km from a 12 C valley morning. */
    double base = 12.0 - alt_m * 0.0065;
    double diurnal = 4.0 * sin((hour - 9.0) / 24.0 * 2.0 * M_PI);
    double storm = -12.0 * weather;
    return base + diurnal + storm;
}

double ta_wind_chill(double temp_c, double wind_mps) {
    double v = wind_mps * 3.6; /* km/h, Environment Canada formula */
    if (v < 5.0) return temp_c;
    return 13.12 + 0.6215 * temp_c - 11.37 * pow(v, 0.16) + 0.3965 * temp_c * pow(v, 0.16);
}

static double vo2max_at(double alt_m, double acclimatization) {
    /* ~10% loss / 1000 m above 1500, slightly mitigated by acclimatization. */
    double loss = 0.0;
    if (alt_m > 1500.0) {
        loss = (alt_m - 1500.0) / 1000.0 * 0.10;
    }
    loss *= (1.0 - 0.25 * acclimatization);
    return TA_VO2_SEA * clampf(1.0 - loss, 0.12, 1.0);
}

void ta_state_init(TaState *s) {
    memset(s, 0, sizeof(*s));
    s->spo2 = 96.0;
    s->hr_bpm = 72.0;
    s->rr_bpm = 14.0;
    s->vo2max = TA_VO2_SEA;
    s->stamina = 0.92;
    s->core_c = TA_BASE_CORE_C;
    s->skin_c = 33.0;
    s->calories = 2800.0;
    s->water_l = 2.4;
    s->acclimatization = 0.18;
    s->clarity = 1.0;
    s->cause = "";
}

void ta_tick(TaState *s, const TaInput *in) {
    if (s->dead) return;

    double dt = in->dt;
    if (dt <= 0.0 || dt > 1.0) dt = 1.0 / 60.0;

    s->pressure_hpa = ta_pressure_hpa(in->altitude_m);
    s->pio2_mmhg = ta_pio2_mmhg(in->altitude_m);
    s->vo2max = vo2max_at(in->altitude_m, s->acclimatization);

    double effort = 0.08;
    if (in->resting > 0.5) {
        effort = 0.04;
    } else {
        effort += in->speed_mps / 4.5;
        effort += clampf(in->slope_deg, 0.0, 55.0) / 80.0;
        effort += in->climbing * 0.28;
    }
    effort = clampf(effort, 0.04, 1.35);

    /* Supplemental O2 raises effective inspired pressure. */
    double o2_bonus = clampf(in->o2_flow_lpm / 4.0, 0.0, 1.0) * 18.0;

    double target_spo2 = ta_expected_spo2(in->altitude_m, s->acclimatization);
    target_spo2 += o2_bonus;
    target_spo2 -= effort * 9.0;
    target_spo2 -= s->hape * 16.0;
    target_spo2 = clampf(target_spo2, 35.0, 99.0);

    /* Blood gases lag — you don't desat instantly, nor recover instantly. */
    double spo2_tau = in->resting > 0.5 ? 8.0 : 4.5;
    s->spo2 += (target_spo2 - s->spo2) * (1.0 - exp(-dt / spo2_tau));

    double hr_rest = 64.0 + (98.0 - s->spo2) * 1.15;
    double hr_work = hr_rest + effort * 78.0;
    /* Max HR falls at extreme altitude. */
    double hr_max = 188.0 - (in->altitude_m > 3000.0 ? (in->altitude_m - 3000.0) / 130.0 : 0.0);
    double target_hr = clampf(hr_work, 52.0, hr_max);
    s->hr_bpm += (target_hr - s->hr_bpm) * (1.0 - exp(-dt / 3.0));

    double target_rr = 12.0 + (98.0 - s->spo2) * 0.55 + effort * 14.0;
    s->rr_bpm += (target_rr - s->rr_bpm) * (1.0 - exp(-dt / 2.4));

    /* Stamina */
    double drain = effort * 0.042 * (1.2 - s->spo2 / 120.0);
    if (in->altitude_m > TA_DEATH_ZONE_M) drain *= 1.65;
    if (in->resting > 0.5) {
        double rec = 0.035 * (s->spo2 / 95.0);
        s->stamina = clampf(s->stamina + rec * dt, 0.0, 1.0);
    } else {
        s->stamina = clampf(s->stamina - drain * dt, 0.0, 1.0);
    }

    /* Thermoregulation */
    double wc = ta_wind_chill(in->air_temp_c, in->wind_mps);
    double clothing = 0.78 - in->wet * 0.35 + in->sheltered * 0.25;
    double env_core = 36.7 + effort * 0.5 - clampf(8.0 - wc, 0.0, 42.0) * 0.085 * (1.15 - clothing);
    if (in->sheltered > 0.5) env_core = lerp(env_core, 36.6, 0.75);
    s->core_c += (env_core - s->core_c) * (1.0 - exp(-dt / 28.0));
    s->core_c = clampf(s->core_c, 26.0, 40.5);
    s->skin_c = lerp(s->core_c - 4.0, wc + 6.0, 0.45);

    if (s->skin_c < 4.0 && in->sheltered < 0.4) {
        s->frostbite = saturating(s->frostbite + dt * 0.004);
    }

    /* Fuel */
    s->calories -= (1.6 + effort * 6.5) * dt / 60.0; /* kcal/min */
    s->calories = clampf(s->calories, 0.0, 4000.0);
    s->water_l -= (0.00035 + effort * 0.0009) * dt;
    s->water_l = clampf(s->water_l, 0.0, 3.5);
    if (s->calories < 400.0) s->stamina *= 0.999;
    if (s->water_l < 0.4) s->clarity -= 0.01 * dt;

    /* Acclimatization: slow gain below death zone while not wrecked. */
    if (in->altitude_m > 2500.0 && in->altitude_m < TA_DEATH_ZONE_M && s->spo2 > 62.0) {
        s->acclimatization = saturating(s->acclimatization + dt * 0.00035);
    }
    if (in->altitude_m > TA_DEATH_ZONE_M) {
        s->death_zone_h += dt / 3600.0;
        s->acclimatization = saturating(s->acclimatization - dt * 0.0008);
    }

    /* HAPE / HACE — rapid ascent + low sats. */
    if (s->spo2 < TA_HAPE_SPO2_TRIGGER && in->altitude_m > 3500.0) {
        s->hape = saturating(s->hape + dt * 0.0035 * (1.0 - in->o2_flow_lpm / 6.0));
    } else {
        s->hape = saturating(s->hape - dt * 0.0012);
    }
    if (s->spo2 < 58.0 && in->altitude_m > 5000.0) {
        s->hace = saturating(s->hace + dt * 0.0028);
    } else if (in->altitude_m < 4500.0) {
        s->hace = saturating(s->hace - dt * 0.002);
    }

    double target_clarity = 1.0;
    target_clarity -= clampf((70.0 - s->spo2) / 40.0, 0.0, 0.7);
    target_clarity -= s->hace * 0.7;
    target_clarity -= clampf((35.0 - s->core_c) / 8.0, 0.0, 0.5);
    if (s->death_zone_h > 8.0) target_clarity -= 0.15;
    s->clarity += (clampf(target_clarity, 0.05, 1.0) - s->clarity) * (1.0 - exp(-dt / 6.0));

    /* Locomotion penalty — the "straw + treadmill" feel. */
    s->move_scale = 1.0;
    s->move_scale *= lerp(0.22, 1.0, saturating((s->spo2 - 48.0) / 40.0));
    s->move_scale *= lerp(0.35, 1.0, s->stamina);
    s->move_scale *= lerp(0.4, 1.0, s->clarity);
    if (s->core_c < 35.0) s->move_scale *= 0.7;
    if (in->altitude_m > TA_DEATH_ZONE_M) s->move_scale *= 0.72;
    s->move_scale = clampf(s->move_scale, 0.08, 1.0);

    s->collapsed = (s->spo2 < TA_COLLAPSE_SPO2) || (s->stamina < 0.02) || (s->clarity < 0.12);
    if (s->spo2 < 40.0) {
        s->dead = 1;
        s->cause = "hypoxia";
    } else if (s->hape > 0.92) {
        s->dead = 1;
        s->cause = "hape";
    } else if (s->hace > 0.92) {
        s->dead = 1;
        s->cause = "hace";
    } else if (s->core_c < TA_SEVERE_HYPO_C - 2.0) {
        s->dead = 1;
        s->cause = "hypothermia";
    } else if (s->death_zone_h > TA_DEATH_ZONE_HOURS) {
        s->dead = 1;
        s->cause = "death_zone";
    }
}
