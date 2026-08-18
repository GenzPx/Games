package dev.hoshi.thinair

import android.content.Context
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
            if (mode == "play") tick(dt)
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
        val sp = 3.15f
        if (hypot(sx, sy) > 0.08f) {
            dir = if (kotlin.math.abs(sx) > kotlin.math.abs(sy)) if (sx < 0) 2 else 3 else if (sy < 0) 0 else 1
            walk += dt * 9f
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
    }

    private fun say(s: String) { toast = s; toastT = 3.4f }

    private fun pop(tx: Float, ty: Float, s: String) { pops += Pop(tx, ty, 1.1f, s) }

    private fun interact() {
        if (mode != "play") {
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
                pop(fx + 0.5f, fy.toFloat(), "+1 kayu")
                repeat(6) {
                    bits += Bit(fx + 0.5f, fy + 0.5f, rng.nextFloat() * 2 - 1, -1.2f, 0.4f, 0xFF8B5A3C.toInt())
                }
                say("Kayu. Api butuh makan.")
            }
            BUSH -> {
                tiles[fy][fx] = BUSH0
                food++
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
        pop(x, y - 0.5f, "yum")
        say("Kenyang.")
    }

    private fun reset() {
        buildMap()
        hunger = 1f; warmth = 1f; wood = 3; food = 2
        day = 1; clock = 10f; fire = 0.8f; lit = true
        wolves.clear(); bits.clear(); pops.clear()
        sx = 0f; sy = 0f
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
        if (mode == "title") { drawTitle(c, W, H); drawPad(c, W, H); return }

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
            val bob = if (hypot(sx, sy) > 0.1f) sin(walk * 2.2f) * scale * 0.4f else 0f
            val ch = atlas.s(atlas.t(8, 8), scale)
            c.drawBitmap(ch, W / 2f - ch.width / 2f, H / 2f - ch.height / 2f + bob, px)
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
        if (mode == "dead") drawDead(c, W, H)
    }

    private fun drawTitle(c: Canvas, W: Int, H: Int) {
        // fake forest bg
        val tw = TS * scale
        for (j in 0..8) for (i in 0..14) {
            c.drawBitmap(atlas.s(atlas.t(0, 0), scale), (i * tw).toFloat(), (j * tw).toFloat(), px)
            if ((i + j) % 4 == 0) c.drawBitmap(atlas.s(atlas.t(4, 0, 1, 3), scale), (i * tw).toFloat(), (j * tw - 2 * tw).toFloat(), px)
        }
        atlas.nine(c, atlas.btnBrown, W / 2 - 170, H / 2 - 90, 340, 150, px)
        ui.textAlign = Paint.Align.CENTER
        ui.color = 0xFFFFE7A8.toInt()
        ui.textSize = 40f
        c.drawText("EMBER", W / 2f, H / 2f - 28f, ui)
        ui.textSize = 13f
        ui.color = 0xFFD9C59A.toInt()
        c.drawText("HOSHIDEV  ·  jaga apinya", W / 2f, H / 2f, ui)
        ui.textSize = 15f
        ui.color = 0xFFEF7D57.toInt()
        c.drawText("Tap A untuk masuk hutan", W / 2f, H / 2f + 32f, ui)
    }

    private fun drawDead(c: Canvas, W: Int, H: Int) {
        ui.color = 0xB305070C.toInt()
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ui)
        atlas.nine(c, atlas.btnBrown, W / 2 - 180, H / 2 - 80, 360, 150, px)
        ui.textAlign = Paint.Align.CENTER
        ui.color = 0xFFB13E53.toInt()
        ui.textSize = 26f
        c.drawText("API PADAM", W / 2f, H / 2f - 20f, ui)
        ui.color = 0xFFE8E0D2.toInt()
        ui.textSize = 15f
        c.drawText(toast, W / 2f, H / 2f + 10f, ui)
        ui.color = 0xFFC9A227.toInt()
        c.drawText("Tap A — coba lagi", W / 2f, H / 2f + 40f, ui)
    }

    private fun drawHud(c: Canvas, W: Int, H: Int) {
        atlas.nine(c, atlas.btnBrown, 10, 8, 196, 108, px)
        ui.textAlign = Paint.Align.LEFT
        ui.textSize = 13f
        ui.color = 0xFFFFE7A8.toInt()
        val night = clock >= DAY
        c.drawText("HARI $day   ${if (night) "MALAM" else "SIANG"}", 22f, 28f, ui)
        meter(c, 22, 38, 170, hunger, atlas.barRed)
        meter(c, 22, 56, 170, warmth, atlas.barBlue)
        if (lit) meter(c, 22, 74, 170, fire, atlas.barGreen)
        ui.textSize = 10f
        ui.color = 0xEEFFFFFF.toInt()
        c.drawText("LAPAR", 26f, 48f, ui)
        c.drawText("HANGAT", 26f, 66f, ui)
        if (lit) c.drawText("API", 26f, 84f, ui)

        // inventory
        val ix = W / 2 - 70
        val iy = 12
        slot(c, ix, iy, atlas.s(atlas.t(7, 9), 2), wood)
        slot(c, ix + 72, iy, atlas.s(atlas.t(5, 2), 2), food)

        if (toastT > 0) {
            atlas.nine(c, atlas.btnBrown, W / 2 - 190, H - 132, 380, 36, px)
            ui.textAlign = Paint.Align.CENTER
            ui.textSize = 14f
            ui.color = 0xFFFFE7A8.toInt()
            c.drawText(toast, W / 2f, H - 108f, ui)
        }
    }

    private fun meter(c: Canvas, x: Int, y: Int, w: Int, v: Float, fill: android.graphics.Bitmap) {
        atlas.nine(c, atlas.barWhite, x, y, w, 14, px)
        val fw = ((w - 4) * v.coerceIn(0f, 1f)).toInt()
        if (fw > 2) {
            val src = Rect(0, 0, fill.width, fill.height)
            val dst = Rect(x + 2, y + 2, x + 2 + fw, y + 12)
            c.drawBitmap(fill, src, dst, px)
        }
    }

    private fun slot(c: Canvas, x: Int, y: Int, icon: Bitmap, n: Int) {
        atlas.nine(c, atlas.btnGrey, x, y, 64, 36, px)
        c.drawBitmap(icon, x + 6f, y + 4f, px)
        ui.textAlign = Paint.Align.LEFT
        ui.textSize = 16f
        ui.color = 0xFF1A1C2C.toInt()
        c.drawText("x$n", x + 32f, y + 24f, ui)
    }

    private fun drawPad(c: Canvas, W: Int, H: Int) {
        val cx = 88f
        val cy = H - 88f
        c.drawBitmap(atlas.s(atlas.knobDark, 2), cx - atlas.knobDark.width, cy - atlas.knobDark.height, px)
        val kn = atlas.s(atlas.knob, 2)
        c.drawBitmap(kn, cx - kn.width / 2f + sx * 34f, cy - kn.height / 2f + sy * 34f, px)

        val ax = W - 92f
        val ay = H - 96f
        val aBtn = atlas.s(atlas.btnRed, 1)
        c.drawBitmap(aBtn, ax - aBtn.width / 2f, ay - aBtn.height / 2f, px)
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 18f
        ui.color = 0xFFFFF3C4.toInt()
        c.drawText("A", ax, ay + 6f, ui)

        val bx = W - 168f
        val by = H - 72f
        val bBtn = atlas.s(atlas.btnBrown, 1)
        c.drawBitmap(bBtn, bx - bBtn.width / 2f, by - bBtn.height / 2f, px)
        ui.textSize = 12f
        c.drawText("EAT", bx, by + 4f, ui)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val W = width
        val H = height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val px = e.getX(i)
                val py = e.getY(i)
                val id = e.getPointerId(i)
                when {
                    hypot(px - 88f, py - (H - 88f)) < 78f -> {
                        stickId = id; stick(px, py, H)
                    }
                    hypot(px - (W - 92f), py - (H - 96f)) < 46f -> interact()
                    hypot(px - (W - 168f), py - (H - 72f)) < 40f -> eat()
                    mode != "play" -> interact()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount)
                    if (e.getPointerId(i) == stickId) stick(e.getX(i), e.getY(i), H)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.getPointerId(e.actionIndex) == stickId) {
                    stickId = -1; sx = 0f; sy = 0f
                }
            }
        }
        return true
    }

    private fun stick(px: Float, py: Float, H: Int) {
        var dx = px - 88f
        var dy = py - (H - 88f)
        val len = hypot(dx, dy)
        if (len > 52f) { dx = dx / len * 52f; dy = dy / len * 52f }
        sx = (dx / 52f).coerceIn(-1f, 1f)
        sy = (dy / 52f).coerceIn(-1f, 1f)
    }
}
