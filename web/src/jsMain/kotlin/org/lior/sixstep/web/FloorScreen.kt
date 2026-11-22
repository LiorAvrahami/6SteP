package org.lior.sixstep.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.lior.sixstep.Colors
import org.lior.sixstep.DanceFloor
import org.lior.sixstep.FloorHost
import org.lior.sixstep.FloorPicture
import org.lior.sixstep.Hint
import org.lior.sixstep.HintPainter
import org.lior.sixstep.Scene
import org.lior.sixstep.StepCell
import org.lior.sixstep.StripPainter
import org.lior.sixstep.Texts
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.pow

/** The dance floor in a browser: the screen of the shared DanceFloor. It draws what the floor shows (the shared
 *  FloorPicture and StripPainter on two canvases), slides the bars, and passes on the gestures: a swipe left or right,
 *  a swipe down or up for the bars (either brings them, either sends them away), and every tap the moment the finger lifts (a tap plays or pauses:
 *  DanceFloor.tap); on a keyboard the arrow keys and the space bar. The bars are the app's (activity_dance_floor.xml):
 *  play, the tempo, the song, mute; then back. */
class FloorScreen(kind: String, name: String, variant: Int, autoplay: Boolean) : Screen, FloorHost {
    companion object {
        const val TOP_BAR = 64.0
        const val STRIP = 132.0
        const val BAND = 32.0            // the strip's upper band, where the annotations are: touches there go to the floor
        const val SWIPE_X = 60.0
        const val SWIPE_Y = 80.0
        const val TAP_SLOP = 20.0        // a finger that moved less than this, either way, has tapped
    }

    override val root: HTMLElement = el("div", "floor")
    private val stage = Stage(document.createElement("canvas") as HTMLCanvasElement)
    private val stripStage = Stage(document.createElement("canvas") as HTMLCanvasElement)
    // the hints (shared HintPainter): a layer over everything, bars included, that takes no touches
    private val hintStage = Stage(document.createElement("canvas") as HTMLCanvasElement)
    private var hint: Hint? = null
    private var hintSince = 0.0      // when its clock started: when it became the floor's hint, or the last touch
    private var hintFirst = 0.0      // s from then to its first showing (the floor decides: HintPainter.firstAt, AFTER_PAUSE, AFTER_BARS)
    private var hintDrawn = false    // something of it is on the layer now
    private var hintTimer: Int? = null   // wakes the frames when a resting hint is to play again
    private var peek = 0.0           // px the top bar is brought down by a hint (HintPainter.nudge)
    private lateinit var topBar: HTMLElement
    private val picture = FloorPicture(1.0)
    private val strip = StripPainter(1.0).apply { barColor = Colors.BAR; calloutBandDp = BAND; cellWidthDp = 64.0; gapDp = 8.0 }

    private val playBtn = iconButton("play", Texts.PLAY) { floor.playPause() }
    private val rateBtn = el("button", "rate press") {
        title = Texts.TEMPO
        onclick = { it.stopPropagation(); val choices = floor.tempoChoices(); menu(this, choices) { i -> floor.chooseTempo(choices[i].key) }; null }
    }
    private val songLabel = el("span")
    private val songBtn = el("button", "song press") {
        title = Texts.SONG
        add(songLabel); add(icon("chevron_down"))
        onclick = { it.stopPropagation(); val choices = floor.songChoices(); menu(this, choices) { i -> floor.chooseSong(choices[i].key) }; null }
    }
    private val muteBtn = iconButton("volume", Texts.MUTE) { floor.toggleMute() }
    private val loadingEl = el("div", "loading") { add(el("div", "spinner")); add(el("span", "", Texts.LOADING_MUSIC)); hidden = true }
    private var menuEl: HTMLElement? = null

    private val floor = DanceFloor(App.assets.data, App.assets.store, kind, name, variant, autoplay, App.player, this)

    private var attached = false
    private val callbacks = ArrayList<() -> Unit>()
    private var rafAsked = false
    private var dirty = true
    private var stripDirty = true

    // the strip's scrolling: a glide to a picture, a fling after a drag
    private var glideFrom = 0.0; private var glideTo = 0.0; private var glideT0 = 0.0; private var gliding = false
    private var flingV = 0.0             // px per ms
    private var lastFrame = 0.0
    private var stripTouched = -1e9      // when the user last handled the strip
    private var stripDown = false; private var stripDragging = false
    private var stripDownX = 0.0; private var stripDownY = 0.0; private var stripLastX = 0.0; private var stripLastT = 0.0

    // the floor's gestures
    private var downX = 0.0; private var downY = 0.0; private var isDown = false
    private var closing = false
    private var wakeLock: dynamic = null

    init {
        stage.canvas.className = "stage"
        root.add(stage.canvas)
        val stripBox = el("div", "strip")
        stripBox.add(stripStage.canvas)
        val touch = el("div", "touch")
        stripBox.add(touch)
        root.add(stripBox)
        val top = el("div", "topbar")
        topBar = top
        top.add(playBtn); top.add(rateBtn); top.add(songBtn); top.add(muteBtn); top.add(el("div", "space"))
        top.add(iconButton("back", Texts.BACK) { App.back() })
        root.add(top)
        hintStage.canvas.className = "hints"
        root.add(hintStage.canvas)
        // any touch on the floor, its bars or its menus, before it does anything else: a hint on the screen goes, and
        // waits for the user to leave the screen alone again (DanceFloor.touched)
        root.addEventListener("pointerdown", { floor.touched() }, true)
        root.add(loadingEl)

        // the floor: where the touch went down and where it came up
        stage.canvas.addEventListener("pointerdown", { e ->
            e as MouseEvent
            isDown = true; downX = Display.x(e); downY = Display.y(e)
            // the floor keeps the touch until it lifts, also when it ends over a bar
            try { stage.canvas.asDynamic().setPointerCapture(e.asDynamic().pointerId) } catch (t: Throwable) {}
            App.player.unlock()
            closing = menuEl != null   // a touch that closes a menu does nothing else, as on Android
            closeMenu()
        })
        stage.canvas.addEventListener("pointerup", { e -> e as MouseEvent; if (isDown) { isDown = false; if (!closing) gesture(Display.x(e) - downX, Display.y(e) - downY) } })
        stage.canvas.addEventListener("pointercancel", { isDown = false })

        // the strip under its band: drag it, or tap a step
        touch.addEventListener("pointerdown", { e ->
            e as MouseEvent
            App.player.unlock(); closeMenu()
            stripDown = true; stripDragging = false; gliding = false; flingV = 0.0
            stripDownX = Display.x(e); stripDownY = Display.y(e); stripLastX = stripDownX; stripLastT = nowMs(); stripTouched = stripLastT
            try { touch.asDynamic().setPointerCapture(e.asDynamic().pointerId) } catch (t: Throwable) {}
        })
        touch.addEventListener("pointermove", { e ->
            e as MouseEvent
            if (!stripDown) return@addEventListener
            val x = Display.x(e); val now = nowMs()
            if (!stripDragging && abs(x - stripDownX) > 8) { stripDragging = true; stripLastX = x; stripLastT = now }
            if (stripDragging) {
                val dx = x - stripLastX; val dt = (now - stripLastT).coerceAtLeast(1.0)
                strip.offset = (strip.offset - dx).coerceIn(0.0, strip.maxOffset())
                flingV = 0.7 * (-dx / dt) + 0.3 * flingV
                stripLastX = x; stripLastT = now; stripDirty = true; ask()
            }
            stripTouched = now
        })
        val stripUp = { e: Event ->
            e as MouseEvent
            if (stripDown) {
                stripDown = false; stripTouched = nowMs()
                if (stripDragging) { stripDragging = false; if (nowMs() - stripLastT > 80) flingV = 0.0; ask() }
                else {
                    val k = strip.cellAt(Display.xIn(e, stripStage.canvas))
                    if (k >= 0 && abs(Display.y(e) - stripDownY) < 12) floor.tapStop(k)   // a finger that moved is not a tap
                }
            }
        }
        touch.addEventListener("pointerup", stripUp)
        touch.addEventListener("pointercancel", { stripDown = false; stripDragging = false })
    }

    private val onKey = { e: Event ->
        e as KeyboardEvent
        floor.touched()
        var used = true
        when (e.key) {
            "ArrowRight" -> floor.swipe(true)
            "ArrowLeft" -> floor.swipe(false)
            "ArrowDown" -> floor.swipeDown()
            "ArrowUp" -> floor.swipeUp()
            " " -> { App.player.unlock(); floor.playPause() }
            "Escape" -> App.back()
            else -> used = false
        }
        if (used) e.preventDefault()
    }
    private val onResize = { _: Event -> dirty = true; stripDirty = true; ask() }
    private val onVisibility = { _: Event -> if (document.asDynamic().hidden == true) floor.hidden() else keepAwake() }

    override fun attach() {
        attached = true
        window.addEventListener("keydown", onKey)
        window.addEventListener("resize", onResize)
        document.addEventListener("visibilitychange", onVisibility)
        fit()
        keepAwake()
        floor.open()
        ask()
    }

    override fun detach() {
        attached = false
        window.removeEventListener("keydown", onKey)
        window.removeEventListener("resize", onResize)
        document.removeEventListener("visibilitychange", onVisibility)
        try { wakeLock?.release() } catch (e: Throwable) {}
        wakeLock = null
        floor.release()
        hintTimer?.let { window.clearTimeout(it) }; hintTimer = null
    }

    /** the screen stays on while the floor is shown, where the browser can do that */
    private fun keepAwake() {
        try {
            val wl = window.navigator.asDynamic().wakeLock
            if (wl != null && wl != undefined) wl.request("screen").then({ l: dynamic -> wakeLock = l }, { _: dynamic -> })
        } catch (e: Throwable) {}
    }

    // ------------------------------------------------------------------ gestures

    private fun gesture(dx: Double, dy: Double) {
        if (abs(dy) > SWIPE_Y && abs(dy) > abs(dx) * 1.2) { if (dy > 0) floor.swipeDown() else floor.swipeUp(); return }
        if (abs(dx) > SWIPE_X && abs(dx) > abs(dy) * 1.2) { floor.swipe(dx < 0); return }
        // a tap: passed on at once (user 2026-11-21: one tap pauses, with nothing to wait for)
        if (abs(dx) <= TAP_SLOP && abs(dy) <= TAP_SLOP) floor.tap()
    }

    // ------------------------------------------------------------------ the frames

    private fun ask() {
        if (rafAsked || !attached) return
        rafAsked = true
        window.requestAnimationFrame { rafAsked = false; frame() }
    }

    private fun fit() {
        if (stage.fit()) { picture.setSize(stage.width, stage.height); dirty = true }
        if (stripStage.fit()) { strip.setSize(stripStage.width, stripStage.height); stripDirty = true }
        picture.pixel = stage.pixel; strip.pixel = stripStage.pixel
    }

    private fun frame() {
        if (!attached) return
        val now = nowMs()
        val dt = if (lastFrame == 0.0) 16.0 else (now - lastFrame).coerceIn(1.0, 100.0)
        lastFrame = now
        val due = callbacks.toList(); callbacks.clear()
        for (c in due) c()
        fit()
        // the strip under way: a glide to a picture, or the rest of a fling
        if (gliding) {
            val u = ((now - glideT0) / 350.0).coerceIn(0.0, 1.0)
            val e = 1 - (1 - u) * (1 - u)
            strip.offset = (glideFrom + (glideTo - glideFrom) * e).coerceIn(0.0, strip.maxOffset())
            if (u >= 1.0) gliding = false
            stripDirty = true
        } else if (!stripDragging && abs(flingV) > 0.02) {
            strip.offset = (strip.offset + flingV * dt).coerceIn(0.0, strip.maxOffset())
            flingV *= 0.996.pow(dt)
            if (strip.offset <= 0.0 || strip.offset >= strip.maxOffset()) flingV = 0.0
            stripTouched = now
            stripDirty = true
        }
        if (dirty) { dirty = picture.draw(stage.draw, now) }
        if (stripDirty) {
            stripDirty = false
            stripStage.g.clearRect(0.0, 0.0, stripStage.width, stripStage.height)
            strip.draw(stripStage.draw)
        }
        // the hint, if there is one: its picture moves by itself, so it is drawn at every frame for as long as it is there
        val h = hint
        val th = (now - hintSince) / 1000.0
        val still = reducedMotion
        if (h != null || hintDrawn) {
            hintStage.fit()
            if (hintDrawn) hintStage.g.clearRect(0.0, 0.0, hintStage.width, hintStage.height)
            hintDrawn = h != null && HintPainter.draw(hintStage.draw, h, th, hintStage.width, hintStage.height, 1.0, if (floor.barsShown) STRIP else 0.0, still, hintFirst)
        }
        // and the real interface makes a little of the move with it (HintPainter.nudge): the top bar peeks in as the finger
        // swipes down
        val n = if (h == null) 0.0 else HintPainter.nudge(h, th, still, hintFirst)
        val wantPeek = if (h == Hint.SWIPE_DOWN && !floor.barsShown) kotlin.math.max(0.0, n) * HintPainter.BAR_PEEK else 0.0
        if (wantPeek != peek) {
            peek = wantPeek
            topBar.style.transform = if (peek > 0.0) "translateY(calc(-100% + ${peek}px))" else ""
            topBar.style.transition = if (peek > 0.0) "none" else ""
        }
        if (stripDirty) {
            stripDirty = false
            stripStage.g.clearRect(0.0, 0.0, stripStage.width, stripStage.height)
            strip.draw(stripStage.draw)
        }
        loadingEl.hidden = !floor.loading
        report()
        // while it rests nothing needs drawing: the frames are woken when it is to play again
        if (h != null && !hintDrawn && hintTimer == null) {
            val wait = HintPainter.wait(h, th, hintFirst)   // infinite after its last showing: then it waits for a touch
            if (wait.isFinite()) hintTimer = window.setTimeout({ hintTimer = null; ask() }, (wait * 1000.0).coerceIn(16.0, 60000.0).toInt())
        }
        if (dirty || stripDirty || callbacks.isNotEmpty() || gliding || (!stripDragging && abs(flingV) > 0.02) || floor.loading || hintDrawn) ask()
    }

    /** what the floor shows, for whoever tests the page from outside (window.sixstep) */
    private fun report() {
        val sc = picture.scene
        val o: dynamic = js("({})")
        o.playing = floor.playing; o.loading = floor.loading; o.beat = App.player.beatNow(); o.bpm = App.player.bpm(); o.t = nowMs()
        o.title = sc?.title; o.count = sc?.count; o.banner = sc?.banner; o.arrows = sc?.arrows?.size ?: 0
        o.current = strip.current; o.bars = floor.barsShown; o.song = floor.songLabel()
        o.hint = hint?.key; o.hintShowing = hintDrawn; o.peek = peek
        window.asDynamic().sixstep = o
    }

    // ------------------------------------------------------------------ what the floor asks of its screen

    override fun nowMs(): Double = org.lior.sixstep.web.nowMs()
    override fun nextFrame(callback: () -> Unit) { callbacks.add(callback); ask() }
    override fun show(scene: Scene) { picture.setScene(scene, nowMs()); dirty = true; ask() }

    override fun strip(cells: List<StepCell>, callouts: List<Pair<Int, String>>) { strip.cells = cells; strip.callouts = callouts; stripDirty = true; ask() }
    override fun stripCurrent(k: Int) { if (strip.current != k) { strip.current = k; stripDirty = true; ask() } }

    override fun stripScrollTo(k: Int, animate: Boolean) {
        fit()
        val target = strip.targetFor(k)
        flingV = 0.0
        if (animate) { gliding = true; glideFrom = strip.offset; glideTo = target; glideT0 = nowMs() }
        else { gliding = false; strip.offset = target }
        stripDirty = true; ask()
    }

    /** the user is handling the strip (or just did): it does not follow the dance meanwhile */
    private fun stripBusy(): Boolean = stripDown || gliding || abs(flingV) > 0.02 || nowMs() - stripTouched < 2500

    override fun stripFollow(k: Int) {
        if (stripBusy() || strip.width <= 0) return
        val target = strip.targetFor(k)
        if (abs(target - strip.offset) > 0.5) { strip.offset += (target - strip.offset) * StripPainter.FOLLOW; stripDirty = true }
    }

    override fun bars(shown: Boolean) {
        if (shown) root.classList.add("bars") else root.classList.remove("bars")
        if (!shown) closeMenu()
        picture.setInsets(if (shown) TOP_BAR else 0.0, if (shown) STRIP else 0.0, nowMs())
        dirty = true; ask()
    }

    override fun buttons() {
        val playing = floor.playing
        (playBtn.firstChild as HTMLElement).setAttribute("src", if (playing) "icons/pause.svg" else "icons/play.svg")
        playBtn.title = if (playing) Texts.PAUSE else Texts.PLAY
        playBtn.setAttribute("aria-label", playBtn.title)
        (muteBtn.firstChild as HTMLElement).setAttribute("src", if (floor.muted) "icons/volume_off.svg" else "icons/volume.svg")
        muteBtn.title = if (floor.muted) Texts.UNMUTE else Texts.MUTE
        muteBtn.setAttribute("aria-label", muteBtn.title)
        rateBtn.textContent = floor.tempoLabel()
        rateBtn.add(el("small", "", "BPM"))
        songLabel.textContent = floor.songLabel()
        ask()
    }

    override fun message(text: String) = toast(text)
    /** the settings page's switch for the lead-in after a swipe (on unless it was switched off) */
    override val leadIn: Boolean get() = Prefs.bool("lead_in", true)

    // the hints: which one to show, and what this user has already done (kept in the browser's storage, so until that is cleared)
    /** the phone or computer asks for less motion: the hints are still pictures */
    private val reducedMotion: Boolean get() = try { window.matchMedia("(prefers-reduced-motion: reduce)").matches } catch (e: Throwable) { false }
    override fun hint(hint: Hint?, first: Double) {
        this.hint = hint; hintSince = nowMs(); hintFirst = first
        hintTimer?.let { window.clearTimeout(it) }; hintTimer = null
        ask()
    }
    override fun knows(what: String): Boolean = Prefs.bool(Hint.flagOf(what), false)
    override fun learned(what: String) { Prefs.put(Hint.flagOf(what), true) }

    /** the song menu's "Upload…": the dance stops, and the window in which a song of the listener's own is added
     *  opens; a song that was added is the one that plays from then on */
    override fun addSong() {
        floor.hidden()
        UploadWindow { key -> floor.chooseSong(key) }.open()
    }

    // ------------------------------------------------------------------ the tempo and song menus

    private fun closeMenu() { menuEl?.remove(); menuEl = null }

    /** a menu under one of the bar's buttons, as the app's: each line with a round mark at its end, filled on the one
     *  that is chosen; a line that only does something (the song menu's "Upload…") has no mark */
    private fun menu(anchor: HTMLElement, choices: List<DanceFloor.Choice>, pick: (Int) -> Unit) {
        val open = menuEl != null && menuEl?.getAttribute("data-of") == anchor.className
        closeMenu()
        if (open || choices.isEmpty()) return
        val m = el("div", "menu")
        m.setAttribute("data-of", anchor.className)
        m.style.left = "${anchor.offsetLeft}px"
        for ((i, c) in choices.withIndex()) m.add(el("button", if (c.checked) "on" else "") {
            add(el("span", "", c.label))
            if (c.checkable) add(el("i", "radio"))
            onclick = { it.stopPropagation(); closeMenu(); pick(i); null }
        })
        root.add(m)
        menuEl = m
    }
}
