package dev.hoshi.thinair

import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val WORLD_M = 2400f
const val VISUAL_H = 520f
const val BASE_ALT = 4280f
const val SUMMIT_ALT = 8849f

data class Hold(
    val x: Float, val y: Float, val z: Float,
    val w: Float, val d: Float, val thick: Float,
    val ice: Boolean, val rest: Boolean, val camp: String? = null,
)

data class Camp(val id: String, val name: String, val x: Float, val z: Float, val index: Int)

object World {
    val camps = listOf(
        Camp("bc", "Base Camp", 12f, -708f, 0),
        Camp("c1", "Camp I", 0f, -499f, 1),
        Camp("c2", "Camp II", -14f, -235f, 2),
        Camp("c3", "Camp III", -19f, 19f, 3),
        Camp("c4", "Camp IV", 0f, 180f, 4),
    )
    val summit = Triple(0f, 293f, "summit")

    fun heightNorm(u: Float, v: Float): Float {
        val dx = (u - 0.5f) * 2f
        val dy = (v - 0.56f) * 2f
        val r = hypot(dx * 2.05f, dy * 1.25f)
        val horn = max(0f, 1f - r).let { it * it * sqrt(it) }
        val rx = dx + 0.045f * sin(v * 16f)
        val ridge = exp(-(rx * rx) * 70f) * smooth(0.1f, 0.56f, v) * (1f - smooth(0.78f, 1f, v))
        var h = max(horn * 0.96f, ridge * 0.9f)
        h += exp(-((u - 0.31f) * (u - 0.31f) * 90f + (v - 0.42f) * (v - 0.42f) * 55f)) * 0.28f
        val n = fbm(u * 7f, v * 7f)
        h += (n - 0.5f) * 0.045f
        val shelves = floatArrayOf(0.12f, 0.22f, 0.34f, 0.46f, 0.58f, 0.7f, 0.82f)
        var terr = h
        for (s in shelves) if (h > s && h < s + 0.055f) terr = s + (h - s) * 0.12f
        h = terr * 0.78f + h * 0.22f
        h = max(h, 0.012f + n * 0.02f)
        h += 0.07f * exp(-(dx * dx + dy * dy) * 110f)
        return h.coerceIn(0f, 1f)
    }

    fun heightY(x: Float, z: Float): Float {
        val u = x / WORLD_M + 0.5f
        val v = z / WORLD_M + 0.5f
        return heightNorm(u, v) * VISUAL_H
    }

    fun altitude(x: Float, z: Float): Float =
        BASE_ALT + heightNorm(x / WORLD_M + 0.5f, z / WORLD_M + 0.5f) * (SUMMIT_ALT - BASE_ALT)

    fun slopeDeg(x: Float, z: Float): Float {
        val eps = 1.6f
        val dx = heightY(x + eps, z) - heightY(x - eps, z)
        val dz = heightY(x, z + eps) - heightY(x, z - eps)
        val ny = 2f * eps
        val len = sqrt(dx * dx + ny * ny + dz * dz)
        val y = ny / len
        return Math.toDegrees(acos(y.coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    fun biome(h: Float, steep: Float): FloatArray {
        val snow = (h * 1.35f - (steep - 32f) / 55f).coerceIn(0f, 1f)
        var r: Float; var g: Float; var b: Float
        when {
            h < 0.16f -> { r = 0.28f; g = 0.42f; b = 0.22f }
            h < 0.34f -> { r = 0.45f; g = 0.40f; b = 0.30f }
            h < 0.55f -> { r = 0.52f; g = 0.48f; b = 0.42f }
            else -> { r = 0.78f; g = 0.80f; b = 0.84f }
        }
        r = r * (1 - snow) + 0.93f * snow
        g = g * (1 - snow) + 0.95f * snow
        b = b * (1 - snow) + 0.98f * snow
        if (steep > 55f) { r *= 0.72f; g *= 0.74f; b *= 0.8f }
        return floatArrayOf(r, g, b)
    }

    fun buildHolds(): List<Hold> {
        val out = ArrayList<Hold>(400)
        val sx = camps[0].x; val sz = camps[0].z
        val ex = summit.first; val ez = summit.second
        val steps = 90
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val x = sx + (ex - sx) * t + sin(t * 22f) * (11f - t * 6f)
            val z = sz + (ez - sz) * t
            val gy = heightY(x, z)
            val rest = i % 4 == 0
            val w = (if (i < 12) 5.2f else 3.2f) - t * 1.1f + if (rest) 2.8f else 0f
            val d = 2.8f - t * 0.7f
            val thick = 0.6f
            val y = gy + 0.35f
            val ice = t > 0.62f
            out += Hold(x, y, z, w, d, thick, ice, rest)
            if (i < steps - 1) {
                for (k in 0 until 3) {
                    val hx = x + (k - 1) * 0.95f + sin(i * 1.7f + k) * 0.35f
                    val hz = z + 0.75f
                    val hy = y + 0.9f + k * 0.7f
                    out += Hold(hx, hy, hz, 0.85f, 0.55f, 0.38f, ice, false)
                }
            }
        }
        for (c in camps) {
            val y = heightY(c.x, c.z) + 0.25f
            out += Hold(c.x, y, c.z, 16f, 12f, 0.5f, ice = false, rest = true, camp = c.id)
        }
        return out
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun hash2(ix: Int, iy: Int): Float {
        var n = ix * 374761393 + iy * 668265263
        n = n xor (n ushr 13)
        n *= 1274126177
        return ((n and 0xFFFFFF) / 16777215f)
    }

    private fun noise2(x: Float, y: Float): Float {
        val x0 = floor(x.toDouble()).toInt()
        val y0 = floor(y.toDouble()).toInt()
        val fx = x - x0
        val fy = y - y0
        val u = fx * fx * (3 - 2 * fx)
        val v = fy * fy * (3 - 2 * fy)
        val a = hash2(x0, y0); val b = hash2(x0 + 1, y0)
        val c = hash2(x0, y0 + 1); val d = hash2(x0 + 1, y0 + 1)
        return a + (b - a) * u + ((c + (d - c) * u) - (a + (b - a) * u)) * v
    }

    private fun fbm(x: Float, y: Float): Float {
        var amp = 1f; var freq = 1f; var total = 0f; var norm = 0f
        repeat(5) {
            total += noise2(x * freq, y * freq) * amp
            norm += amp; amp *= 0.5f; freq *= 2.05f
        }
        return total / norm
    }
}
