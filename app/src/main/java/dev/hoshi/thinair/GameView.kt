package dev.hoshi.thinair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private const val MAP = 48
private const val TS = 16
private const val DAY = 80f
private const val NIGHT = 55f

private const val GRASS = 0
private const val DIRT = 1
private const val PATH = 2
private const val WATER = 3
private const val TREE = 4
private const val STUMP = 5
private const val BUSH = 6
private const val BUSH0 = 7
private const val ROCK = 8
private const val FLOWER = 9

class GameView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {
    private val atlas = Atlas(ctx)
    private val audio = Audio(ctx)
    private var lastStep = 0f
    private var lastTheme = ""
    private var thread: Thread? = null
    @Volatile private var running = false

    private val px = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val ui = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val nightPaint = Paint()
    private val lightPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val tmp = RectF()

    private val tiles = Array(MAP) { IntArray(MAP) }
    private val rng = Random(11)

    private var x = 24f
    private var y = 25f
    private var dir = 1
    private var walk = 0f
    private var hunger = 1f
    private var warmth = 1f
    private var wood = 3
    private var food = 2
    private var day = 1
    private var clock = 10f
    private var fireX = 24f
    private var fireY = 24f
    private var fire = 0.8f
    private var lit = true
    private var mode = "title"
    private var toast = "Jaga api sebelum malam."
    private var toastT = 4f
    private var shake = 0f
    private var scale = 4
    private val pops = ArrayList<Pop>()
    private val bits = ArrayList<Bit>()
    private val wolves = ArrayList<Mob>()

    private var stickId = -1
    private var sx = 0f
    private var sy = 0f
    private var dashing = false
    private var atkDown = false
    private var paused = false

    data class Pop(var x: Float, var y: Float, var t: Float, val text: String)
    data class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var t: Float, val col: Int)
    data class Mob(var x: Float, var y: Float)
    data class Draw(val z: Float, val run: () -> Unit)

    init {
        holder.addCallback(this)
        isFocusable = true
        buildMap()
    }

    private fun buildMap() {
        for (j in 0 until MAP) for (i in 0 until MAP) {
            val edge = i < 2 || j < 2 || i > MAP - 3 || j > MAP - 3
            val pond = hypot(i - 12f, j - 34f) < 4.5f
            tiles[j][i] = when {
                edge || pond -> WATER
                else -> GRASS
            }
        }
        // clearing + path
        for (j in 20..28) for (i in 20..28) {
            val d = hypot(i - 24f, j - 24f)
            if (d < 5.2f) tiles[j][i] = if (d < 2.4f) DIRT else PATH
        }
        for (i in 24..40) tiles[24][i] = PATH
        for (j in 24..40) tiles[j][36] = PATH
        // forest clusters
        repeat(70) {
            val i = rng.nextInt(3, MAP - 3)
            val j = rng.nextInt(3, MAP - 3)
            if (hypot(i - 24f, j - 24f) < 6f) return@repeat
            if (tiles[j][i] == GRASS) tiles[j][i] = TREE
        }
        repeat(28) {
            val i = rng.nextInt(3, MAP - 3)
            val j = rng.nextInt(3, MAP - 3)
            if (tiles[j][i] == GRASS) tiles[j][i] = BUSH
        }
        repeat(18) {
            val i = rng.nextInt(3, MAP - 3)
            val j = rng.nextInt(3, MAP - 3)
            if (tiles[j][i] == GRASS) tiles[j][i] = ROCK
        }
        repeat(40) {
            val i = rng.nextInt(3, MAP - 3)
            val j = rng.nextInt(3, MAP - 3)
            if (tiles[j][i] == GRASS && rng.nextFloat() < 0.7f) tiles[j][i] = FLOWER
        }
        x = 24.2f; y = 25.4f
        fireX = 24f; fireY = 23.6f
        tiles[22][22] = PATH
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this, "ember").also { it.start() }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        audio.release()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
        scale = (minOf(w / (16 * 20), ht / (16 * 12))).coerceIn(3, 7)
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = now
            if (mode == "play" && !paused) tick(dt)
            pumpMusic()
            holder.lockCanvas()?.let {
                try { render(it) } finally { holder.unlockCanvasAndPost(it) }
            }
            val leftover = 16_000_000L - (System.nanoTime() - now)
            if (leftover > 2_000_000) try { Thread.sleep(leftover / 1_000_000) } catch (_: InterruptedException) {}
        }
    }

    private fun solid(tx: Int, ty: Int): Boolean {
        if (tx !in 0 until MAP || ty !in 0 until MAP) return true
        return tiles[ty][tx] == WATER || tiles[ty][tx] == TREE || tiles[ty][tx] == ROCK
    }

    private fun tick(dt: Float) {
        val sp = if (dashing) 5.4f else 3.15f
        if (dashing) warmth = (warmth - dt * 0.02f).coerceAtLeast(0f)
        if (hypot(sx, sy) > 0.08f) {
            dir = if (kotlin.math.abs(sx) > kotlin.math.abs(sy)) if (sx < 0) 2 else 3 else if (sy < 0) 0 else 1
            walk += dt * 9f
            lastStep += dt
            if (lastStep > 0.32f) { lastStep = 0f; audio.playSfx("step", 0.28f) }
            val nx = x + sx * sp * dt
            val ny = y + sy * sp * dt
            if (!solid(floor(nx).toInt(), floor(y).toInt())) x = nx.coerceIn(1.3f, MAP - 1.3f)
            if (!solid(floor(x).toInt(), floor(ny).toInt())) y = ny.coerceIn(1.3f, MAP - 1.3f)
        }
        clock += dt
        val cycle = DAY + NIGHT
        if (clock >= cycle) {
            clock -= cycle
            day++
            say("Hari $day. Kayu. Makanan. Api.")
            for (j in 0 until MAP) for (i in 0 until MAP)
                if (tiles[j][i] == BUSH0 && rng.nextFloat() < 0.6f) tiles[j][i] = BUSH
        }
        val night = clock >= DAY
        if (clock in (DAY - 9f)..DAY && toastT < 0.2f) say("Matahari terbenam. Balik ke api.")

        hunger = (hunger - dt * 0.011f).coerceIn(0f, 1f)
        val near = lit && fire > 0.04f && hypot(x - fireX, y - fireY) < 3.4f
        warmth = when {
            night && !near -> (warmth - dt * 0.05f)
            near -> (warmth + dt * 0.14f).coerceAtMost(1f)
            else -> (warmth + dt * 0.012f).coerceAtMost(1f)
        }
        if (lit) fire = (fire - dt * 0.016f).coerceAtLeast(0f)
        if (fire <= 0f) lit = false

        if (night) {
            if (wolves.size < 1 + day / 2 && rng.nextFloat() < dt * 0.22f) {
                wolves += Mob(if (rng.nextBoolean()) 3f else MAP - 3f, rng.nextInt(6, MAP - 6).toFloat())
            }
        } else wolves.clear()

        val it = wolves.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val fd = hypot(w.x - fireX, w.y - fireY)
            if (lit && fire > 0.05f && fd < 4.8f) {
                w.x += (w.x - fireX) / (fd + 0.2f) * 2.4f * dt
                w.y += (w.y - fireY) / (fd + 0.2f) * 2.4f * dt
            } else {
                val d = hypot(x - w.x, y - w.y) + 0.05f
                w.x += (x - w.x) / d * 1.55f * dt
                w.y += (y - w.y) / d * 1.55f * dt
                if (d < 0.72f) {
                    warmth -= 0.16f
                    hunger -= 0.04f
                    shake = 0.35f
                    say("Digigit!")
                    pop(x, y - 0.5f, "HIT")
                    w.x -= (x - w.x) / d * 2.2f
                    w.y -= (y - w.y) / d * 2.2f
                }
            }
            if (w.x < 1.5f || w.y < 1.5f || w.x > MAP - 1.5f || w.y > MAP - 1.5f) it.remove()
        }

        // fire sparks
        if (lit && fire > 0 && rng.nextFloat() < dt * 14f) {
            bits += Bit(fireX + rng.nextFloat() * 0.4f - 0.2f, fireY, rng.nextFloat() * 0.4f - 0.2f, -1.4f - rng.nextFloat(), 0.5f, 0xFFFFCD75.toInt())
        }
        bits.removeAll { b ->
            b.t -= dt; b.x += b.vx * dt; b.y += b.vy * dt; b.vy -= 0.4f * dt
            b.t <= 0
        }
        pops.removeAll { p -> p.t -= dt; p.y -= dt * 0.6f; p.t <= 0 }
        if (shake > 0) shake -= dt
        if (toastT > 0) toastT -= dt
        if (hunger <= 0f) die("Kamu kelaparan.")
        else if (warmth <= 0f) die("Kamu membeku.")
    }

    private fun die(why: String) {
        mode = "dead"
        toast = "$why  Hari ke-$day."
        toastT = 99f
        wolves.clear()
        paused = false
        audio.playSfx("dead", 0.85f)
    }

    private fun pumpMusic() {
        val track = when {
            mode == "title" -> "audio/music/title.mp3"
            mode == "dead" -> "audio/music/night_a.mp3"
            clock >= DAY -> when (day % 3) {
                0 -> "audio/music/night_a.mp3"
                1 -> "audio/music/night_b.ogg"
                else -> "audio/music/night_c.ogg"
            }
            clock > DAY - 12f -> "audio/music/dusk.mp3"
            else -> when (day % 4) {
                0 -> "audio/music/day_a.ogg"
                1 -> "audio/music/day_b.ogg"
                2 -> "audio/music/day_c.mp3"
                else -> "audio/music/day_d.ogg"
            }
        }
        if (track != lastTheme) {
            lastTheme = track
            audio.music(track)
        }
    }

    private fun say(s: String) { toast = s; toastT = 3.4f }

    private fun pop(tx: Float, ty: Float, s: String) { pops += Pop(tx, ty, 1.1f, s) }

    private fun interact() {
        if (mode != "play") {
            audio.playSfx("start", 0.8f)
            reset(); mode = "play"; say("Tebang pohon. Ambil berry. Isi api.")
            return
        }
        val fx = floor(x + when (dir) { 2 -> -0.85f; 3 -> 0.85f; else -> 0f }).toInt()
        val fy = floor(y + when (dir) { 0 -> -0.85f; 1 -> 0.85f; else -> 0f }).toInt()
        if (fx !in 0 until MAP || fy !in 0 until MAP) return
        when (tiles[fy][fx]) {
            TREE -> {
                tiles[fy][fx] = STUMP
                wood++
                shake = 0.18f
                audio.playSfx(if (wood % 2 == 0) "chop" else "chop2", 0.75f)
                pop(fx + 0.5f, fy.toFloat(), "+1 kayu")
                repeat(6) {
                    bits += Bit(fx + 0.5f, fy + 0.5f, rng.nextFloat() * 2 - 1, -1.2f, 0.4f, 0xFF8B5A3C.toInt())
                }
                say("Kayu. Api butuh makan.")
            }
            BUSH -> {
                tiles[fy][fx] = BUSH0
                food++
                audio.playSfx("pickup", 0.7f)
                pop(fx + 0.5f, fy.toFloat(), "+1 berry")
                say("Berry. Makan sebelum lapar.")
            }
            else -> {
                if (wood >= 3 && !lit) {
                    fireX = x; fireY = y; lit = true; fire = 0.9f; wood -= 3
                    say("Api hidup lagi.")
                    pop(x, y - 0.4f, "API")
                } else if (wood >= 1 && lit && hypot(x - fireX, y - fireY) < 2.3f) {
                    wood--; fire = (fire + 0.34f).coerceAtMost(1f)
                    pop(fireX, fireY - 0.5f, "fuel")
                    say("Kayu ke unggun.")
                } else say("Hadap pohon / semak. 3 kayu = nyalakan api.")
            }
        }
    }

    private fun eat() {
        if (mode != "play") return
        if (food <= 0) { say("Berry habis."); return }
        food--; hunger = (hunger + 0.4f).coerceAtMost(1f)
        audio.playSfx("eat", 0.8f)
        pop(x, y - 0.5f, "+HP")
        say("Kenyang.")
    }

    private fun reset() {
        buildMap()
        hunger = 1f; warmth = 1f; wood = 3; food = 2
        day = 1; clock = 10f; fire = 0.8f; lit = true
        wolves.clear(); bits.clear(); pops.clear()
        sx = 0f; sy = 0f
        paused = false
    }

    private fun dusk(): Float {
        if (clock < DAY - 10f) return 0f
        if (clock < DAY) return (clock - (DAY - 10f)) / 10f
        if (clock > DAY + NIGHT - 8f) return (1f - (clock - (DAY + NIGHT - 8f)) / 8f).coerceAtLeast(0f)
        return 1f
    }

    private fun render(c: Canvas) {
        val W = c.width
        val H = c.height
        c.drawColor(0xFF1B2430.toInt())
        if (mode == "title") { drawTitle(c, W, H); return }

        val tw = TS * scale
        var camX = x * tw - W / 2f
        var camY = y * tw - H / 2f
        if (shake > 0) {
            camX += (rng.nextFloat() - 0.5f) * 10f * shake
            camY += (rng.nextFloat() - 0.5f) * 10f * shake
        }

        val x0 = (camX / tw).toInt() - 1
        val y0 = (camY / tw).toInt() - 1
        val x1 = x0 + W / tw + 3
        val y1 = y0 + H / tw + 3

        fun gx(tx: Float) = tx * tw - camX
        fun gy(ty: Float) = ty * tw - camY

        for (ty in y0..y1) for (tx in x0..x1) {
            if (tx !in 0 until MAP || ty !in 0 until MAP) continue
            val t = tiles[ty][tx]
            val ground = when (t) {
                DIRT -> atlas.t(0, 1)
                PATH -> atlas.t(6, 3)
                WATER -> atlas.d(0, 3)
                else -> if ((tx + ty) and 1 == 0) atlas.t(0, 0) else atlas.t(1, 0)
            }
            c.drawBitmap(atlas.s(ground, scale), gx(tx.toFloat()), gy(ty.toFloat()), px)
            if (t == FLOWER) c.drawBitmap(atlas.s(atlas.t(2, 0), scale), gx(tx.toFloat()), gy(ty.toFloat()), px)
        }

        val draw = ArrayList<Draw>(80)
        for (ty in y0..y1) for (tx in x0..x1) {
            if (tx !in 0 until MAP || ty !in 0 until MAP) continue
            val t = tiles[ty][tx]
            val sx = gx(tx.toFloat())
            val sy = gy(ty.toFloat())
            when (t) {
                TREE -> draw += Draw(ty + 0.95f) {
                    c.drawBitmap(atlas.s(atlas.t(4, 0, 1, 3), scale), sx, sy - 2 * tw, px)
                }
                STUMP -> draw += Draw(ty + 0.6f) {
                    c.drawBitmap(atlas.s(atlas.t(4, 2), scale), sx, sy, px)
                }
                BUSH, BUSH0 -> draw += Draw(ty + 0.55f) {
                    c.drawBitmap(atlas.s(if (t == BUSH) atlas.t(5, 0) else atlas.t(5, 1), scale), sx, sy, px)
                    if (t == BUSH) c.drawBitmap(atlas.s(atlas.t(5, 2), scale), sx, sy + scale * 6f, px)
                }
                ROCK -> draw += Draw(ty + 0.5f) {
                    c.drawBitmap(atlas.s(atlas.d(0, 0), scale), sx, sy, px)
                }
            }
        }
        // tent
        draw += Draw(22.8f) {
            c.drawBitmap(atlas.s(atlas.t(8, 4, 2, 2), scale), gx(21.5f), gy(21.2f), px)
        }
        if (lit && fire > 0) {
            draw += Draw(fireY + 0.4f) {
                val fr = ((System.currentTimeMillis() / 120) % 2).toInt()
                c.drawBitmap(atlas.s(atlas.d(5, 2), scale), gx(fireX) - tw / 2f, gy(fireY) - tw / 4f, px)
                c.drawBitmap(atlas.s(atlas.d(5, 1), scale), gx(fireX) - tw / 2f, gy(fireY) - tw * 0.95f - fr, px)
            }
        }
        for (w in wolves) {
            val mob = w
            draw += Draw(mob.y + 0.3f) {
                c.drawBitmap(atlas.s(atlas.d(2, 9), scale), gx(mob.x) - tw / 2f, gy(mob.y) - tw / 2f, px)
            }
        }
        draw += Draw(y + 0.35f) {
            val bob = if (hypot(sx, sy) > 0.1f) sin(walk * 2.2f) * scale * 0.35f else 0f
            val ch = atlas.fit(atlas.hero(dir), (tw * 11) / 10, (tw * 15) / 10)
            val px0 = W / 2f - ch.width / 2f
            val py0 = H / 2f - ch.height * 0.72f + bob
            c.drawBitmap(ch, px0, py0, px)
            val bw = tw * 0.88f
            val bh = 6f
            val bx = W / 2f - bw / 2f
            val by = py0 + ch.height + 2f
            ui.color = 0xCC10141C.toInt()
            c.drawRoundRect(bx, by, bx + bw, by + bh, 3f, 3f, ui)
            ui.color = if (hunger > 0.35f) 0xFF3DDC6A.toInt() else 0xFFE84D4D.toInt()
            c.drawRoundRect(bx + 1f, by + 1f, bx + 1f + (bw - 2f) * hunger.coerceIn(0f, 1f), by + bh - 1f, 2f, 2f, ui)
        }
        draw.sortBy { it.z }
        for (d in draw) d.run()

        for (b in bits) {
            ui.color = b.col
            c.drawRect(gx(b.x), gy(b.y), gx(b.x) + scale.toFloat(), gy(b.y) + scale.toFloat(), ui)
        }
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 13f
        ui.color = 0xFFFFF3C4.toInt()
        for (p in pops) c.drawText(p.text, gx(p.x), gy(p.y), ui)

        val night = dusk()
        if (night > 0.02f) {
            val layer = c.saveLayer(0f, 0f, W.toFloat(), H.toFloat(), null)
            nightPaint.color = Color.argb((210 * night).toInt(), 6, 8, 20)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), nightPaint)
            if (lit && fire > 0) {
                val cx = gx(fireX)
                val cy = gy(fireY)
                val r = 5.6f * tw * (0.7f + 0.3f * fire)
                lightPaint.shader = RadialGradient(cx, cy, r, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, lightPaint)
            }
            lightPaint.shader = RadialGradient(W / 2f, H / 2f, 2.3f * tw, 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(W / 2f, H / 2f, 2.3f * tw, lightPaint)
            c.restoreToCount(layer)
        }

        drawHud(c, W, H)
        drawPad(c, W, H)
        if (paused && mode == "play") drawPause(c, W, H)
        if (mode == "dead") drawDead(c, W, H)
    }

    private fun drawTitle(c: Canvas, W: Int, H: Int) {
        val tw = TS * scale
        for (j in 0..8) for (i in 0..14) {
            c.drawBitmap(atlas.s(atlas.t(0, 0), scale), (i * tw).toFloat(), (j * tw).toFloat(), px)
            if ((i + j) % 4 == 0) c.drawBitmap(atlas.s(atlas.t(4, 0, 1, 3), scale), (i * tw).toFloat(), (j * tw - 2 * tw).toFloat(), px)
        }
        val face = atlas.fit(atlas.gtFace, 88, 88)
        c.drawBitmap(face, W / 2f - 44f, H * 0.18f, ui)
        ui.textAlign = Paint.Align.CENTER
        ui.color = 0xFFFFE566.toInt()
        ui.textSize = 42f
        c.drawText("EMBER", W / 2f, H * 0.42f, ui)
        ui.textSize = 14f
        ui.color = 0xCCFFFFFF.toInt()
        c.drawText("HOSHIDEV", W / 2f, H * 0.42f + 22f, ui)
        ui.textSize = 16f
        ui.color = 0xFFFFE566.toInt()
        c.drawText("TAP  ATK  TO  START", W / 2f, H * 0.55f, ui)
        drawPad(c, W, H)
    }

    private fun drawDead(c: Canvas, W: Int, H: Int) {
        ui.color = 0xD0051018.toInt()
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ui)
        val face = atlas.fit(atlas.gtFace, 96, 96)
        c.drawBitmap(face, W / 2f - 48f, H * 0.22f, ui)
        ui.textAlign = Paint.Align.CENTER
        ui.color = 0xFFFFFFFF.toInt()
        ui.textSize = 28f
        c.drawText("GAME OVER", W / 2f, H * 0.48f, ui)
        ui.textSize = 15f
        ui.color = 0xFFD0D4DC.toInt()
        c.drawText(toast, W / 2f, H * 0.55f, ui)
        // GT-style yellow lobby buttons
        ui.color = 0xFFFFD54A.toInt()
        c.drawRoundRect(W / 2f - 160f, H * 0.68f, W / 2f - 20f, H * 0.68f + 44f, 10f, 10f, ui)
        ui.color = 0xFFFFE566.toInt()
        c.drawRoundRect(W / 2f + 16f, H * 0.68f, W / 2f + 160f, H * 0.68f + 44f, 10f, 10f, ui)
        ui.color = 0xFF2A2418.toInt()
        ui.textSize = 14f
        c.drawText("Lobby", W / 2f - 90f, H * 0.68f + 28f, ui)
        c.drawText("Retry", W / 2f + 88f, H * 0.68f + 28f, ui)
    }

    private fun drawHud(c: Canvas, W: Int, H: Int) {
        // Guardian Tales party card — top left
        ui.color = 0xB20C1018.toInt()
        c.drawRoundRect(8f, 8f, 278f, 84f, 14f, 14f, ui)
        val face = atlas.fit(atlas.gtFace, 64, 64)
        c.drawBitmap(face, 12f, 14f, ui)
        ui.textAlign = Paint.Align.LEFT
        ui.textSize = 15f
        ui.color = 0xFFFFFFFF.toInt()
        c.drawText("KNIGHT", 86f, 30f, ui)
        ui.color = 0xFF1A1E28.toInt()
        c.drawRoundRect(86f, 36f, 266f, 50f, 4f, 4f, ui)
        ui.color = if (hunger > 0.35f) 0xFF3DDC6A.toInt() else 0xFFE84D4D.toInt()
        c.drawRoundRect(87f, 37f, 87f + 178f * hunger.coerceIn(0f, 1f), 49f, 3f, 3f, ui)
        ui.color = 0xFF1A1E28.toInt()
        c.drawRoundRect(86f, 54f, 266f, 66f, 4f, 4f, ui)
        ui.color = 0xFF4FC3F7.toInt()
        c.drawRoundRect(87f, 55f, 87f + 178f * warmth.coerceIn(0f, 1f), 65f, 3f, 3f, ui)

        ui.color = 0xB20C1018.toInt()
        c.drawRoundRect(8f, 90f, 96f, 116f, 8f, 8f, ui)
        c.drawRoundRect(104f, 90f, 192f, 116f, 8f, 8f, ui)
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 12f
        ui.color = 0xFFFFE082.toInt()
        c.drawText("WOOD $wood", 52f, 108f, ui)
        ui.color = 0xFFFF8A80.toInt()
        c.drawText("BERRY $food", 148f, 108f, ui)

        ui.color = 0xB20C1018.toInt()
        c.drawRoundRect(W - 176f, 10f, W - 62f, 50f, 12f, 12f, ui)
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 12f
        ui.color = 0xFFFFFFFF.toInt()
        val night = clock >= DAY
        c.drawText(if (night) "NIGHT $day" else "DAY $day", W - 119f, 28f, ui)
        ui.textSize = 10f
        ui.color = 0xFFFFD54A.toInt()
        val left = if (night) (DAY + NIGHT - clock) else (DAY - clock)
        c.drawText("${left.toInt()}s", W - 119f, 44f, ui)
        ui.color = 0xCC0C1018.toInt()
        c.drawCircle(W - 34f, 30f, 20f, ui)
        ui.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(W - 41f, 20f, W - 36f, 40f, 1.5f, 1.5f, ui)
        c.drawRoundRect(W - 32f, 20f, W - 27f, 40f, 1.5f, 1.5f, ui)

        if (lit) {
            ui.color = 0xB20C1018.toInt()
            c.drawRoundRect(W / 2f - 72f, 10f, W / 2f + 72f, 36f, 8f, 8f, ui)
            ui.color = 0xFFFF8A3D.toInt()
            c.drawRoundRect(W / 2f - 64f, 18f, W / 2f - 64f + 128f * fire.coerceIn(0f, 1f), 28f, 3f, 3f, ui)
            ui.color = 0xFFFFFFFF.toInt()
            ui.textSize = 10f
            c.drawText("FIRE", W / 2f, 27f, ui)
        }

        if (toastT > 0) {
            ui.color = 0xCC10141C.toInt()
            c.drawRoundRect(W / 2f - 210f, 122f, W / 2f + 210f, 154f, 10f, 10f, ui)
            ui.textAlign = Paint.Align.CENTER
            ui.textSize = 14f
            ui.color = 0xFFFFFFFF.toInt()
            c.drawText(toast, W / 2f, 144f, ui)
        }
    }

    private fun drawPad(c: Canvas, W: Int, H: Int) {
        val stickR = 124
        val stick = atlas.fit(atlas.gtStick, stickR, stickR)
        val cx = 104f
        val cy = H - 112f
        c.drawBitmap(stick, cx - stickR / 2f, cy - stickR / 2f, ui)
        val kn = atlas.fit(atlas.gtKnob, 50, 50)
        c.drawBitmap(kn, cx - 25f + sx * 38f, cy - 25f + sy * 38f, ui)

        val atkS = if (atkDown) 108 else 118
        val ax = W - 96f
        val ay = H - 112f
        val atk = atlas.fit(atlas.gtAtk, atkS, atkS)
        c.drawBitmap(atk, ax - atkS / 2f, ay - atkS / 2f, ui)

        val dashS = if (dashing) 70 else 78
        val dx = W - 196f
        val dy = H - 80f
        val dash = atlas.fit(atlas.gtDash, dashS, dashS)
        c.drawBitmap(dash, dx - dashS / 2f, dy - dashS / 2f, ui)

        val skS = 74
        val skx = W - 176f
        val sky = H - 176f
        val old = ui.alpha
        if (food <= 0 && mode == "play") ui.alpha = 120
        val sk = atlas.fit(atlas.gtSkill, skS, skS)
        c.drawBitmap(sk, skx - skS / 2f, sky - skS / 2f, ui)
        ui.alpha = old
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val W = width
        val H = height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val tx = e.getX(i)
                val ty = e.getY(i)
                val id = e.getPointerId(i)
                when {
                    mode == "play" && hypot(tx - (W - 34f), ty - 30f) < 26f -> {
                        paused = !paused
                        audio.playSfx("click", 0.7f)
                    }
                    paused && mode == "play" -> {
                        if (ty > H * 0.58f && ty < H * 0.58f + 50f) {
                            audio.playSfx("click", 0.7f)
                            if (tx < W / 2f) { mode = "title"; reset() } else paused = false
                        }
                    }
                    hypot(tx - 104f, ty - (H - 112f)) < 82f -> {
                        stickId = id; stick(tx, ty, H)
                    }
                    hypot(tx - (W - 96f), ty - (H - 112f)) < 60f -> {
                        atkDown = true
                        interact()
                    }
                    hypot(tx - (W - 196f), ty - (H - 80f)) < 44f -> {
                        dashing = true
                        audio.playSfx("click", 0.35f)
                    }
                    hypot(tx - (W - 176f), ty - (H - 176f)) < 42f -> eat()
                    mode == "dead" && ty > H * 0.65f -> {
                        audio.playSfx("click", 0.7f)
                        if (tx < W / 2f) { mode = "title"; reset() } else { reset(); mode = "play" }
                    }
                    mode != "play" -> interact()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount)
                    if (e.getPointerId(i) == stickId) stick(e.getX(i), e.getY(i), H)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == stickId) {
                    stickId = -1; sx = 0f; sy = 0f
                }
                dashing = false
                atkDown = false
            }
        }
        return true
    }

    private fun stick(px: Float, py: Float, H: Int) {
        var dx = px - 104f
        var dy = py - (H - 112f)
        val len = hypot(dx, dy)
        if (len > 52f) { dx = dx / len * 52f; dy = dy / len * 52f }
        sx = (dx / 52f).coerceIn(-1f, 1f)
        sy = (dy / 52f).coerceIn(-1f, 1f)
    }

    private fun drawPause(c: Canvas, W: Int, H: Int) {
        ui.color = 0xC0051018.toInt()
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ui)
        ui.textAlign = Paint.Align.CENTER
        ui.color = 0xFFFFFFFF.toInt()
        ui.textSize = 28f
        c.drawText("PAUSED", W / 2f, H * 0.38f, ui)
        ui.color = 0xFFFFD54A.toInt()
        c.drawRoundRect(W / 2f + 12f, H * 0.58f, W / 2f + 156f, H * 0.58f + 46f, 10f, 10f, ui)
        ui.color = 0xFFE8E8E8.toInt()
        c.drawRoundRect(W / 2f - 156f, H * 0.58f, W / 2f - 12f, H * 0.58f + 46f, 10f, 10f, ui)
        ui.color = 0xFF2A2418.toInt()
        ui.textSize = 15f
        c.drawText("Lobby", W / 2f - 84f, H * 0.58f + 30f, ui)
        c.drawText("Resume", W / 2f + 84f, H * 0.58f + 30f, ui)
    }
}
