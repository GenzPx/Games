package dev.hoshi.thinair

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/** Tiny pixel factory. Chars in rows map to palette; '.' is transparent. */
object Pal {
    const val VOID = 0x00000000
    val ink = 0xFF1A1C2C.toInt()
    val night = 0xFF252446.toInt()
    val bark = 0xFF5D3A2E.toInt()
    val wood = 0xFF8B5A3C.toInt()
    val dirt = 0xFF6B5344.toInt()
    val grass = 0xFF3E7A3A.toInt()
    val grass2 = 0xFF56A04A.toInt()
    val leaf = 0xFF2F6B38.toInt()
    val leaf2 = 0xFF4F9A48.toInt()
    val berry = 0xFFB13E53.toInt()
    val skin = 0xFFE0B08A.toInt()
    val cloth = 0xFFC45C26.toInt()
    val cloth2 = 0xFFEF7D57.toInt()
    val fire = 0xFFFFCD75.toInt()
    val flame = 0xFFEF7D57.toInt()
    val white = 0xFFF4F4F4.toInt()
    val stone = 0xFF7A8494.toInt()
    val stone2 = 0xFF566C86.toInt()
    val water = 0xFF3B5DC9.toInt()
    val water2 = 0xFF41A6F6.toInt()
    val wolf = 0xFF6A6A78.toInt()
    val wolf2 = 0xFF3A3A48.toInt()
    val gold = 0xFFC9A227.toInt()
}

object Sprites {
    private val cache = HashMap<String, Bitmap>()

    fun get(key: String, rows: Array<String>, pal: Map<Char, Int>): Bitmap {
        cache[key]?.let { return it }
        val h = rows.size
        val w = rows.maxOf { it.length }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in rows.indices) {
            val row = rows[y]
            for (x in row.indices) {
                val c = pal[row[x]] ?: Pal.VOID
                if (c ushr 24 != 0) bmp.setPixel(x, y, c)
            }
        }
        cache[key] = bmp
        return bmp
    }

    private val P = mapOf(
        'k' to Pal.ink, 'n' to Pal.night,
        'b' to Pal.bark, 'w' to Pal.wood, 'd' to Pal.dirt,
        'g' to Pal.grass, 'G' to Pal.grass2, 'l' to Pal.leaf, 'L' to Pal.leaf2,
        'r' to Pal.berry, 's' to Pal.skin, 'c' to Pal.cloth, 'C' to Pal.cloth2,
        'f' to Pal.fire, 'F' to Pal.flame, 'W' to Pal.white,
        'S' to Pal.stone, 't' to Pal.stone2, 'u' to Pal.water, 'U' to Pal.water2,
        'a' to Pal.wolf, 'A' to Pal.wolf2, 'y' to Pal.gold,
    )

    fun grass() = get("g", arrayOf(
        "ggGg",
        "GggG",
        "gGgg",
        "ggGg",
    ), P)

    fun dirt() = get("d", arrayOf(
        "dddd",
        "dbdd",
        "dddd",
        "ddbd",
    ), P)

    fun water(frame: Int) = get("wa$frame", if (frame % 2 == 0) arrayOf(
        "uUuU",
        "UuUu",
        "uUuU",
        "UuUu",
    ) else arrayOf(
        "UuUu",
        "uUuU",
        "UuUu",
        "uUuU",
    ), P)

    fun tree() = get("tree", arrayOf(
        "..LLL...",
        ".LLlLL..",
        "LLlLlLL.",
        ".LlblL..",
        "..LbL...",
        "...bb...",
        "...bb...",
        "..bddb..",
    ), P)

    fun stump() = get("stump", arrayOf(
        "........",
        "........",
        "........",
        "........",
        "...ww...",
        "..wbbw..",
        "...bb...",
        "..bddb..",
    ), P)

    fun bush(has: Boolean) = get(if (has) "bush1" else "bush0", arrayOf(
        if (has) ".LlLrL.." else ".LlLlL..",
        if (has) "LlrLlL.." else "LlLlLL..",
        "lLllLl..",
        ".llll...",
        "..gg....",
        "........",
        "........",
        "........",
    ), P)

    fun rock() = get("rock", arrayOf(
        "........",
        "........",
        "...SS...",
        "..SttS..",
        ".SttttS.",
        ".StSttS.",
        "..SStS..",
        "...gg...",
    ), P)

    fun tent() = get("tent", arrayOf(
        "...cc...",
        "..cCCc..",
        ".cCCCCc.",
        "cCCCCCCc",
        "cCkCCkCc",
        "cCCCCCCc",
        "ccCCCCcc",
        ".dddddd.",
    ), P)

    fun fire(frame: Int) = get("fire$frame", when (frame % 3) {
        0 -> arrayOf(
            "........",
            "...F....",
            "..FfF...",
            ".FfyfF..",
            "..FwF...",
            "...ww...",
            "..w..w..",
            "...dd...",
        )
        1 -> arrayOf(
            "........",
            "....F...",
            "...Ff...",
            "..FyFf..",
            ".FfwfF..",
            "...ww...",
            "..w..w..",
            "...dd...",
        )
        else -> arrayOf(
            "........",
            "...f....",
            "..FFf...",
            ".FfFyF..",
            "..fFf...",
            "...ww...",
            "..w..w..",
            "...dd...",
        )
    }, P)

    fun player(dir: Int, walk: Int): Bitmap {
        val key = "p$dir$walk"
        val leg = if (walk % 2 == 0) "c" else "C"
        val face = when (dir) {
            1 -> arrayOf( // down
                "..kkkk..",
                ".kssssk.",
                ".ksksks.",
                ".kssssk.",
                "..cccc..",
                ".cCCCCc.",
                "..c${leg}c...",
                "..k..k..",
            )
            2 -> arrayOf( // left
                "..kkkk..",
                ".kssssk.",
                ".kssk...",
                ".kssssk.",
                "..cccc..",
                ".cCCCCc.",
                "...${leg}c...",
                "..k.k...",
            )
            3 -> arrayOf( // right
                "..kkkk..",
                ".kssssk.",
                "...kssk.",
                ".kssssk.",
                "..cccc..",
                ".cCCCCc.",
                "...c${leg}...",
                "...k.k..",
            )
            else -> arrayOf( // up
                "..kkkk..",
                ".kkkkkk.",
                ".kkkkkk.",
                ".kssssk.",
                "..cccc..",
                ".cCCCCc.",
                "..c${leg}c...",
                "..k..k..",
            )
        }
        return get(key, face, P)
    }

    fun wolf(frame: Int) = get("wolf$frame", if (frame % 2 == 0) arrayOf(
        "........",
        ".Aaaa...",
        "AaaAaA..",
        ".aaaaa..",
        "..aaaa..",
        "..a..a..",
        "..A..A..",
        "........",
    ) else arrayOf(
        "........",
        ".Aaaa...",
        "AaaAaA..",
        ".aaaaa..",
        "..aaaa..",
        ".a....a.",
        ".A....A.",
        "........",
    ), P)

    fun woodIcon() = get("iwood", arrayOf(
        "........",
        ".ww.....",
        ".bww....",
        "..bww...",
        "...bww..",
        "....bw..",
        "........",
        "........",
    ), P)

    fun foodIcon() = get("ifood", arrayOf(
        "........",
        "..LrL...",
        ".LrrrL..",
        "..LrL...",
        "...l....",
        "........",
        "........",
        "........",
    ), P)

    fun star() = get("star", arrayOf(
        "...W....",
        "...W....",
        ".WWWWW..",
        "...W....",
        "...W....",
        "........",
        "........",
        "........",
    ), P)

    fun logoMark(): Bitmap {
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val rows = arrayOf(
            "......y.......",
            ".....y.y......",
            "....y.W.y.....",
            "...y..W..y....",
            "..y...W...y...",
            ".y....W....y..",
            "y.....W.....y.",
            ".yyyyyyyyyyy..",
            "......k.......",
            ".....kkk......",
            "....kkkkk.....",
            "...kkkkkkk....",
            "........k.....",
            ".......kk.....",
            "......kkk.....",
            "..............",
        )
        for (y in rows.indices) for (x in rows[y].indices) {
            val col = P[rows[y][x]] ?: continue
            if (col ushr 24 != 0) b.setPixel(x, y, col)
        }
        return b
    }

    fun scale(src: Bitmap, n: Int): Bitmap {
        val key = "sc${src.hashCode()}_$n"
        cache[key]?.let { return it }
        val out = Bitmap.createScaledBitmap(src, src.width * n, src.height * n, false)
        cache[key] = out
        return out
    }
}

fun Canvas.blit(bmp: Bitmap, x: Float, y: Float, paint: Paint) {
    drawBitmap(bmp, x, y, paint)
}
