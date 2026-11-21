package org.lior.sixstep.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.lior.sixstep.Composer
import org.lior.sixstep.CompositionStore
import org.lior.sixstep.ExploreModel
import org.lior.sixstep.FloorPicture
import org.lior.sixstep.MusicPlan
import org.lior.sixstep.Piece
import org.lior.sixstep.Scene
import org.lior.sixstep.StripPainter
import org.lior.sixstep.Hint
import org.lior.sixstep.Texts
import org.lior.sixstep.Timeline
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One of the page's screens: its element, and what it does when it is put on the page and taken off it.
 *
 *  The screens look as the Android app's do (user 2026-11-21: "the web app looks slightly different from the app"): each
 *  is laid out from the app's own layout file, with its sizes (a dp there is a px here), its fonts and its icons. What
 *  the page has and the app has not: a back button on explore, settings and the heart page (a phone's own back button
 *  leaves the full screen first), and the launcher's fourth button, which leaves and takes the full screen. */
interface Screen {
    val root: HTMLElement
    /** sideways, as the Android app's screens are; the heart page is upright */
    val sideways: Boolean get() = true
    fun attach() {}
    fun detach() {}
}

// ------------------------------------------------------------------ the launcher

/** The launcher (the user's layout draft, 2026-10-28; the app's activity_main.xml): at the left the title, a little
 *  floor dancing the "quick recap" at 1.5 times the exercise's tempo, and the heart, settings and help buttons; at the
 *  right the daily exercise with how long it takes, and explore. Help puts two explanations where the little floor
 *  is, each with an arrow to the button it explains. */
class Launcher : Screen {
    companion object { const val SNIPPET_SPEED = 1.5 }

    override val root: HTMLElement = el("div", "launcher")
    private val stage = Stage(document.createElement("canvas") as HTMLCanvasElement)
    private val picture = FloorPicture(1.0).apply { showLabels = false }
    private var tl: Timeline? = null          // the quick recap
    private var bpm = 120.0
    private var t0 = 0.0
    private var running = false
    private var helpOn = false
    private val cap = el("div", "cap", Texts.QUICK_RECAP)
    private val helpPanel = el("div", "helppanel") { hidden = true }
    private val helpDailyText = el("div", "", Texts.HELP_DAILY)
    private val helpExploreText = el("div", "", Texts.HELP_EXPLORE)
    private val helpDaily = el("div", "helpbox") { style.background = "var(--daily)"; add(helpDailyText) }
    private val helpExplore = el("div", "helpbox") { style.background = "var(--explore)"; add(helpExploreText) }
    private val dailyIcon = icon("dumbbell")
    private val exploreIcon = icon("telescope")
    private val arrows: Element = document.createElementNS("http://www.w3.org/2000/svg", "svg").also { it.setAttribute("class", "arrows"); it.setAttribute("hidden", "") }
    private val onResize = { _: Event -> if (helpOn) layoutHelp() }
    // the page's own fourth button: out of the full screen and back into it (user 2026-11-21: "it's fine that you
    // bring it back"). On a phone the page takes the full screen by itself at any tap (Display); this is the way out.
    private val fullBtn = iconButton("maximize", Texts.FULL_SCREEN, "box press") { Display.toggle() }.also { it.style.background = "#fff" }
    private val showFull: () -> Unit = {
        val on = Display.isFullscreen()
        (fullBtn.firstChild as HTMLElement).setAttribute("src", if (on) "icons/minimize.svg" else "icons/maximize.svg")
        fullBtn.title = if (on) Texts.LEAVE_FULL_SCREEN else Texts.FULL_SCREEN
        fullBtn.setAttribute("aria-label", fullBtn.title)
    }

    init {
        val store = App.assets.store; val data = App.assets.data
        val daily = store.daily()
        var dailyBeats = 0.0
        if (daily != null) {
            val whole = Composer.compose(data, store.sequence(daily), daily.startState, Piece.SWING)
            if (whole.errors.isEmpty()) dailyBeats = whole.length
            store.find(CompositionStore.QUICK_RECAP)?.let { r ->
                val recap = Composer.compose(data, store.sequence(r), r.startState, Piece.SWING)
                if (recap.errors.isEmpty()) tl = Timeline(recap, Piece.SWING)
            }
        }
        // the tempo the daily exercise is danced at, with the song and the tempo chosen on the dance floor
        bpm = MusicPlan.startTempo(App.assets.tracks, Prefs.str("song", MusicPlan.ALL), Prefs.int("bpm_offset", 0))
        val time = if (dailyBeats <= 0) "" else Texts.minutes(((dailyBeats + 4) * 60.0 / bpm).roundToInt())

        val side = el("div", "side")
        side.add(el("div", "logo box", Texts.LOGO))
        val snippet = el("div", "snippet box")
        snippet.add(stage.canvas); snippet.add(cap)
        helpPanel.add(helpDaily); helpPanel.add(helpExplore)
        snippet.add(helpPanel)
        side.add(snippet)
        val small = el("div", "small")
        small.add(iconButton("heart", Texts.HEART, "box press") { App.go("heart") }.also { it.style.background = "var(--heart)" })
        small.add(iconButton("settings", Texts.SETTINGS, "box press") { App.go("settings") }.also { it.style.background = "var(--gear)" })
        small.add(iconButton("help", Texts.HELP, "box press") { setHelp(!helpOn) }.also { it.style.background = "var(--help)" })
        // an iPhone's browser has no full screen to give: there the page goes on the home screen
        if (Display.canFullscreen()) small.add(fullBtn)
        side.add(small)
        root.add(side)

        val tiles = el("div", "tiles")
        tiles.add(el("button", "tile box press") {
            style.background = "var(--daily)"
            setAttribute("aria-label", Texts.DAILY_EXERCISE)
            add(el("div", "mid") { add(el("div", "name", Texts.DAILY_EXERCISE)); add(dailyIcon) })
            add(el("div", "time", time))
            onclick = {
                App.player.unlock()   // the tap itself lets the browser make sound
                if (daily != null) App.go(App.floorPath(Piece.COMPOSITION, daily.name, 0, true))
                null
            }
        })
        tiles.add(el("button", "tile box press") {
            style.background = "var(--explore)"
            setAttribute("aria-label", Texts.EXPLORE)
            add(el("div", "mid") { add(el("div", "name", Texts.EXPLORE)); add(exploreIcon) })
            onclick = { App.go("explore"); null }
        })
        root.add(tiles)
        root.appendChild(arrows)   // over everything, never taking a touch
    }

    private fun setHelp(on: Boolean) {
        helpOn = on
        helpPanel.hidden = !on
        stage.canvas.style.visibility = if (on) "hidden" else "visible"
        cap.hidden = on
        if (on) { arrows.removeAttribute("hidden"); running = false; layoutHelp() } else { arrows.setAttribute("hidden", ""); start() }
    }

    /** where an element is inside the launcher, in the page's own px (whichever way the page lies): x, y, width, height */
    private fun place(e: HTMLElement): DoubleArray {
        var x = 0.0; var y = 0.0
        var n: HTMLElement? = e
        while (n != null && n !== root) {
            x += n.offsetLeft; y += n.offsetTop
            val p = n.offsetParent as? HTMLElement
            if (p != null && p !== root) { x += p.clientLeft; y += p.clientTop }
            n = p
        }
        return doubleArrayOf(x, y, e.offsetWidth.toDouble(), e.offsetHeight.toDouble())
    }

    /** the help view: each explanation's words as large as fit its box (9 to 17 px), and a dark blue arrow from it to
     *  the icon of the button it explains (the Android app's HelpArrows, with its sizes) */
    private fun layoutHelp() {
        fitText(helpDaily, helpDailyText, 9, 17); fitText(helpExplore, helpExploreText, 9, 17)
        while (arrows.firstChild != null) arrows.removeChild(arrows.firstChild!!)
        for ((from, to) in listOf(Pair(helpDaily, dailyIcon as HTMLElement), Pair(helpExplore, exploreIcon as HTMLElement))) {
            val a = place(from); val b = place(to)
            val x0 = a[0] + a[2] - 18; val y0 = a[1] + a[3] * 0.5
            val x1 = b[0] - 8; val y1 = b[1] + b[3] * 0.5
            val len = hypot(x1 - x0, y1 - y0)
            if (len < 1) continue
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
            val head = 30.0; val half = 17.0; val shaft = 4.5
            val bx = x1 - ux * head; val by = y1 - uy * head   // the middle of the head's base
            val pts = listOf(Pair(x0 - uy * shaft, y0 + ux * shaft), Pair(bx - uy * shaft, by + ux * shaft), Pair(bx - uy * half, by + ux * half),
                Pair(x1, y1), Pair(bx + uy * half, by - ux * half), Pair(bx + uy * shaft, by - ux * shaft), Pair(x0 + uy * shaft, y0 - ux * shaft))
            val poly = document.createElementNS("http://www.w3.org/2000/svg", "polygon")
            poly.setAttribute("points", pts.joinToString(" ") { "${it.first},${it.second}" })
            arrows.appendChild(poly)
        }
    }

    private fun start() {
        if (running) return
        running = true; t0 = nowMs()
        window.requestAnimationFrame { tick() }
    }

    private fun tick() {
        if (!running || helpOn) { running = false; return }
        val t = tl
        if (stage.fit()) picture.setSize(stage.width, stage.height)
        picture.pixel = stage.pixel
        if (t != null && t.length > 0 && stage.width > 0) {
            val span = t.length + Piece.LEAD_IN
            val b = ((nowMs() - t0) / 60000.0 * bpm * SNIPPET_SPEED) % span - Piece.LEAD_IN
            picture.setScene(Scene(t.feetAt(b), mapOf('L' to t.weights('L', b), 'F' to t.weights('F', b))), nowMs())
            picture.draw(stage.draw, nowMs())
        }
        window.requestAnimationFrame { tick() }
    }

    override fun attach() { window.addEventListener("resize", onResize); Display.onFullscreenChange(showFull); showFull(); start() }
    override fun detach() { window.removeEventListener("resize", onResize); Display.removeListener(showFull); running = false }
}

// ------------------------------------------------------------------ explore

/** Explore (the user's layout draft, 2026-10-28; the app's ExploreActivity): three groups that fold. Each row: the
 *  name, its versions (the starting conditions of an element) as pills under it when they all fit, otherwise a raised
 *  button at the right of the name with a count badge that opens a list; a piece with one version shows where it
 *  starts and ends as plain text. Then three pictures of consecutive steps, centred on the step that stands out most.
 *  Drag a row's pictures sideways to move through its steps, which puts the others back where they began; a tap opens
 *  the dance floor on the first step of the version shown, which is remembered. */
class Explore : Screen {
    override val root: HTMLElement = el("div", "explore")
    private val list = el("div", "list")
    private val thumb = el("div", "thumb")
    private val model = ExploreModel(App.assets.data, App.assets.store)
    private class RowStrip(val stage: Stage, val strip: StripPainter, var centre: Int)
    private val strips = ArrayList<RowStrip>()
    private val fits = ArrayList<() -> Unit>()     // each row with versions decides: pills, or the button with its list
    private var openList: HTMLElement? = null
    private val onResize = { _: Event -> drawAll(); for (f in fits) f(); scrollBar() }
    private val onDocClick = { _: Event -> closeList() }
    private var first = true

    init {
        root.add(list); root.add(thumb)
        group(Texts.ELEMENTS, "elements", true, model.elements)
        group(Texts.COMPOSITIONS, "compositions", true, model.compositions)
        group(Texts.BUILDING_BLOCKS, "blocks", false, model.blocks)
        list.addEventListener("scroll", { scrollBar() })
    }

    /** a heading with its triangle (pointing at the heading when folded, down when open) and its rows */
    private fun group(title: String, key: String, openByDefault: Boolean, rows: List<ExploreModel.Row>) {
        val g = el("div", "group")
        var open = Prefs.bool("explore_open_$key", openByDefault)
        fun refresh() { if (open) g.classList.remove("folded") else g.classList.add("folded") }
        val line = el("div", "gline")
        line.add(el("button", "ghead") {
            add(el("span", "", title)); add(el("span", "tri"))
            onclick = { open = !open; Prefs.put("explore_open_$key", open); refresh(); if (open) { drawAll(); for (f in fits) f() }; scrollBar(); null }
        })
        // the page's own back button, on the first heading's line (the app leaves this to the phone's back button)
        if (first) { first = false; line.add(el("div", "space")); line.add(iconButton("back", Texts.BACK) { App.back() }) }
        g.add(line)
        val box = el("div", "rows")
        for (r in rows) box.add(row(r))
        g.add(box)
        refresh()
        list.add(g)
    }

    private fun closeList() { openList?.let { it.parentNode?.removeChild(it) }; openList = null }

    private fun row(r: ExploreModel.Row): HTMLElement {
        val v = el("div", "row")
        val name = el("div", "name press") { setAttribute("role", "button"); tabIndex = 0 }
        val col = el("div", "col")
        col.add(el("div", "t", r.title))
        name.add(col)
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val rs = RowStrip(Stage(canvas), StripPainter(1.0).apply { cellsAcross = 3.0; separators = true }, 0)
        strips.add(rs)
        var chosen = Prefs.int(r.key, 0).coerceIn(0, r.versions.size - 1)
        val pills = ArrayList<HTMLElement>()
        val chipText = el("span")

        fun show(i: Int) {
            chosen = i
            val ver = r.versions[i]
            chipText.textContent = ver.facing + "\n" + ver.foot
            for ((k, p) in pills.withIndex()) if (k == i) p.classList.add("on") else p.classList.remove("on")
            rs.strip.cells = ver.cells
            rs.centre = model.centre(ver)
            draw(rs, recentre = true)
        }
        fun pick(i: Int) { Prefs.put(r.key, i); show(i) }

        if (r.versions.size == 1) col.add(el("div", "f", r.versions[0].facing + " · " + r.versions[0].foot))
        else {
            // option B: the versions as pills under the name, the one shown black with a tick
            val box = el("div", "pills")
            for ((i, ver) in r.versions.withIndex()) {
                val p = el("button", "pill") {
                    add(icon("check"))
                    add(el("span") { add(el("div", "", ver.facing)); add(el("small", "", ver.footShort)) })
                }
                p.onclick = { it.stopPropagation(); pick(i); null }
                pills.add(p); box.add(p)
            }
            col.add(box)
            // option A, when the pills do not all fit beside the pictures: a raised button with a count badge, and a list
            val chip = el("div", "chip") { hidden = true }
            val chipBox = el("button", "chipbox raised") { title = Texts.CHOOSE_VERSION; add(chipText); add(icon("chevron_down")) }
            chip.add(chipBox); chip.add(el("span", "count", r.versions.size.toString()))
            chipBox.onclick = { e ->
                e.stopPropagation()
                val was = openList != null && openList?.parentNode === chip
                closeList()
                if (!was) {
                    val l = el("div", "verlist raised")
                    for ((i, ver) in r.versions.withIndex())
                        l.add(el("button", if (i == chosen) "on" else "", ver.facing + " · " + ver.foot) { onclick = { ev -> ev.stopPropagation(); closeList(); pick(i); null } })
                    chip.add(l); openList = l
                }
                null
            }
            name.add(chip)
            fits.add {
                if (col.clientWidth > 0) {
                    box.hidden = false; chip.hidden = true
                    val fit = box.scrollWidth <= col.clientWidth
                    box.hidden = !fit; chip.hidden = fit
                }
            }
        }
        val open = { App.player.unlock(); App.go(App.floorPath(r.versions[chosen].piece.kind, r.versions[chosen].piece.name, r.versions[chosen].piece.variant, false)) }
        name.onclick = { open(); null }
        v.add(name)
        val pics = el("div", "pics")
        pics.add(canvas)
        v.add(pics)

        // drag the pictures sideways; a tap opens the floor. The other rows go back to where they began.
        var down = false; var dragging = false; var downX = 0.0; var downY = 0.0; var lastX = 0.0
        canvas.addEventListener("pointerdown", { e -> e as MouseEvent; down = true; dragging = false; downX = Display.x(e); downY = Display.y(e); lastX = downX })
        canvas.addEventListener("pointermove", { e ->
            e as MouseEvent
            if (!down) return@addEventListener
            val x = Display.x(e)
            if (!dragging && abs(x - downX) > 8) {
                dragging = true; lastX = x
                try { canvas.asDynamic().setPointerCapture(e.asDynamic().pointerId) } catch (t: Throwable) {}
                for (o in strips) if (o !== rs) draw(o, recentre = true)
            }
            if (dragging) { rs.strip.offset = (rs.strip.offset - (x - lastX)).coerceIn(0.0, rs.strip.maxOffset()); lastX = x; draw(rs, recentre = false) }
        })
        // a tap opens the floor; a finger that moved (scrolling the list, say) is not a tap
        canvas.addEventListener("pointerup", { e ->
            e as MouseEvent
            if (down) { down = false; if (!dragging && abs(Display.x(e) - downX) < 10 && abs(Display.y(e) - downY) < 10) open(); dragging = false }
        })
        canvas.addEventListener("pointercancel", { down = false; dragging = false })

        show(chosen)
        return v
    }

    private fun draw(rs: RowStrip, recentre: Boolean) {
        val changed = rs.stage.fit()
        if (rs.stage.width <= 0) return   // folded away
        rs.strip.pixel = rs.stage.pixel
        if (changed) rs.strip.setSize(rs.stage.width, rs.stage.height)
        if (recentre || changed) rs.strip.offset = rs.strip.targetFor(rs.centre)
        rs.stage.g.clearRect(0.0, 0.0, rs.stage.width, rs.stage.height)
        rs.strip.draw(rs.stage.draw)
    }

    private fun drawAll() { for (rs in strips) draw(rs, recentre = false) }

    /** the scroll bar at the right, always shown, as the draft has it */
    private fun scrollBar() {
        val view = list.clientHeight.toDouble(); val all = list.scrollHeight.toDouble()
        if (all <= view + 1 || view <= 0) { thumb.hidden = true; return }
        thumb.hidden = false
        val h = (view * view / all).coerceAtLeast(24.0)
        thumb.style.height = "${h}px"
        thumb.style.top = "${(view - h) * (list.scrollTop / (all - view))}px"
    }

    override fun attach() {
        window.addEventListener("resize", onResize)
        document.addEventListener("click", onDocClick)
        for (rs in strips) draw(rs, recentre = true)
        for (f in fits) f()
        scrollBar()
    }
    override fun detach() {
        window.removeEventListener("resize", onResize)
        document.removeEventListener("click", onDocClick)
    }
}

// ------------------------------------------------------------------ settings and the heart page

/** Settings (the app's activity_settings.xml). First, whether a swipe while a composition plays leads in with rock
 *  triple triples (user 2026-11-21: "make this feature toggleable in the settings so that it's possible to disable
 *  it"); then, a little apart, what is heard when the dance floor's mute button is off. */
class Settings : Screen {
    override val root: HTMLElement = el("div", "settings")

    init {
        val head = el("div", "head")
        head.add(el("h1", "", Texts.SETTINGS))
        head.add(iconButton("back", Texts.BACK) { App.back() })
        root.add(head)
        fun switch(label: String, isOn: () -> Boolean, flip: () -> Unit): () -> Unit {
            val b = el("button", "setting box")
            b.setAttribute("role", "switch")
            val refresh = { val on = isOn(); if (on) b.classList.add("on") else b.classList.remove("on"); b.setAttribute("aria-checked", on.toString()) }
            b.add(el("span", "label", label)); b.add(el("span", "sw"))
            b.onclick = { flip(); refresh(); null }
            refresh()
            root.add(b)
            return refresh
        }
        fun bind(label: String, key: String, fallback: Boolean) {
            var on = Prefs.bool(key, fallback)
            switch(label, { on }) { on = !on; Prefs.put(key, on) }
        }
        bind(Texts.SET_LEAD_IN, "lead_in", true)   // first: on a phone the page is longer than the screen, and this one is looked for
        bind(Texts.SET_OVERLAY, "metronome_over_music", false)
        bind(Texts.SET_SNARE, "snare_on", true)
        bind(Texts.SET_HIHAT, "hihat_on", true)
        bind(Texts.SET_VOICE, "speak_annotations", true)
        root.add(el("div", "note", Texts.SET_MUTE_NOTE))
        // the dance floor's hints from the beginning: every flag down again (user 2026-11-21: "add a button in the
        // settings to reset the hints")
        root.add(el("button", "setting box again", Texts.SET_HINTS_AGAIN) {
            onclick = { for (h in Hint.values()) Prefs.remove(h.flag); toast(Texts.HINTS_AGAIN_DONE); null }
        })
        val about = el("div", "about", Texts.about("web"))
        root.add(about)
        // which build of the page this is
        window.fetch("version.txt").then({ r -> if (r.ok) r.text().then({ v -> about.textContent = Texts.about("web " + v.trim()) }) })
    }

}

/** The heart page (the user's layout draft, revised 2026-10-28; the app's HeartActivity): a note from the user and a
 *  button that writes them an email. Upright, as the user drew it (the rest is sideways). The draft's two other
 *  buttons, the user's website and a beer (5, 15 or 30 dollars), are out for now (user 2026-11-21; Texts.HEART_MORE
 *  brings them back: neither is set up yet, and a tap says so). */
class Heart : Screen {
    override val root: HTMLElement = el("div", "heart")
    override val sideways: Boolean get() = false
    private val note = el("div", "note")
    private val words = el("div", "", Texts.HEART_NOTE)
    private val onResize = { _: Event -> fitText(note, words, 12, 24) }

    init {
        root.add(el("div", "top") { add(iconButton("back", Texts.BACK) { App.back() }) })
        note.add(words)
        root.add(note)
        val links = el("div", "links")
        links.add(el("a", "link press") {
            title = Texts.EMAIL_ME; setAttribute("aria-label", Texts.EMAIL_ME)
            val enc = { s: String -> window.asDynamic().encodeURIComponent(s) as String }
            setAttribute("href", "mailto:" + enc(Texts.EMAIL) + "?subject=" + enc(Texts.APP_NAME))   // the "+" of the address encoded, so that no mail app reads it as a space
            add(icon("at"))
        })
        if (Texts.HEART_MORE) {
            links.add(el("button", "link press") {
                title = Texts.MY_WEBSITE; setAttribute("aria-label", Texts.MY_WEBSITE)
                add(icon("globe"))
                onclick = { if (Texts.WEBSITE.isEmpty()) toast(Texts.LINK_NOT_SET) else window.open(Texts.WEBSITE, "_blank"); null }
            })
            links.add(el("button", "link press") {
                title = Texts.BUY_BEER; setAttribute("aria-label", Texts.BUY_BEER)
                add(icon("dollar"))
                onclick = { beer(); null }
            })
        } else links.classList.add("one")   // the email button alone, in the middle
        root.add(links)
    }

    /** "Buy me a Beer" (user 2026-10-29): the title, the beer's words, the amounts, in the heart page's look */
    private fun beer() {
        val m = Modal("beer")
        m.box.add(iconButton("close", Texts.CLOSE, "x press") { m.close() })
        m.box.add(el("div", "title", Texts.BUY_BEER))
        m.box.add(el("div", "words", Texts.HEART_BEER))
        val row = el("div", "amounts")
        for (amount in Texts.BEER_AMOUNTS) row.add(el("button", "raised", "\$$amount") {
            onclick = { if (Texts.PAY_URL.isEmpty()) toast(Texts.LINK_NOT_SET) else window.open(Texts.PAY_URL.replace("%d", amount.toString()), "_blank"); null }
        })
        m.box.add(row)
        m.open()
    }

    override fun attach() { window.addEventListener("resize", onResize); fitText(note, words, 12, 24); window.setTimeout({ fitText(note, words, 12, 24) }, 60) }
    override fun detach() { window.removeEventListener("resize", onResize) }
}
