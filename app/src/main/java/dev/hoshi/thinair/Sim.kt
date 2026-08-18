package dev.hoshi.thinair

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class Sim {
    val body = NativeSim()
    val holds = World.buildHolds()

    var x = World.camps[0].x + 2.5f
    var y = World.heightY(World.camps[0].x, World.camps[0].z) + 0.2f
    var z = World.camps[0].z + 6f
    var vy = 0f
    var yaw = 0.05f
    var pitch = 0.18f
    var climbing = false
    var grounded = true
    var inTent = false
    var lastCamp = "bc"
    var hour = 4.55f
    var weather = 0.18f
    var o2Bottles = 2
    var o2On = false
    var o2Left = 0f
    var food = 4
    var summited = false
    var speed = 0f
    var alt = 5300f
    var ended: String? = null
    var prompt: String = "Tahan PANJAT di batu. Jatuh = balik tenda."

    fun look(dx: Float, dy: Float) {
        yaw -= dx * 0.0055f
        pitch = (pitch + dy * 0.0045f).coerceIn(-0.15f, 1.25f)
    }

    fun step(dt: Float, stickX: Float, stickY: Float, climbBtn: Boolean, run: Boolean) {
        if (ended != null) return
        val fwdX = sin(yaw); val fwdZ = cos(yaw)
        val rightX = fwdZ; val rightZ = -fwdX
        var wx = 0f; var wz = 0f
        if (hypot(stickX, stickY) > 0.12f) {
            wx += fwdX * -stickY + rightX * stickX
            wz += fwdZ * -stickY + rightZ * stickX
        }
        val wlen = hypot(wx, wz)
        if (wlen > 0.001f) { wx /= wlen; wz /= wlen }

        alt = World.altitude(x, z)
        val slope = World.slopeDeg(x, z)
        val gy = groundY(x, z, y)
        grounded = y <= gy + 0.18f && vy <= 0.4f
        val hold = nearest(x, y + 1.1f, z, wx, 0f, wz, 2.4f)
        val canGrab = hold != null || slope > 46f

        climbing = climbBtn && canGrab && snap.stamina > 0.04f && !inTent
        if (snap.stamina <= 0.02f) climbing = false

        if (climbing) {
            var cx = wx; var cy = if (climbBtn) 1f else 0f; var cz = wz
            if (stickY > 0.4f) cy = -1f
            val cl = hypot(hypot(cx, cy), cz)
            if (cl > 0.001f) { cx /= cl; cy /= cl; cz /= cl }
            val spd = 2.15f * snap.moveScale * if (hold?.ice == true) 0.7f else 1f
            x += cx * spd * dt; y += cy * spd * dt; z += cz * spd * dt
            nearest(x, y + 1f, z, cx, cy, cz, 2.6f)?.let { s ->
                x += (s.x - x) * (1 - kotlin.math.exp(-8 * dt))
                z += (s.z - z) * (1 - kotlin.math.exp(-8 * dt))
                y += ((s.y - 0.55f) - y) * (1 - kotlin.math.exp(-8 * dt))
            }
            speed = spd
            vy = 0f
        } else {
            val walk = (if (run) 7.2f else 4.4f) * snap.moveScale
            if (grounded) {
                x += wx * walk * dt; z += wz * walk * dt
                vy = if (climbBtn) 6.2f else 0f
                speed = if (wlen > 0) walk else 0f
            } else {
                x += wx * 3.2f * dt; z += wz * 3.2f * dt
                vy -= 22f * dt
                speed = 3.2f
            }
            y += vy * dt
            val ng = groundY(x, z, y)
            if (y < ng) {
                if (vy < -16f) { respawn(); return }
                y = ng; vy = 0f; grounded = true
            }
        }
        val half = WORLD_M * 0.48f
        x = x.coerceIn(-half, half)
        z = z.coerceIn(-half, half)

        hour = (hour + dt * 12f / 3600f) % 24f
        if (o2On) {
            o2Left -= dt * 12f
            if (o2Left <= 0f) { o2Left = 0f; o2On = false }
        }
        body.tick(
            dt, alt, speed, slope, climbing, inTent,
            if (o2On) 2.5f else 0f,
            3f + weather * 20f, 12f - alt * 0.0065f, 0.05f, if (inTent) 1f else 0f,
        )
        if (climbing) {
            val tax = 0.11f + ((alt - 5500f) / 4000f).coerceIn(0f, 1f) * 0.16f
            // stamina also from C; extra game tax applied by climbing flag already
        }

        val c = nearestCamp()
        if (summited && c.index == 0 && hypot(x - c.x, z - c.z) < 20f) {
            ended = "win"
            prompt = "Kamu hidup. Turunan selesai."
        }
        val sx = World.summit.first; val sz = World.summit.second
        if (!summited && hypot(x - sx, z - sz) < 12f && alt > 8500f) {
            summited = true
            prompt = "PUNCAK. Turun sekarang."
        }
        if (snap.dead) {
            ended = snap.cause.ifEmpty { "hypoxia" }
            prompt = "Ekspedisi berakhir: $ended"
        }
        if (y < World.heightY(x, z) - 4f) respawn()
    }

    val snap get() = body.snapshot()

    fun groundY(px: Float, pz: Float, fromY: Float): Float {
        var gy = World.heightY(px, pz)
        for (h in holds) {
            if (kotlin.math.abs(px - h.x) <= h.w * 0.55f && kotlin.math.abs(pz - h.z) <= h.d * 0.55f) {
                val top = h.y + h.thick * 0.5f
                if (fromY + 0.35f >= top && top > gy) gy = top
            }
        }
        return gy
    }

    fun nearest(px: Float, py: Float, pz: Float, dx: Float, dy: Float, dz: Float, r: Float): Hold? {
        var best: Hold? = null
        var bestS = -1e9f
        for (h in holds) {
            val vx = h.x - px; val vy = h.y - py; val vz = h.z - pz
            val d = hypot(hypot(vx, vy), vz)
            if (d < 0.15f || d > r) continue
            val align = (vx * dx + vy * dy + vz * dz) / d
            val s = align * 2.2f - d * 0.35f + if (h.rest) 0.4f else 0f
            if (s > bestS) { bestS = s; best = h }
        }
        return best
    }

    fun nearestCamp(): Camp {
        var b = World.camps[0]; var bd = 1e9f
        for (c in World.camps) {
            val d = kotlin.math.abs(World.altitude(c.x, c.z) - alt)
            if (d < bd) { bd = d; b = c }
        }
        return b
    }

    fun tent() {
        val c = nearestCamp()
        if (hypot(x - c.x, z - c.z) < 16f) {
            inTent = !inTent
            if (inTent) {
                lastCamp = c.id
                prompt = "Tenda. Checkpoint."
            }
        }
    }

    fun eat() {
        if (food <= 0) { prompt = "Makanan habis"; return }
        food--
        prompt = "Makan. Stamina pulih."
    }

    fun oxygen() {
        if (o2On) { o2On = false; prompt = "O₂ off"; return }
        if (o2Bottles <= 0 && o2Left <= 0f) { prompt = "O₂ habis"; return }
        if (o2Left <= 0f) { o2Bottles--; o2Left = 360f }
        o2On = true
        prompt = "Oksigen ON"
    }

    fun respawn() {
        val c = World.camps.find { it.id == lastCamp } ?: World.camps[0]
        x = c.x + 2.5f
        z = c.z + 6f
        y = World.heightY(c.x, c.z) + 0.2f
        vy = 0f
        climbing = false
        prompt = "Jatuh. Balik ke tenda."
    }

    fun heading(): Float = atan2(
        if (speed > 0.2f) sin(yaw) else sin(yaw),
        cos(yaw),
    )

    fun destroy() = body.destroy()
}
