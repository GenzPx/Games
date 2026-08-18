#include "thinair.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void print_state(const TaState *s, double alt) {
    printf("alt=%.0f spo2=%.1f hr=%.0f rr=%.0f stam=%.2f core=%.1f "
           "vo2=%.1f clar=%.2f hape=%.2f hace=%.2f p=%.1f move=%.2f\n",
           alt, s->spo2, s->hr_bpm, s->rr_bpm, s->stamina, s->core_c,
           s->vo2max, s->clarity, s->hape, s->hace, s->pressure_hpa, s->move_scale);
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "profile";
    TaState s;
    ta_state_init(&s);

    if (strcmp(mode, "profile") == 0) {
        printf("# Thin Air physiology profile (rest, unacclimatized→partial)\n");
        double alts[] = {0, 2500, 3500, 5300, 6500, 7300, 8000, 8849};
        for (unsigned i = 0; i < sizeof(alts) / sizeof(alts[0]); i++) {
            ta_state_init(&s);
            s.acclimatization = alts[i] >= 5000.0 ? 0.35 : 0.1;
            TaInput in;
            memset(&in, 0, sizeof(in));
            in.altitude_m = alts[i];
            in.air_temp_c = ta_air_temp_c(alts[i], 6.0, 0.2);
            in.wind_mps = 4.0 + alts[i] / 2000.0;
            in.dt = 1.0;
            in.resting = 1.0;
            /* settle 20 minutes */
            for (int k = 0; k < 1200; k++) ta_tick(&s, &in);
            print_state(&s, alts[i]);
        }
        return 0;
    }

    if (strcmp(mode, "json") == 0) {
        printf("{\n");
        printf("  \"pressure_sea\": %.4f,\n", ta_pressure_hpa(0));
        printf("  \"pressure_summit\": %.4f,\n", ta_pressure_hpa(8849));
        printf("  \"spo2_bc\": %.3f,\n", ta_expected_spo2(5364, 0.4));
        printf("  \"spo2_summit\": %.3f\n", ta_expected_spo2(8849, 0.4));
        printf("}\n");
        return 0;
    }

    fprintf(stderr, "usage: thinair-sim [profile|json]\n");
    return 1;
}
