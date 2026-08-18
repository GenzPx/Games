/* JS port of engine/src/physiology.c — keep numerically aligned. */
import { WORLD } from "./generated.js";

const C = WORLD.constants;

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const lerp = (a, b, t) => a + (b - a) * t;
const sat = (v) => clamp(v, 0, 1);

export function pressureHpa(alt) {
  let x = 1 - 2.25577e-5 * alt;
  if (x < 0.05) x = 0.05;
  return 1013.25 * x ** 5.25588;
}

export function pio2Mmhg(alt) {
  const p = pressureHpa(alt) * 0.750061683;
  return Math.max(1, 0.2094 * (p - 47));
}

export function expectedSpo2(alt, accl) {
  const k = alt / 1000;
  let raw = 98 - 0.9 * k - 0.55 * k * k + 0.008 * k * k * k;
  const room = clamp((7500 - alt) / 7500, 0, 1);
  raw += accl * 7.5 * room;
  return clamp(raw, 38, 99);
}

export function airTempC(alt, hour, weather) {
  const base = 12 - alt * 0.0065;
  const diurnal = 4 * Math.sin(((hour - 9) / 24) * Math.PI * 2);
  return base + diurnal - 12 * weather;
}

export function windChill(tempC, windMps) {
  const v = windMps * 3.6;
  if (v < 5) return tempC;
  const p = v ** 0.16;
  return 13.12 + 0.6215 * tempC - 11.37 * p + 0.3965 * tempC * p;
}

function vo2maxAt(alt, accl) {
  let loss = alt > 1500 ? ((alt - 1500) / 1000) * 0.1 : 0;
  loss *= 1 - 0.25 * accl;
  return C.VO2_SEA * clamp(1 - loss, 0.12, 1);
}

export function createState() {
  return {
    spo2: 96,
    hr: 72,
    rr: 14,
    vo2max: C.VO2_SEA,
    stamina: 0.92,
    coreC: C.BASE_CORE_C,
    skinC: 33,
    calories: 2800,
    waterL: 2.4,
    acclimatization: 0.18,
    hape: 0,
    hace: 0,
    clarity: 1,
    frostbite: 0,
    deathZoneH: 0,
    pressure: 1013,
    pio2: 150,
    moveScale: 1,
    collapsed: false,
    dead: false,
    cause: "",
  };
}

export function tick(s, input) {
  if (s.dead) return s;
  let dt = input.dt;
  if (!(dt > 0) || dt > 1) dt = 1 / 60;

  const alt = input.altitude;
  s.pressure = pressureHpa(alt);
  s.pio2 = pio2Mmhg(alt);
  s.vo2max = vo2maxAt(alt, s.acclimatization);

  let effort = 0.08;
  if (input.resting > 0.5) effort = 0.04;
  else {
    effort += input.speed / 4.5;
    effort += clamp(input.slope, 0, 55) / 80;
    effort += (input.climbing ? 1 : 0) * 0.28;
  }
  effort = clamp(effort, 0.04, 1.35);

  const o2Bonus = clamp(input.o2Flow / 4, 0, 1) * 18;
  let targetSpo2 = expectedSpo2(alt, s.acclimatization) + o2Bonus - effort * 9 - s.hape * 16;
  targetSpo2 = clamp(targetSpo2, 35, 99);
  const spo2Tau = input.resting > 0.5 ? 8 : 4.5;
  s.spo2 += (targetSpo2 - s.spo2) * (1 - Math.exp(-dt / spo2Tau));

  const hrRest = 64 + (98 - s.spo2) * 1.15;
  const hrMax = 188 - (alt > 3000 ? (alt - 3000) / 130 : 0);
  const targetHr = clamp(hrRest + effort * 78, 52, hrMax);
  s.hr += (targetHr - s.hr) * (1 - Math.exp(-dt / 3));

  const targetRr = 12 + (98 - s.spo2) * 0.55 + effort * 14;
  s.rr += (targetRr - s.rr) * (1 - Math.exp(-dt / 2.4));

  if (input.resting > 0.5) {
    s.stamina = clamp(s.stamina + 0.035 * (s.spo2 / 95) * dt, 0, 1);
  } else {
    let drain = effort * 0.042 * (1.2 - s.spo2 / 120);
    if (alt > C.DEATH_ZONE_M) drain *= 1.65;
    s.stamina = clamp(s.stamina - drain * dt, 0, 1);
  }

  const wc = windChill(input.airTemp, input.wind);
  const clothing = 0.78 - input.wet * 0.35 + input.sheltered * 0.25;
  let envCore = 36.7 + effort * 0.5 - clamp(8 - wc, 0, 42) * 0.085 * (1.15 - clothing);
  if (input.sheltered > 0.5) envCore = lerp(envCore, 36.6, 0.75);
  s.coreC += (envCore - s.coreC) * (1 - Math.exp(-dt / 28));
  s.coreC = clamp(s.coreC, 26, 40.5);
  s.skinC = lerp(s.coreC - 4, wc + 6, 0.45);
  if (s.skinC < 4 && input.sheltered < 0.4) s.frostbite = sat(s.frostbite + dt * 0.004);

  s.calories = clamp(s.calories - ((1.6 + effort * 6.5) * dt) / 60, 0, 4000);
  s.waterL = clamp(s.waterL - (0.00035 + effort * 0.0009) * dt, 0, 3.5);
  if (s.calories < 400) s.stamina *= 0.999;
  if (s.waterL < 0.4) s.clarity -= 0.01 * dt;

  if (alt > 2500 && alt < C.DEATH_ZONE_M && s.spo2 > 62) {
    s.acclimatization = sat(s.acclimatization + dt * 0.00035);
  }
  if (alt > C.DEATH_ZONE_M) {
    s.deathZoneH += dt / 3600;
    s.acclimatization = sat(s.acclimatization - dt * 0.0008);
  }

  if (s.spo2 < C.HAPE_SPO2_TRIGGER && alt > 3500) {
    s.hape = sat(s.hape + dt * 0.0035 * (1 - input.o2Flow / 6));
  } else s.hape = sat(s.hape - dt * 0.0012);

  if (s.spo2 < 58 && alt > 5000) s.hace = sat(s.hace + dt * 0.0028);
  else if (alt < 4500) s.hace = sat(s.hace - dt * 0.002);

  let tCl = 1;
  tCl -= clamp((70 - s.spo2) / 40, 0, 0.7);
  tCl -= s.hace * 0.7;
  tCl -= clamp((35 - s.coreC) / 8, 0, 0.5);
  if (s.deathZoneH > 8) tCl -= 0.15;
  s.clarity += (clamp(tCl, 0.05, 1) - s.clarity) * (1 - Math.exp(-dt / 6));

  s.moveScale = 1;
  s.moveScale *= lerp(0.22, 1, sat((s.spo2 - 48) / 40));
  s.moveScale *= lerp(0.35, 1, s.stamina);
  s.moveScale *= lerp(0.4, 1, s.clarity);
  if (s.coreC < 35) s.moveScale *= 0.7;
  if (alt > C.DEATH_ZONE_M) s.moveScale *= 0.72;
  s.moveScale = clamp(s.moveScale, 0.08, 1);

  s.collapsed = s.spo2 < C.COLLAPSE_SPO2 || s.stamina < 0.02 || s.clarity < 0.12;
  if (s.spo2 < 40) {
    s.dead = true;
    s.cause = "hypoxia";
  } else if (s.hape > 0.92) {
    s.dead = true;
    s.cause = "hape";
  } else if (s.hace > 0.92) {
    s.dead = true;
    s.cause = "hace";
  } else if (s.coreC < C.SEVERE_HYPO_C - 2) {
    s.dead = true;
    s.cause = "hypothermia";
  } else if (s.deathZoneH > C.DEATH_ZONE_HOURS) {
    s.dead = true;
    s.cause = "death_zone";
  }
  return s;
}
