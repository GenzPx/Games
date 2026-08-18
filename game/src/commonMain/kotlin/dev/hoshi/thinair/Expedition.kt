package dev.hoshi.thinair

/**
 * Expedition state machine — Kotlin source of truth for camps,
 * inventory, turnaround, and endings. Ported numerically in web/js/expedition.js
 */
data class Inventory(
    var o2Bottles: Int = 2,
    var food: Int = 4,
    var waterBottles: Int = 3,
    var radioBattery: Double = 1.0,
)

enum class Ending {
    NONE, SUMMIT_AND_HOME, SUMMIT_DIED_DESCENT, TURNED_AROUND,
    HYPOXIA, HAPE, HACE, HYPOTHERMIA, FALL, STORM, DEATH_ZONE, COLLAPSE
}

data class ExpeditionState(
    var hour: Double = 4.5,          // 04:30 start
    var day: Int = 1,
    var altitude: Double = 5364.0,
    var campIndex: Int = 0,
    var headingUp: Boolean = true,
    var summited: Boolean = false,
    var turnaroundHour: Double = 13.0,
    var weather: Double = 0.18,      // 0 clear .. 1 storm
    var inventory: Inventory = Inventory(),
    var ending: Ending = Ending.NONE,
    var radioLog: MutableList<String> = mutableListOf(),
)

object Expedition {
    const val DEATH_ZONE = 8000.0
    val campAlts = doubleArrayOf(5364.0, 6100.0, 6500.0, 7300.0, 7920.0)
    val campNames = arrayOf("Base Camp", "Camp I", "Camp II", "Camp III", "Camp IV / South Col")

    fun nearestCamp(alt: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in campAlts.indices) {
            val d = kotlin.math.abs(campAlts[i] - alt)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    fun shouldTurnAround(hour: Double, turnaround: Double, spo2: Double, stamina: Double): Boolean {
        if (hour >= turnaround) return true
        if (spo2 < 52.0) return true
        if (stamina < 0.18) return true
        return false
    }

    fun advanceWeather(state: ExpeditionState, dtHours: Double, rng: Double) {
        val drift = (rng - 0.48) * 0.35 * dtHours
        state.weather = (state.weather + drift).coerceIn(0.0, 1.0)
        // Afternoon storms more likely
        val afternoon = if (state.hour in 12.0..18.0) 0.04 * dtHours else 0.0
        state.weather = (state.weather + afternoon).coerceIn(0.0, 1.0)
    }

    fun tickClock(state: ExpeditionState, dtSeconds: Double) {
        state.hour += dtSeconds / 3600.0
        if (state.hour >= 24.0) {
            state.hour -= 24.0
            state.day += 1
        }
    }

    fun useOxygen(state: ExpeditionState): Boolean {
        if (state.inventory.o2Bottles <= 0) return false
        state.inventory.o2Bottles -= 1
        return true
    }

    fun eat(state: ExpeditionState): Boolean {
        if (state.inventory.food <= 0) return false
        state.inventory.food -= 1
        return true
    }

    fun drink(state: ExpeditionState): Boolean {
        if (state.inventory.waterBottles <= 0) return false
        state.inventory.waterBottles -= 1
        return true
    }

    fun resolveEnding(
        state: ExpeditionState,
        cause: String?,
        atCamp: Boolean,
        fallen: Boolean,
    ): Ending {
        if (state.ending != Ending.NONE) return state.ending
        val e = when {
            fallen -> Ending.FALL
            cause == "hape" -> Ending.HAPE
            cause == "hace" -> Ending.HACE
            cause == "hypothermia" -> Ending.HYPOTHERMIA
            cause == "death_zone" -> Ending.DEATH_ZONE
            cause == "hypoxia" -> Ending.HYPOXIA
            cause == "storm" -> Ending.STORM
            state.summited && atCamp && state.campIndex <= 1 && !state.headingUp ->
                Ending.SUMMIT_AND_HOME
            state.summited && cause != null -> Ending.SUMMIT_DIED_DESCENT
            else -> Ending.NONE
        }
        state.ending = e
        return e
    }

    fun endingCopy(e: Ending, lang: String): String {
        val id = lang.startsWith("id")
        return when (e) {
            Ending.SUMMIT_AND_HOME ->
                if (id) "Kamu berdiri lagi di Base Camp. Puncak sudah di belakang. Kamu hidup."
                else "You stand in Base Camp again. The summit is behind you. You lived."
            Ending.SUMMIT_DIED_DESCENT ->
                if (id) "Puncak tercapai. Turunan yang membunuhmu."
                else "The summit was optional. The descent was not."
            Ending.TURNED_AROUND ->
                if (id) "Kamu putar balik. Gunung masih di sini besok. Kamu juga."
                else "You turned around. The mountain will be here. So will you."
            Ending.HYPOXIA ->
                if (id) "Udara tidak cukup. Otak padam pelan-pelan."
                else "There was not enough air. The lights went out slowly."
            Ending.HAPE ->
                if (id) "Paru-parumu terisi cairan. Kamu tenggelam di daratan."
                else "Your lungs filled. You drowned on dry stone."
            Ending.HACE ->
                if (id) "Otak membengkak. Dunia miring, lalu hilang."
                else "The brain swelled. The world listed, then left."
            Ending.HYPOTHERMIA ->
                if (id) "Dingin berhenti terasa. Itu tanda terakhir."
                else "The cold stopped hurting. That was the last mercy."
            Ending.FALL ->
                if (id) "Satu pijakan. Lalu udara."
                else "One foothold. Then air."
            Ending.STORM ->
                if (id) "Badai menutup jendela cuaca. Gunung menutupmu."
                else "The weather window shut. The mountain shut with it."
            Ending.DEATH_ZONE ->
                if (id) "Di atas delapan ribu, tubuh tidak beradaptasi. Ia hanya mati."
                else "Above eight thousand the body does not adapt. It only dies."
            Ending.COLLAPSE ->
                if (id) "Kakimu berhenti menurut. Itu akhir pendakian."
                else "The legs stopped taking orders. That was the climb."
            Ending.NONE -> ""
        }
    }
}
