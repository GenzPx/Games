package dev.hoshi.thinair

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private const val MAP = 40
private const val TS = 16
private const val DAY_LEN = 70f
private const val NIGHT_LEN = 50f

private const val T_GRASS = 0
private const val T_DIRT = 1
private const val T_WATER = 2
private const val T_TREE = 3
private const val T_STUMP = 4
private const val T_BUSH = 5
private const val T_BUSH_EMPTY = 6
private const val T_ROCK = 7

class GameView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false

    private val px = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val ui = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nightPaint = Paint()
    private val lightPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    private val tiles = Array(MAP) { IntArray(MAP) }
    private val rng = Random(7)

    private var pxw = 20f
    private var pyw = 20f
    private var pvx = 0f
    private var pvy = 0f
    private var dir = 1
    private var walk = 0f
    private var hunger = 1f
    private var warmth = 1f
    private var wood = 2
    private var food = 2
    private var day = 1
    private var tod = 8f
    private var fireX = 20f
    private var fireY = 20f
    private var fireFuel = 0.7f
    private var hasFire = true
    private var mode = "title" // title play dead
    private var prompt = "Jaga apinya. Jangan kedinginan."
    private var promptT = 3f
    private val wolves = ArrayList<Wolf>()

    private var stickId = -1
    private var stickX = 0f
    private var stickY = 0f
    private var stickNx = 0f
    private var stickNy = 0f
    private var scale = 5
    private var camX = 0f
    private var camY = 0f

    data class Wolf(var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f)

    init {
        holder.addCallback(this)
        isFocusable = true
        gen()
    }

    private fun gen() {
        for (y in 0 until MAP) for (x in 0 until MAP) {
            val edge = x < 2 || y < 2 || x > MAP - 3 || y > MAP - 3
            tiles[y][x] = when {
                edge -> T_WATER
                hypot((x - 20).toFloat(), (y - 20).toFloat()) < 4.2f -> T_DIRT
                rng.nextFloat() < 0.07f && hypot((x - 12).toFloat(), (y - 28).toFloat()) < 5f -> T_WATER
                rng.nextFloat() < 0.16f -> T_TREE
                rng.nextFloat() < 0.06f -> T_BUSH
                rng.nextFloat() < 0.04f -> T_ROCK
                else -> T_GRASS
            }
        }
        // clearing
        for (y in 17..23) for (x in 17..23) {
            if (tiles[y][x] == T_TREE || tiles[y][x] == T_WATER) tiles[y][x] = T_DIRT
        }
        tiles[18][18] = T_GRASS
        pxw = 20.2f; pyw = 21.2f
        fireX = 20.0f; fireY = 20.0f
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this, "ember").also { it.start() }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        thread?.join(400)
        thread = null
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        scale = (minOf(width / (16 * 18), height / (16 * 12))).coerceIn(3, 8)
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            last = now
            if (mode == "play") tick(dt)
            val c = holder.lockCanvas()
            if (c != null) {
                try { drawGame(c) } finally { holder.unlockCanvasAndPost(c) }
            }
            val frame = System.nanoTime() - now
            val sleep = 16_000_000L - frame / 1
            if (sleep > 1_000_000) try { Thread.sleep(sleep / 1_000_000) } catch (_: InterruptedException) {}
        }
    }

    private fun blocked(tx: Int, ty: Int): Boolean {
        if (tx !in 0 until MAP || ty !in 0 until MAP) return true
        return when (tiles[ty][tx]) {
            T_WATER, T_TREE, T_ROCK -> true
            else -> false
        }
    }

    private fun tick(dt: Float) {
        val speed = 3.4f
        pvx = stickNx * speed
        pvy = stickNy * speed
        if (hypot(pvx, pvy) > 0.05f) {
            dir = when {
                kotlin.math.abs(pvx) > kotlin.math.abs(pvy) -> if (pvx < 0) 2 else 3
                pvy < 0 -> 0
                else -> 1
            }
            walk += dt * 8f
        }
        val nx = pxw + pvx * dt
        val ny = pyw + pvy * dt
        if (!blocked(floor(nx).toInt(), floor(pyw).toInt())) pxw = nx.coerceIn(1.2f, MAP - 1.2f)
        if (!blocked(floor(pxw).toInt(), floor(ny).toInt())) pyw = ny.coerceIn(1.2f, MAP - 1.2f)

        tod += dt
        val cycle = DAY_LEN + NIGHT_LEN
        if (tod >= cycle) {
            tod -= cycle
            day++
            say("Hari $day. Cari kayu sebelum gelap.")
            // refill some bushes
            for (y in 0 until MAP) for (x in 0 until MAP) {
                if (tiles[y][x] == T_BUSH_EMPTY && rng.nextFloat() < 0.55f) tiles[y][x] = T_BUSH
            }
        }
        val night = tod >= DAY_LEN
        val nightT = if (night) ((tod - DAY_LEN) / NIGHT_LEN) else 0f

        hunger = (hunger - dt * 0.012f).coerceIn(0f, 1f)
        val nearFire = hasFire && fireFuel > 0.02f && hypot(pxw - fireX, pyw - fireY) < 3.2f
        if (night && !nearFire) warmth -= dt * 0.055f
        else if (nearFire) warmth = (warmth + dt * 0.12f).coerceAtMost(1f)
        else warmth = (warmth + dt * 0.01f).coerceAtMost(1f)
        if (hasFire) fireFuel = (fireFuel - dt * 0.018f).coerceAtLeast(0f)
        if (fireFuel <= 0f) hasFire = false

        if (night) {
            if (wolves.size < 2 + day / 3 && rng.nextFloat() < dt * 0.25f) {
                val side = rng.nextInt(4)
                val wx = if (side == 0) 3f else if (side == 1) MAP - 3f else rng.nextInt(MAP).toFloat()
                val wy = if (side == 2) 3f else if (side == 3) MAP - 3f else rng.nextInt(MAP).toFloat()
                wolves += Wolf(wx, wy)
            }
        } else {
            wolves.clear()
        }
        val it = wolves.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val dfx = w.x - fireX
            val dfy = w.y - fireY
            val fireD = hypot(dfx, dfy)
            if (hasFire && fireFuel > 0 && fireD < 4.5f) {
                w.vx = dfx / (fireD + 0.1f) * 2.2f
                w.vy = dfy / (fireD + 0.1f) * 2.2f
            } else {
                val dx = pxw - w.x
                val dy = pyw - w.y
                val d = hypot(dx, dy) + 0.01f
                w.vx = dx / d * 1.6f
                w.vy = dy / d * 1.6f
                if (d < 0.7f) {
                    warmth -= 0.18f
                    hunger -= 0.05f
                    say("Serigala!")
                    w.x -= dx / d * 2f
                    w.y -= dy / d * 2f
                }
            }
            w.x += w.vx * dt
            w.y += w.vy * dt
            if (w.x < 2 || w.y < 2 || w.x > MAP - 2 || w.y > MAP - 2) it.remove()
        }

        if (promptT > 0) promptT -= dt
        if (hunger <= 0f) die("Kamu kelaparan.")
        else if (warmth <= 0f) die("Kamu kedinginan.")
    }

    private fun die(why: String) {
        mode = "dead"
        prompt = "$why  Malam ke-$day."
        promptT = 99f
        wolves.clear()
    }

    private fun say(s: String) {
        prompt = s
        promptT = 3.2f
    }

    private fun interact() {
        if (mode == "title") {
            reset(); mode = "play"; say("Ambil kayu. Nyalakan api sebelum malam.")
            return
        }
        if (mode == "dead") {
            reset(); mode = "play"; say("Coba lagi. Jaga apinya.")
            return
        }
        val fx = floor(pxw + when (dir) { 2 -> -0.8f; 3 -> 0.8f; else -> 0f }).toInt()
        val fy = floor(pyw + when (dir) { 0 -> -0.8f; 1 -> 0.8f; else -> 0f }).toInt()
        if (fx !in 0 until MAP || fy !in 0 until MAP) return
        when (tiles[fy][fx]) {
            T_TREE -> {
                tiles[fy][fx] = T_STUMP
                wood++
                say("Kayu +1")
            }
            T_BUSH -> {
                tiles[fy][fx] = T_BUSH_EMPTY
                food++
                say("Berry +1")
            }
            else -> {
                if (wood >= 3 && !hasFire) {
                    fireX = pxw; fireY = pyw; hasFire = true; fireFuel = 0.85f; wood -= 3
                    say("Api menyala.")
                } else if (wood >= 1 && hasFire && hypot(pxw - fireX, pyw - fireY) < 2.2f) {
                    wood--; fireFuel = (fireFuel + 0.35f).coerceAtMost(1f)
                    say("Kayu ke api.")
                } else {
                    say("Hadap pohon / semak. 3 kayu = api baru.")
                }
            }
        }
    }

    private fun eat() {
        if (mode != "play") return
        if (food <= 0) { say("Makanan habis."); return }
        food--; hunger = (hunger + 0.38f).coerceAtMost(1f)
        say("Kenyang.")
    }

    private fun reset() {
        gen()
        hunger = 1f; warmth = 1f; wood = 2; food = 2
        day = 1; tod = 8f; fireFuel = 0.7f; hasFire = true
        wolves.clear()
        stickNx = 0f; stickNy = 0f
    }

    private fun nightAmt(): Float {
        if (tod < DAY_LEN - 8f) return 0f
        if (tod < DAY_LEN) return (tod - (DAY_LEN - 8f)) / 8f
        if (tod > DAY_LEN + NIGHT_LEN - 8f) return 1f - (tod - (DAY_LEN + NIGHT_LEN - 8f)) / 8f
        return 1f
    }

    private fun drawGame(c: Canvas) {
        val w = c.width
        val h = c.height
        c.drawColor(Pal.ink)

        if (mode == "title") {
            drawTitle(c, w, h)
            drawControls(c, w, h, dim = true)
            return
        }

        val tw = TS * scale
        camX = pxw * tw - w / 2f + tw / 2f
        camY = pyw * tw - h / 2f + tw / 2f
        val frame = (System.currentTimeMillis() / 180).toInt()

        val x0 = (camX / tw).toInt() - 1
        val y0 = (camY / tw).toInt() - 1
        val x1 = x0 + w / tw + 3
        val y1 = y0 + h / tw + 3
        for (ty in y0..y1) for (tx in x0..x1) {
            if (tx !in 0 until MAP || ty !in 0 until MAP) continue
            val sx = tx * tw - camX
            val sy = ty * tw - camY
            val t = tiles[ty][tx]
            val ground = when (t) {
                T_DIRT, T_STUMP -> Sprites.dirt()
                T_WATER -> Sprites.water(frame)
                else -> Sprites.grass()
            }
            c.drawBitmap(Sprites.scale(ground, scale), sx, sy, px)
            val deco = when (t) {
                T_TREE -> Sprites.tree()
                T_STUMP -> Sprites.stump()
                T_BUSH -> Sprites.bush(true)
                T_BUSH_EMPTY -> Sprites.bush(false)
                T_ROCK -> Sprites.rock()
                else -> null
            }
            if (deco != null) {
                val d = Sprites.scale(deco, scale)
                c.drawBitmap(d, sx + (tw - d.width) / 2f, sy + tw - d.height, px)
            }
        }

        // tent
        val tent = Sprites.scale(Sprites.tent(), scale)
        c.drawBitmap(tent, 18 * tw - camX, 18 * tw - camY - scale * 2, px)

        if (hasFire && fireFuel > 0) {
            val fb = Sprites.scale(Sprites.fire(frame), scale)
            c.drawBitmap(fb, fireX * tw - camX - fb.width / 2f, fireY * tw - camY - fb.height + scale * 4, px)
        }

        for (wolf in wolves) {
            val wb = Sprites.scale(Sprites.wolf(frame), scale)
            c.drawBitmap(wb, wolf.x * tw - camX - wb.width / 2f, wolf.y * tw - camY - wb.height + scale, px)
        }

        val pb = Sprites.scale(Sprites.player(dir, walk.toInt()), scale)
        c.drawBitmap(pb, w / 2f - pb.width / 2f, h / 2f - pb.height / 2f, px)

        val night = nightAmt()
        if (night > 0.02f) {
            val save = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
            nightPaint.color = Color.argb((200 * night).toInt(), 8, 10, 22)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), nightPaint)
            if (hasFire && fireFuel > 0) {
                val cx = fireX * tw - camX
                val cy = fireY * tw - camY
                val r = 5.2f * tw * (0.75f + 0.25f * fireFuel)
                lightPaint.shader = RadialGradient(cx, cy, r, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, lightPaint)
            }
            val pr = 2.1f * tw
            lightPaint.shader = RadialGradient(w / 2f, h / 2f, pr, 0xAAFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(w / 2f, h / 2f, pr, lightPaint)
            c.restoreToCount(save)
        }

        drawHud(c, w, h)
        drawControls(c, w, h, dim = false)
        if (mode == "dead") drawDead(c, w, h)
    }

    private fun drawTitle(c: Canvas, w: Int, h: Int) {
        ui.color = Pal.gold
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 42f
        ui.isFakeBoldText = true
        c.drawText("EMBER", w / 2f, h * 0.32f, ui)
        ui.textSize = 14f
        ui.isFakeBoldText = false
        ui.color = 0xFFC9C2B4.toInt()
        c.drawText("HOSHIDEV", w / 2f, h * 0.32f + 22f, ui)
        ui.textSize = 16f
        c.drawText("Jaga api. Cari kayu. Jangan kedinginan.", w / 2f, h * 0.48f, ui)
        ui.color = Pal.fire
        c.drawText("TAP PANJAT / A  untuk mulai", w / 2f, h * 0.58f, ui)
        val mark = Sprites.scale(Sprites.logoMark(), 4)
        c.drawBitmap(mark, w / 2f - mark.width / 2f, h * 0.16f, px)
    }

    private fun drawDead(c: Canvas, w: Int, h: Int) {
        ui.color = 0xAA05070C.toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ui)
        ui.color = Pal.berry
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 28f
        ui.isFakeBoldText = true
        c.drawText("API PADAM", w / 2f, h * 0.42f, ui)
        ui.isFakeBoldText = false
        ui.textSize = 16f
        ui.color = 0xFFE8E0D2.toInt()
        c.drawText(prompt, w / 2f, h * 0.50f, ui)
        ui.color = Pal.gold
        c.drawText("Tap A untuk coba lagi", w / 2f, h * 0.60f, ui)
    }

    private fun drawHud(c: Canvas, w: Int, h: Int) {
        ui.textAlign = Paint.Align.LEFT
        ui.textSize = 14f
        ui.color = 0xFFE8E0D2.toInt()
        val night = tod >= DAY_LEN
        c.drawText("HARI $day   ${if (night) "MALAM" else "SIANG"}", 16f, 28f, ui)
        bar(c, 16f, 40f, 140f, hunger, Pal.berry, "LAPAR")
        bar(c, 16f, 62f, 140f, warmth, Pal.flame, "HANGAT")
        if (hasFire) bar(c, 16f, 84f, 140f, fireFuel, Pal.fire, "API")

        val iw = Sprites.scale(Sprites.woodIcon(), 3)
        val ifo = Sprites.scale(Sprites.foodIcon(), 3)
        c.drawBitmap(iw, 16f, 104f, px)
        ui.color = Pal.white
        ui.textSize = 16f
        c.drawText("x$wood", 16f + iw.width + 6f, 104f + 22f, ui)
        c.drawBitmap(ifo, 90f, 104f, px)
        c.drawText("x$food", 90f + ifo.width + 6f, 104f + 22f, ui)

        if (promptT > 0) {
            ui.textAlign = Paint.Align.CENTER
            ui.textSize = 15f
            ui.color = Pal.gold
            c.drawText(prompt, w / 2f, h - 118f, ui)
        }
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, v: Float, col: Int, label: String) {
        ui.color = 0x66000000
        c.drawRoundRect(x, y, x + w, y + 12f, 4f, 4f, ui)
        ui.color = col
        c.drawRoundRect(x + 1, y + 1, x + 1 + (w - 2) * v.coerceIn(0f, 1f), y + 11f, 3f, 3f, ui)
        ui.color = 0xCCFFFFFF.toInt()
        ui.textSize = 9f
        ui.textAlign = Paint.Align.LEFT
        c.drawText(label, x + 4f, y + 10f, ui)
    }

    private fun drawControls(c: Canvas, w: Int, h: Int, dim: Boolean) {
        val cx = 90f
        val cy = h - 90f
        val r = 64f
        ui.style = Paint.Style.STROKE
        ui.strokeWidth = 3f
        ui.color = if (dim) 0x33E8E0D2 else 0x66E8E0D2
        c.drawCircle(cx, cy, r, ui)
        ui.style = Paint.Style.FILL
        ui.color = 0x99C9A227.toInt()
        c.drawCircle(cx + stickNx * 36f, cy + stickNy * 36f, 22f, ui)

        // A interact
        val ax = w - 86f
        val ay = h - 96f
        ui.color = 0xCCEF7D57.toInt()
        c.drawCircle(ax, ay, 36f, ui)
        ui.color = Pal.ink
        ui.textAlign = Paint.Align.CENTER
        ui.textSize = 16f
        c.drawText("A", ax, ay + 6f, ui)
        // B eat
        val bx = w - 160f
        val by = h - 70f
        ui.color = 0xCC56A04A.toInt()
        c.drawCircle(bx, by, 26f, ui)
        ui.color = Pal.ink
        ui.textSize = 14f
        c.drawText("EAT", bx, by + 5f, ui)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width
        val h = height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val id = e.getPointerId(i)
                if (hypot(x - 90f, y - (h - 90f)) < 80f) {
                    stickId = id
                    updateStick(x, y, h)
                } else if (hypot(x - (w - 86f), y - (h - 96f)) < 48f) {
                    interact()
                } else if (hypot(x - (w - 160f), y - (h - 70f)) < 36f) {
                    eat()
                } else if (mode != "play") {
                    interact()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == stickId) updateStick(e.getX(i), e.getY(i), h)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == stickId) {
                    stickId = -1
                    stickNx = 0f
                    stickNy = 0f
                }
            }
        }
        return true
    }

    private fun updateStick(x: Float, y: Float, h: Int) {
        val cx = 90f
        val cy = h - 90f
        var dx = x - cx
        var dy = y - cy
        val len = hypot(dx, dy)
        if (len > 54f) { dx = dx / len * 54f; dy = dy / len * 54f }
        stickNx = (dx / 54f).coerceIn(-1f, 1f)
        stickNy = (dy / 54f).coerceIn(-1f, 1f)
    }
}
