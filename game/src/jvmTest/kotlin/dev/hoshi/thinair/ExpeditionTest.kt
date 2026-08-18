package dev.hoshi.thinair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpeditionTest {
    @Test
    fun nearestCampPicksSouthColNearDeathZone() {
        assertEquals(4, Expedition.nearestCamp(7950.0))
        assertEquals(0, Expedition.nearestCamp(5400.0))
    }

    @Test
    fun turnaroundRespectsClockAndBody() {
        assertTrue(Expedition.shouldTurnAround(13.5, 13.0, 80.0, 0.8))
        assertTrue(Expedition.shouldTurnAround(8.0, 13.0, 50.0, 0.8))
        assertFalse(Expedition.shouldTurnAround(8.0, 13.0, 80.0, 0.8))
    }

    @Test
    fun oxygenConsumesInventory() {
        val s = ExpeditionState()
        s.inventory.o2Bottles = 1
        assertTrue(Expedition.useOxygen(s))
        assertFalse(Expedition.useOxygen(s))
        assertEquals(0, s.inventory.o2Bottles)
    }

    @Test
    fun livingSummitRequiresGettingHome() {
        val s = ExpeditionState(summited = true, headingUp = false, campIndex = 0)
        val e = Expedition.resolveEnding(s, cause = null, atCamp = true, fallen = false)
        assertEquals(Ending.SUMMIT_AND_HOME, e)
    }
}
