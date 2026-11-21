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

/** one of the app's icons (icons/<name>.svg: the Android app's ic_<name>, made by design/make_ui_icons.py for both) */
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

/** the page's one box (#app): every screen, window and message is put into it, so that they all lie the way it lies */
fun appBox(): HTMLElement = document.getElementById("app") as HTMLElement

/** a short message at the bottom of the page that goes away by itself, as an Android toast */
fun toast(text: String) {
    val box = appBox()
    box.querySelector(".toast")?.let { box.removeChild(it) }
    val t = el("div", "toast", text)
    box.add(t)
    window.setTimeout({ if (t.parentNode != null) t.parentNode?.removeChild(t) }, 3000)
}

/** A window over the page: the page behind it is dimmed and cannot be touched; a tap beside the window closes it.
 *  What is in the window goes into `box`. */
class Modal(boxClass: String) {
    val box: HTMLElement = el("div", boxClass)
    private val shade = el("div", "shade")
    var onClose: (() -> Unit)? = null
    val isOpen: Boolean get() = shade.parentNode != null

    init {
        shade.add(box)
        shade.addEventListener("click", { e -> if (e.target === shade) close() })
    }

    fun open() { if (!isOpen) appBox().add(shade) }
    fun close() { if (isOpen) { shade.parentNode?.removeChild(shade); onClose?.invoke() } }
}

/** the largest whole text size from `max` down to `min` at which `inner` is no taller (and no wider) than the room
 *  inside `outer`, as an Android text that sizes itself. The sizes are the app's "sp": px times the phone's text size
 *  setting where the browser tells it (--sp in style.css). */
fun fitText(outer: HTMLElement, inner: HTMLElement, min: Int, max: Int) {
    val cs = window.getComputedStyle(outer)
    fun px(v: String): Double = v.removeSuffix("px").toDoubleOrNull() ?: 0.0
    val roomH = outer.clientHeight - px(cs.paddingTop) - px(cs.paddingBottom)
    val roomW = outer.clientWidth - px(cs.paddingLeft) - px(cs.paddingRight)
    if (roomH <= 0 || roomW <= 0) return
    var size = max
    while (true) {
        inner.style.fontSize = "calc(${size}px * var(--sp))"
        if (size <= min || (inner.offsetHeight <= roomH + 0.5 && inner.scrollWidth <= roomW + 0.5)) break
        size--
    }
}

// ------------------------------------------------------------------ how the page sits on the screen

/** The page lies the way the Android app's screens do (user 2026-11-21): sideways, all but the heart page, which is
 *  upright as the user drew it; and full screen where the browser allows it.
 *
 *  The full screen comes by itself, as far as a browser lets it (user 2026-11-21: "why can't you just make it happen
 *  without pressing a button?"). No browser gives a page the full screen when it loads or after a wait: only while a
 *  tap is being handled. So on a phone or tablet every tap anywhere on the page, whenever the page does not have the
 *  full screen, takes it: the first tap of a visit, and any tap after the browser has taken it away (the phone's back
 *  gesture, the file chooser). The launcher's fourth button leaves it for good, and takes it back (`wanted`,
 *  remembered). Put on the home screen, the page has the full screen from the start, without any tap.
 *
 *  A page cannot simply tell a phone to turn either. With the full screen it asks for the way the screen should lie
 *  (Android's Chrome grants that to a full-screen page; from the home screen the page gets it without asking). Wherever the phone still shows the page the other way (the browser refuses, or the phone's
 *  rotation is locked), the page turns itself: its one box (#app) is laid out the way the screen wants it and drawn
 *  turned a quarter. A sideways page on an upright phone is then right for whoever holds the phone sideways, top to
 *  the left, as an Android app in landscape is held. A computer's window is left as it is.
 *
 *  Because the box may lie turned, a touch's place is not asked of the event but of x(), y() and xIn() here. */
object Display {
    /** how the page's box lies on the browser's screen: 0 as the screen does; 1 a quarter turn clockwise (the screen
     *  is upright, the page sideways); -1 a quarter turn the other way (the screen is sideways, the page upright) */
    var quarter = 0
        private set
    private var sideways = true
    private val listeners = ArrayList<() -> Unit>()
    /** on a phone or tablet the page takes the full screen at every tap that finds it without; the launcher's button
     *  switches that off and on, and the browser remembers it */
    var wanted: Boolean
        get() = Prefs.bool("full_screen", true)
        private set(v) = Prefs.put("full_screen", v)

    fun start() {
        update()
        window.addEventListener("resize", { update() })
        window.addEventListener("orientationchange", { update() })
        document.addEventListener("fullscreenchange", { update(); for (l in listeners.toList()) l() })
        document.addEventListener("webkitfullscreenchange", { update(); for (l in listeners.toList()) l() })
        document.addEventListener("click", { onTap() }, true)
    }

    /** told when the page enters or leaves the full screen */
    fun onFullscreenChange(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** the screen that is shown now wants the page sideways, or upright (the heart page) */
    fun want(sideways: Boolean) {
        if (this.sideways == sideways) return
        this.sideways = sideways
        if (isFullscreen() || installed()) lock()
        update()
    }

    /** a phone or a tablet: something that is held and touched, and so can be turned */
    private fun handheld(): Boolean = try { window.matchMedia("(pointer: coarse)").matches } catch (e: Throwable) { false }

    /** how far the phone's screen is turned from the way it is normally held, in degrees (90: its top is to the left) */
    private fun angle(): Int = try {
        val o: dynamic = window.screen.asDynamic().orientation
        val a: dynamic = if (o != null && jsTypeOf(o) != "undefined" && jsTypeOf(o.angle) == "number") o.angle else window.asDynamic().orientation
        if (jsTypeOf(a) == "number") a.unsafeCast<Int>() else 90
    } catch (e: Throwable) { 90 }

    private fun update() {
        val app = document.getElementById("app") as? HTMLElement ?: return
        val root = document.documentElement as? HTMLElement ?: return
        val w = window.innerWidth.toDouble(); val h = window.innerHeight.toDouble()
        quarter = when {
            !handheld() -> 0
            sideways && h > w -> 1
            // upright on a screen that lies sideways: turned back the way the screen was turned
            !sideways && w > h -> if (angle() == 270 || angle() == -90) 1 else -1
            else -> 0
        }
        root.classList.toggle("turned", quarter == 1)
        root.classList.toggle("turned-back", quarter == -1)
        if (quarter != 0) { app.style.width = "${h}px"; app.style.height = "${w}px" } else { app.style.width = ""; app.style.height = "" }
        val bw = if (quarter != 0) h else w; val bh = if (quarter != 0) w else h
        // a box that is upright: the heart page, and a tall window on a computer (the launcher has a layout for that)
        root.classList.toggle("upright", bh > bw)
        root.style.setProperty("--aw", "${bw / 100}px"); root.style.setProperty("--ah", "${bh / 100}px")
    }

    /** where a touch is along the page's own width (x) and height (y); only differences of these mean something */
    fun x(e: MouseEvent): Double = when (quarter) { 1 -> e.clientY.toDouble(); -1 -> -e.clientY.toDouble(); else -> e.clientX.toDouble() }
    fun y(e: MouseEvent): Double = when (quarter) { 1 -> -e.clientX.toDouble(); -1 -> e.clientX.toDouble(); else -> e.clientY.toDouble() }
    /** how far a touch is from an element's left edge, in the page's own px */
    fun xIn(e: MouseEvent, el: Element): Double {
        val r = el.getBoundingClientRect()
        return when (quarter) { 1 -> e.clientY - r.top; -1 -> r.bottom - e.clientY; else -> e.clientX - r.left }
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

    /** ask the phone to lie the way the screen shown wants it; it says yes only to a page that has the full screen (or
     *  is on its way to it) */
    private fun lock() {
        try {
            val o: dynamic = window.screen.asDynamic().orientation
            if (o != null && jsTypeOf(o) != "undefined" && jsTypeOf(o.lock) == "function")
                o.lock(if (sideways) "landscape" else "portrait").then({ _: dynamic -> update() }, { _: dynamic -> })
        } catch (e: Throwable) {}
    }

    /** the full screen, and the right way round with it; only from a tap */
    private fun enter() {
        val el: dynamic = document.documentElement
        try {
            val p: dynamic = if (jsTypeOf(el.requestFullscreen) == "function") el.requestFullscreen(js("({ navigationUI: 'hide' })")) else el.webkitRequestFullscreen()
            if (p != null && jsTypeOf(p) != "undefined" && jsTypeOf(p.then) == "function") p.then({ _: dynamic -> lock() }, { _: dynamic -> })
        } catch (e: Throwable) {}
        lock()
    }

    private fun leave() {
        val d: dynamic = document
        try { if (jsTypeOf(d.exitFullscreen) == "function") d.exitFullscreen() else if (jsTypeOf(d.webkitExitFullscreen) == "function") d.webkitExitFullscreen() } catch (e: Throwable) {}
    }

    /** the launcher's button: out of the full screen, to stay out; or into it, to stay in */
    fun toggle() {
        if (isFullscreen()) { wanted = false; leave() } else { wanted = true; enter() }
    }

    /** a tap anywhere on the page: on a phone or tablet it takes the full screen and the right way round, whenever the
     *  page does not have them and has not been told to stay out */
    private fun onTap() {
        if (!handheld() || !wanted) return
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
    fun remove(key: String) { try { localStorage.removeItem(PREFIX + key) } catch (e: Throwable) {} }
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
    /** one of the screen's own pixels, in the page's px (what the shared painters call `pixel`) */
    val pixel: Double get() = 1.0 / window.devicePixelRatio

    /** fit the buffer to the size on the page; true when the size changed */
    fun fit(): Boolean {
        val w = canvas.clientWidth.toDouble(); val h = canvas.clientHeight.toDouble()
        val dpr = window.devicePixelRatio
        draw.dpr = dpr
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
    /** the screen's own pixels per px of the page */
    var dpr = 1.0
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
        var ax = x0; var ay = y0; var bx = x1; var by = y1
        // a line as thin as one of the screen's own pixels is put exactly on a column or a row of them, so that it is
        // one sharp pixel wide (as the Android app's grid is) and not two faint ones
        if (width * dpr <= 1.01) {
            if (x0 == x1) { ax = (kotlin.math.floor(x0 * dpr) + 0.5) / dpr; bx = ax }
            else if (y0 == y1) { ay = (kotlin.math.floor(y0 * dpr) + 0.5) / dpr; by = ay }
        }
        g.beginPath(); g.moveTo(ax, ay); g.lineTo(bx, by)
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
