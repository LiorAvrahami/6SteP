package org.lior.sixstep.web

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.lior.sixstep.Colors
import org.lior.sixstep.Draw
import org.lior.sixstep.FontWeight
import org.lior.sixstep.TextAlign
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Node
import org.w3c.dom.events.MouseEvent
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

// ------------------------------------------------------------------ how the page sits on the screen

/** The page is sideways, as the Android app is, and full screen where the browser allows it (user 2026-11-21).
 *
 *  A page cannot simply tell a phone to turn. What it can do: on the first tap of a visit it asks for the full screen,
 *  and with that for landscape (Android's Chrome grants both; put on the home screen, the page gets them from its
 *  manifest without asking). Wherever the phone still shows the page upright (the browser refuses, or the phone's
 *  rotation is locked), the page turns itself: its one box (#app) is laid out sideways and drawn turned a quarter, so
 *  that it is landscape for whoever holds the phone sideways, top to the left, as an Android app in landscape is held.
 *  A computer's window is left as it is.
 *
 *  Because the box may lie turned, a touch's place is not asked of the event but of x(), y() and xIn() here. */
object Display {
    /** the page's box is drawn turned a quarter: the browser's screen is upright, the page is sideways */
    var turned = false
        private set
    private var tapped = false
    private val listeners = ArrayList<() -> Unit>()

    fun start() {
        update()
        window.addEventListener("resize", { update() })
        window.addEventListener("orientationchange", { update() })
        document.addEventListener("fullscreenchange", { update(); for (l in listeners.toList()) l() })
        document.addEventListener("webkitfullscreenchange", { update(); for (l in listeners.toList()) l() })
        document.addEventListener("click", { firstTap() }, true)
    }

    /** told when the page enters or leaves the full screen */
    fun onFullscreenChange(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** a phone or a tablet: something that is held and touched, and so can be turned */
    private fun handheld(): Boolean = try { window.matchMedia("(pointer: coarse)").matches } catch (e: Throwable) { false }

    private fun update() {
        val app = document.getElementById("app") as? HTMLElement ?: return
        val root = document.documentElement as? HTMLElement ?: return
        val w = window.innerWidth.toDouble(); val h = window.innerHeight.toDouble()
        turned = handheld() && h > w
        if (turned) { root.classList.add("turned"); app.style.width = "${h}px"; app.style.height = "${w}px" }
        else { root.classList.remove("turned"); app.style.width = ""; app.style.height = "" }
        // a box that really is upright (a tall window on a computer) gets the launcher's upright layout
        if (!turned && h > w) root.classList.add("upright") else root.classList.remove("upright")
        val bw = if (turned) h else w; val bh = if (turned) w else h
        root.style.setProperty("--aw", "${bw / 100}px"); root.style.setProperty("--ah", "${bh / 100}px")
    }

    /** where a touch is along the page's own width (x) and height (y); only differences of these mean something */
    fun x(e: MouseEvent): Double = if (turned) e.clientY.toDouble() else e.clientX.toDouble()
    fun y(e: MouseEvent): Double = if (turned) -e.clientX.toDouble() else e.clientY.toDouble()
    /** how far a touch is from an element's left edge, in the page's own px */
    fun xIn(e: MouseEvent, el: Element): Double {
        val r = el.getBoundingClientRect()
        return if (turned) e.clientY - r.top else e.clientX - r.left
    }

    fun canFullscreen(): Boolean {
        val el: dynamic = document.documentElement
        return jsTypeOf(el.requestFullscreen) == "function" || jsTypeOf(el.webkitRequestFullscreen) == "function"
    }

    fun isFullscreen(): Boolean {
        val d: dynamic = document
        val e: dynamic = if (jsTypeOf(d.fullscreenElement) != "undefined") d.fullscreenElement else d.webkitFullscreenElement
        return e != null && jsTypeOf(e) != "undefined"
    }

    /** opened from the home screen: the page already has the screen to itself */
    private fun installed(): Boolean = try {
        window.matchMedia("(display-mode: fullscreen)").matches || window.matchMedia("(display-mode: standalone)").matches ||
            window.navigator.asDynamic().standalone == true
    } catch (e: Throwable) { false }

    /** ask the phone for landscape; it says yes only to a page that has the full screen (or is on its way to it) */
    private fun lock() {
        try {
            val o: dynamic = window.screen.asDynamic().orientation
            if (o != null && jsTypeOf(o) != "undefined" && jsTypeOf(o.lock) == "function") o.lock("landscape").then({ _: dynamic -> }, { _: dynamic -> })
        } catch (e: Throwable) {}
    }

    /** the full screen, and landscape with it; only from a tap */
    fun enter() {
        val el: dynamic = document.documentElement
        try {
            val p: dynamic = if (jsTypeOf(el.requestFullscreen) == "function") el.requestFullscreen(js("({ navigationUI: 'hide' })")) else el.webkitRequestFullscreen()
            if (p != null && jsTypeOf(p) != "undefined" && jsTypeOf(p.then) == "function") p.then({ _: dynamic -> lock() }, { _: dynamic -> })
        } catch (e: Throwable) {}
        lock()
    }

    fun leave() {
        val d: dynamic = document
        try { if (jsTypeOf(d.exitFullscreen) == "function") d.exitFullscreen() else if (jsTypeOf(d.webkitExitFullscreen) == "function") d.webkitExitFullscreen() } catch (e: Throwable) {}
    }

    fun toggle() { if (isFullscreen()) leave() else enter() }

    /** the first tap of a visit on a phone: the full screen and landscape, once. Left again, it stays left until the
     *  launcher's button is used (a page that kept going back to the full screen could not be got out of). */
    private fun firstTap() {
        if (tapped) return
        tapped = true
        if (!handheld()) return
        if (installed()) lock() else if (canFullscreen() && !isFullscreen()) enter()
    }
}

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
