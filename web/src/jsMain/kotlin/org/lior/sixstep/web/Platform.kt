package org.lior.sixstep.web

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.lior.sixstep.Colors
import org.lior.sixstep.Draw
import org.lior.sixstep.FontWeight
import org.lior.sixstep.TextAlign
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Node
import kotlin.math.PI

// ------------------------------------------------------------------ the page's elements

/** a new element with its class, its text and whatever `build` adds */
fun el(tag: String, cls: String = "", text: String? = null, build: (HTMLElement.() -> Unit)? = null): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (cls.isNotEmpty()) e.className = cls
    if (text != null) e.textContent = text
    build?.invoke(e)
    return e
}

fun HTMLElement.add(child: Node): HTMLElement { appendChild(child); return this }

/** one of the Lucide icons (icons/<name>.svg) */
fun icon(name: String, alt: String = ""): HTMLImageElement {
    val i = document.createElement("img") as HTMLImageElement
    i.src = "icons/$name.svg"; i.alt = alt
    i.asDynamic().draggable = false
    return i
}

/** a button with an icon; `label` is what a screen reader and a mouse pointer are told */
fun iconButton(name: String, label: String, cls: String = "iconbtn press", onClick: () -> Unit): HTMLElement =
    el("button", cls) {
        title = label
        setAttribute("aria-label", label)
        add(icon(name))
        onclick = { it.stopPropagation(); onClick(); null }
    }

fun nowMs(): Double = window.performance.now()

// ------------------------------------------------------------------ what the page remembers (the browser's local storage)

/** The same names as the Android app's preferences. A browser that refuses storage (private mode) just forgets. */
object Prefs {
    private const val PREFIX = "6step."
    private fun raw(key: String): String? = try { localStorage.getItem(PREFIX + key) } catch (e: Throwable) { null }
    fun bool(key: String, fallback: Boolean): Boolean = when (raw(key)) { "true" -> true; "false" -> false; else -> fallback }
    fun int(key: String, fallback: Int): Int = raw(key)?.toIntOrNull() ?: fallback
    fun str(key: String, fallback: String): String = raw(key) ?: fallback
    fun put(key: String, value: Any) { try { localStorage.setItem(PREFIX + key, value.toString()) } catch (e: Throwable) {} }
}

// ------------------------------------------------------------------ the shared drawings on a browser canvas

/** A canvas kept as sharp as the screen: its drawing buffer follows its size on the page times the screen's pixel
 *  ratio, and the drawing is done in the page's own px. */
class Stage(val canvas: HTMLCanvasElement) {
    val g: CanvasRenderingContext2D = canvas.getContext("2d") as CanvasRenderingContext2D
    var width = 0.0
        private set
    var height = 0.0
        private set
    val draw = CanvasDraw(g)

    /** fit the buffer to the size on the page; true when the size changed */
    fun fit(): Boolean {
        val w = canvas.clientWidth.toDouble(); val h = canvas.clientHeight.toDouble()
        val dpr = window.devicePixelRatio
        val bw = (w * dpr).toInt(); val bh = (h * dpr).toInt()
        val changed = w != width || h != height || canvas.width != bw || canvas.height != bh
        if (changed) { canvas.width = bw; canvas.height = bh; width = w; height = h }
        g.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
        draw.width = w; draw.height = h
        return changed
    }
}

class CanvasDraw(private val g: CanvasRenderingContext2D) : Draw {
    var width = 0.0
    var height = 0.0
    private val dg: dynamic = g

    private fun css(c: Int): String {
        val a = Colors.alpha(c)
        return if (a == 255) "rgb(${Colors.red(c)},${Colors.green(c)},${Colors.blue(c)})"
        else "rgba(${Colors.red(c)},${Colors.green(c)},${Colors.blue(c)},${a / 255.0})"
    }
    private fun font(size: Double, weight: FontWeight): String {
        val w = when (weight) { FontWeight.SEMIBOLD -> 600; FontWeight.BOLD -> 700; FontWeight.EXTRABOLD -> 800 }
        return "$w ${size}px Inter, system-ui, sans-serif"
    }
    private fun solid() { dg.setLineDash(arrayOf<Double>()) }

    override fun fillAll(color: Int) { dg.fillStyle = css(color); g.fillRect(0.0, 0.0, width, height) }

    override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) {
        g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1)
        dg.strokeStyle = css(color); g.lineWidth = width; dg.lineCap = "butt"; solid(); g.stroke()
    }

    override fun fillRect(l: Double, t: Double, r: Double, b: Double, color: Int) { dg.fillStyle = css(color); g.fillRect(l, t, r - l, b - t) }

    override fun strokeRect(l: Double, t: Double, r: Double, b: Double, color: Int, width: Double) {
        dg.strokeStyle = css(color); g.lineWidth = width; dg.lineJoin = "miter"; solid(); g.strokeRect(l, t, r - l, b - t)
    }

    override fun fillRoundRect(l: Double, t: Double, r: Double, b: Double, radius: Double, color: Int) {
        val rad = minOf(radius, (r - l) / 2, (b - t) / 2)
        g.beginPath()
        g.moveTo(l + rad, t); g.lineTo(r - rad, t); g.arcTo(r, t, r, t + rad, rad)
        g.lineTo(r, b - rad); g.arcTo(r, b, r - rad, b, rad)
        g.lineTo(l + rad, b); g.arcTo(l, b, l, b - rad, rad)
        g.lineTo(l, t + rad); g.arcTo(l, t, l + rad, t, rad)
        g.closePath()
        dg.fillStyle = css(color); g.fill()
    }

    override fun beginPath() = g.beginPath()
    override fun moveTo(x: Double, y: Double) = g.moveTo(x, y)
    override fun lineTo(x: Double, y: Double) = g.lineTo(x, y)
    override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) = g.bezierCurveTo(x1, y1, x2, y2, x3, y3)
    override fun closePath() = g.closePath()
    override fun fillPath(color: Int) { dg.fillStyle = css(color); g.fill() }

    override fun strokePath(color: Int, width: Double, dash: DoubleArray?) {
        dg.strokeStyle = css(color); g.lineWidth = width; dg.lineCap = "round"; dg.lineJoin = "round"
        if (dash != null) dg.setLineDash(dash.toTypedArray()) else solid()
        g.stroke()
        solid()
    }

    override fun strokeArc(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double, color: Int, width: Double) {
        g.beginPath()
        g.arc(cx, cy, r, startDeg * PI / 180.0, (startDeg + sweepDeg) * PI / 180.0, sweepDeg < 0)
        dg.strokeStyle = css(color); g.lineWidth = width; dg.lineCap = "round"; solid(); g.stroke()
    }

    override fun save() = g.save()
    override fun clipRect(l: Double, t: Double, r: Double, b: Double) { g.beginPath(); g.rect(l, t, r - l, b - t); g.clip() }
    override fun restore() = g.restore()

    override fun text(s: String, x: Double, y: Double, size: Double, color: Int, weight: FontWeight, align: TextAlign) {
        g.font = font(size, weight)
        dg.textAlign = when (align) { TextAlign.LEFT -> "left"; TextAlign.CENTER -> "center"; TextAlign.RIGHT -> "right" }
        dg.textBaseline = "alphabetic"
        dg.fillStyle = css(color)
        g.fillText(s, x, y)
    }

    override fun textWidth(s: String, size: Double, weight: FontWeight): Double {
        g.font = font(size, weight)
        return g.measureText(s).width
    }
}
