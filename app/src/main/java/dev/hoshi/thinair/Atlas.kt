package dev.hoshi.thinair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

class Atlas(ctx: Context) {
    val town: Bitmap
    val dung: Bitmap
    val btnBrown: Bitmap
    val btnRed: Bitmap
    val btnGrey: Bitmap
    val banner: Bitmap
    val barRed: Bitmap
    val barGreen: Bitmap
    val barBlue: Bitmap
    val barWhite: Bitmap
    val knob: Bitmap
    val knobDark: Bitmap
    val gtAtk: Bitmap
    val gtDash: Bitmap
    val gtSkill: Bitmap
    val gtStick: Bitmap
    val gtKnob: Bitmap
    val gtFace: Bitmap
    val knightD: Bitmap
    val knightL: Bitmap
    val knightR: Bitmap
    val knightU: Bitmap

    private val crop = HashMap<String, Bitmap>()
    private val scaled = HashMap<String, Bitmap>()
    private val src = Rect()
    private val dst = Rect()

    init {
        val o = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        fun a(path: String) = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, o)!! }
        town = a("gfx/town.png")
        dung = a("gfx/dungeon.png")
        btnBrown = a("ui/d_button_brown.png")
        btnRed = a("ui/d_button_red.png")
        btnGrey = a("ui/button_grey.png")
        banner = a("ui/banner_hanging.png")
        barRed = a("ui/d_progress_red.png")
        barGreen = a("ui/d_progress_green.png")
        barBlue = a("ui/d_progress_blue.png")
        barWhite = a("ui/d_progress_white.png")
        knob = a("ui/round_brown.png")
        knobDark = a("ui/round_brown_dark.png")
        gtAtk = a("ui/gt_atk.png")
        gtDash = a("ui/gt_dash.png")
        gtSkill = a("ui/gt_skill.png")
        gtStick = a("ui/gt_stick.png")
        gtKnob = a("ui/gt_knob.png")
        gtFace = a("ui/gt_portrait.png")
        knightD = a("gfx/knight_d.png")
        knightL = a("gfx/knight_l.png")
        knightR = a("gfx/knight_r.png")
        knightU = a("gfx/knight_u.png")
    }

    fun hero(dir: Int): Bitmap = when (dir) {
        0 -> knightU
        2 -> knightL
        3 -> knightR
        else -> knightD
    }

    fun fit(srcBmp: Bitmap, w: Int, h: Int): Bitmap {
        val ww = w.coerceAtLeast(1)
        val hh = h.coerceAtLeast(1)
        val k = "f${System.identityHashCode(srcBmp)}_${ww}x$hh"
        scaled[k]?.let { return it }
        val out = Bitmap.createScaledBitmap(srcBmp, ww, hh, true)
        scaled[k] = out
        return out
    }

    fun t(c: Int, r: Int, w: Int = 1, h: Int = 1): Bitmap {
        val k = "t$c,$r,$w,$h"
        crop[k]?.let { return it }
        val b = Bitmap.createBitmap(town, c * 16, r * 16, w * 16, h * 16)
        crop[k] = b
        return b
    }

    fun d(c: Int, r: Int, w: Int = 1, h: Int = 1): Bitmap {
        val k = "d$c,$r,$w,$h"
        crop[k]?.let { return it }
        val b = Bitmap.createBitmap(dung, c * 16, r * 16, w * 16, h * 16)
        crop[k] = b
        return b
    }

    fun s(srcBmp: Bitmap, n: Int): Bitmap {
        val k = "${System.identityHashCode(srcBmp)}_$n"
        scaled[k]?.let { return it }
        val out = Bitmap.createScaledBitmap(srcBmp, srcBmp.width * n, srcBmp.height * n, false)
        scaled[k] = out
        return out
    }

    fun nine(c: Canvas, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, p: Paint) {
        val bw = bmp.width
        val bh = bmp.height
        val m = (minOf(bw, bh) / 3).coerceAtLeast(4)
        fun blit(sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
            if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
            src.set(sx, sy, sx + sw, sy + sh)
            dst.set(dx, dy, dx + dw, dy + dh)
            c.drawBitmap(bmp, src, dst, p)
        }
        blit(0, 0, m, m, x, y, m, m)
        blit(bw - m, 0, m, m, x + w - m, y, m, m)
        blit(0, bh - m, m, m, x, y + h - m, m, m)
        blit(bw - m, bh - m, m, m, x + w - m, y + h - m, m, m)
        blit(m, 0, bw - 2 * m, m, x + m, y, w - 2 * m, m)
        blit(m, bh - m, bw - 2 * m, m, x + m, y + h - m, w - 2 * m, m)
        blit(0, m, m, bh - 2 * m, x, y + m, m, h - 2 * m)
        blit(bw - m, m, m, bh - 2 * m, x + w - m, y + m, m, h - 2 * m)
        blit(m, m, bw - 2 * m, bh - 2 * m, x + m, y + m, w - 2 * m, h - 2 * m)
    }
}
