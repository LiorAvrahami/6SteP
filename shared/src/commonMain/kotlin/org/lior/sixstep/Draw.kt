package org.lior.sixstep

/** The colours of the app, as 0xAARRGGBB (the Android app has the same ones in colors.xml and Ui.kt). */
object Colors {
    const val FLOOR: Int = 0xFFFFFFFF.toInt()
    const val FLOOR_GRID: Int = 0xFFE7E7E7.toInt()
    const val LEADER: Int = 0xFF2E6EE6.toInt()
    const val FOLLOWER: Int = 0xFFEB50A0.toInt()
    const val ACCENT: Int = 0xFFF0B432.toInt()       // the annotation's words on the floor
    const val FILLER: Int = 0xFF8A6D00.toInt()       // the title of the triple steps that close a loop
    const val INK: Int = 0xFF222222.toInt()          // the title and the count on the floor
    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val SHOE_GREY: Int = 0xFF787878.toInt()    // a shoe's silhouette, under the dancer's colour
    const val BAR: Int = 0xFFD3D3D3.toInt()          // the dance floor's bars
    const val NOTE: Int = 0xFFBAB186.toInt()         // the annotations in the dance floor's bottom bar
    const val BANNER: Int = 0xFF111118.toInt()       // the box behind an annotation on the floor
    const val LABEL: Int = 0xFF6E6E6E.toInt()        // the count in a step picture
    const val SEPARATOR: Int = 0xFF808080.toInt()
    const val SCROLL: Int = 0xFF9C9C9C.toInt()

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF
    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
}

fun withAlpha(color: Int, alpha: Double): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24)

enum class TextAlign { LEFT, CENTER, RIGHT }

/** the weights of the app's font (Inter) that the drawings use */
enum class FontWeight { SEMIBOLD, BOLD, EXTRABOLD }

/** What the shared drawings need from a canvas: Android's Canvas and the browser's 2D canvas behind one small interface.
 *  Lengths are in the canvas's own pixels; colours are 0xAARRGGBB. Lines made with strokePath and strokeArc have round
 *  ends and corners. The path built with beginPath ... closePath stays until the next beginPath, so it can be filled
 *  and stroked more than once. */
interface Draw {
    fun fillAll(color: Int)
    fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double)
    fun fillRect(l: Double, t: Double, r: Double, b: Double, color: Int)
    fun strokeRect(l: Double, t: Double, r: Double, b: Double, color: Int, width: Double)
    fun fillRoundRect(l: Double, t: Double, r: Double, b: Double, radius: Double, color: Int)

    fun beginPath()
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double)
    fun closePath()
    fun fillPath(color: Int)
    /** dash: the lengths of a dash and of the gap after it, or null for a solid line */
    fun strokePath(color: Int, width: Double, dash: DoubleArray? = null)
    /** a piece of a circle, from startDeg (0 = to the right, 90 = down: the screen's angles run clockwise) over sweepDeg */
    fun strokeArc(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double, color: Int, width: Double)

    fun save()
    fun clipRect(l: Double, t: Double, r: Double, b: Double)
    fun restore()

    /** y is the text's baseline */
    fun text(s: String, x: Double, y: Double, size: Double, color: Int, weight: FontWeight, align: TextAlign = TextAlign.LEFT)
    fun textWidth(s: String, size: Double, weight: FontWeight): Double
}

/** the text cut short with "…" so that it is no wider than maxWidth */
fun ellipsize(d: Draw, s: String, size: Double, weight: FontWeight, maxWidth: Double): String {
    if (d.textWidth(s, size, weight) <= maxWidth) return s
    var n = s.length
    while (n > 1 && d.textWidth(s.substring(0, n).trimEnd() + "…", size, weight) > maxWidth) n--
    return s.substring(0, n).trimEnd() + "…"
}

/** the text broken into lines no wider than maxWidth, at its spaces */
fun wrapText(d: Draw, s: String, size: Double, weight: FontWeight, maxWidth: Double): List<String> {
    val out = ArrayList<String>()
    var line = ""
    for (w in s.split(' ')) {
        val cand = if (line.isEmpty()) w else "$line $w"
        if (d.textWidth(cand, size, weight) > maxWidth && line.isNotEmpty()) { out.add(line); line = w } else line = cand
    }
    if (line.isNotEmpty()) out.add(line)
    return out
}
