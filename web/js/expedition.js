/* Port of game/src/commonMain/kotlin/dev/hoshi/thinair/Expedition.kt */
import { WORLD } from "./generated.js";

export const ENDING = {
  NONE: "NONE",
  SUMMIT_AND_HOME: "SUMMIT_AND_HOME",
  SUMMIT_DIED_DESCENT: "SUMMIT_DIED_DESCENT",
  TURNED_AROUND: "TURNED_AROUND",
  HYPOXIA: "HYPOXIA",
  HAPE: "HAPE",
  HACE: "HACE",
  HYPOTHERMIA: "HYPOTHERMIA",
  FALL: "FALL",
  STORM: "STORM",
  DEATH_ZONE: "DEATH_ZONE",
  COLLAPSE: "COLLAPSE",
};

export function createExpedition() {
  return {
    hour: 4.55,
    day: 1,
    headingUp: true,
    summited: false,
    turnaroundHour: 13.0,
    weather: 0.18,
    o2Bottles: 2,
    o2Active: false,
    o2Remaining: 0,
    food: 4,
    water: 3,
    radioBattery: 1,
    ending: ENDING.NONE,
    log: [],
  };
}

export function nearestCamp(alt) {
  const camps = WORLD.camps;
  let best = 0;
  let bestD = Infinity;
  for (let i = 0; i < camps.length; i++) {
    const d = Math.abs(camps[i].alt - alt);
    if (d < bestD) {
      bestD = d;
      best = i;
    }
  }
  return { camp: camps[best], dist: bestD, index: best };
}

export function tickClock(ex, dt) {
  ex.hour += dt / 3600;
  if (ex.hour >= 24) {
    ex.hour -= 24;
    ex.day += 1;
  }
}

export function tickWeather(ex, dt, rng) {
  const dtH = dt / 3600;
  ex.weather = Math.min(1, Math.max(0, ex.weather + (rng - 0.48) * 0.35 * dtH));
  if (ex.hour >= 12 && ex.hour <= 18) ex.weather = Math.min(1, ex.weather + 0.04 * dtH);
}

export function shouldTurnAround(ex, spo2, stamina) {
  return ex.hour >= ex.turnaroundHour || spo2 < 52 || stamina < 0.18;
}

export function endingCopy(e, lang) {
  const id = lang === "id";
  const map = {
    SUMMIT_AND_HOME: id
      ? "Kamu berdiri lagi di Base Camp. Puncak sudah di belakang. Kamu hidup."
      : "You stand in Base Camp again. The summit is behind you. You lived.",
    SUMMIT_DIED_DESCENT: id
      ? "Puncak tercapai. Turunan yang membunuhmu."
      : "The summit was optional. The descent was not.",
    TURNED_AROUND: id
      ? "Kamu putar balik. Gunung masih di sini besok. Kamu juga."
      : "You turned around. The mountain will be here. So will you.",
    HYPOXIA: id
      ? "Udara tidak cukup. Otak padam pelan-pelan."
      : "There was not enough air. The lights went out slowly.",
    HAPE: id
      ? "Paru-parumu terisi cairan. Kamu tenggelam di daratan."
      : "Your lungs filled. You drowned on dry stone.",
    HACE: id
      ? "Otak membengkak. Dunia miring, lalu hilang."
      : "The brain swelled. The world listed, then left.",
    HYPOTHERMIA: id
      ? "Dingin berhenti terasa. Itu tanda terakhir."
      : "The cold stopped hurting. That was the last mercy.",
    FALL: id ? "Satu pijakan. Lalu udara." : "One foothold. Then air.",
    STORM: id
      ? "Badai menutup jendela cuaca. Gunung menutupmu."
      : "The weather window shut. The mountain shut with it.",
    DEATH_ZONE: id
      ? "Di atas delapan ribu, tubuh tidak beradaptasi. Ia hanya mati."
      : "Above eight thousand the body does not adapt. It only dies.",
    COLLAPSE: id
      ? "Kakimu berhenti menurut. Itu akhir pendakian."
      : "The legs stopped taking orders. That was the climb.",
  };
  return map[e] || "";
}

export function causeToEnding(cause, ex) {
  if (cause === "hape") return ENDING.HAPE;
  if (cause === "hace") return ENDING.HACE;
  if (cause === "hypothermia") return ENDING.HYPOTHERMIA;
  if (cause === "death_zone") return ENDING.DEATH_ZONE;
  if (cause === "hypoxia") return ENDING.HYPOXIA;
  if (cause === "storm") return ENDING.STORM;
  if (cause === "fall") return ENDING.FALL;
  if (ex.summited) return ENDING.SUMMIT_DIED_DESCENT;
  return ENDING.COLLAPSE;
}
