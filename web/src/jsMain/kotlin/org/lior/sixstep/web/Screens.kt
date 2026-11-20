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
import org.lior.sixstep.Texts
import org.lior.sixstep.Timeline
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** One of the page's screens: its element, and what it does when it is put on the page and taken off it. */
interface Screen {
    val root: HTMLElement
    fun attach() {}
    fun detach() {}
}

// ------------------------------------------------------------------ the launcher

/** The launcher (the user's layout draft, 2026-10-28): the daily exercise with how long it takes, explore, and at the
 *  side the title, a little floor dancing the "quick recap" at 1.5 times the exercise's tempo, and the heart, settings
 *  and help buttons. Help puts the two explanations where the little floor is. */
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
    private val snippet = el("div", "snippet box")
    private val help = el("div", "snippet box") { hidden = true; style.padding = "10px"; style.fontSize = "14px"; style.lineHeight = "1.4" }

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
        bpm = tempo()
        val time = if (dailyBeats <= 0) "" else Texts.minutes(((dailyBeats + 4) * 60.0 / bpm).roundToInt())

        val side = el("div", "side")
        side.add(el("div", "logo box", Texts.LOGO))
        snippet.add(stage.canvas); snippet.add(el("div", "cap", Texts.QUICK_RECAP))
        side.add(snippet)
        help.add(el("p", "", Texts.HELP_DAILY) { style.margin = "0 0 10px 0" }); help.add(el("p", "", Texts.HELP_EXPLORE) { style.margin = "0" })
        side.add(help)
        val small = el("div", "small")
        small.add(iconButton("heart", Texts.HEART, "box press") { App.go("heart") }.also { it.style.background = "var(--heart)" })
        small.add(iconButton("settings", Texts.SETTINGS, "box press") { App.go("settings") }.also { it.style.background = "var(--gear)" })
        small.add(iconButton("circle-question-mark", Texts.HELP, "box press") { setHelp(!helpOn) }.also { it.style.background = "var(--help)" })
        side.add(small)
        root.add(side)

        val tiles = el("div", "tiles")
        tiles.add(el("button", "tile box press") {
            style.background = "var(--daily)"
            add(icon("dumbbell"))
            add(el("div") { add(el("div", "name", Texts.DAILY_EXERCISE)); add(el("div", "time", time)) })
            onclick = {
                App.player.unlock()   // the tap itself lets the browser make sound
                if (daily != null) App.go(App.floorPath(Piece.COMPOSITION, daily.name, 0, true))
                null
            }
        })
        tiles.add(el("button", "tile box press") {
            style.background = "var(--explore)"
            add(icon("telescope"))
            add(el("div") { add(el("div", "name", Texts.EXPLORE)) })
            onclick = { App.go("explore"); null }
        })
        root.add(tiles)
    }

    /** the tempo the daily exercise is danced at: the chosen song's at the chosen offset, or the songs' average when they
     *  are shuffled */
    private fun tempo(): Double {
        val off = Prefs.int("bpm_offset", 0)
        val tracks = App.assets.tracks
        val chosen = tracks.firstOrNull { it.key == Prefs.str("song", MusicPlan.SHUFFLE) }
        if (chosen != null) return chosen.variantFor(off).bpm
        return if (tracks.isEmpty()) 120.0 else tracks.map { it.variantFor(off).bpm }.average()
    }

    private fun setHelp(on: Boolean) {
        helpOn = on
        help.hidden = !on; snippet.hidden = on
        if (!on) start()
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
        if (t != null && t.length > 0 && stage.width > 0) {
            val span = t.length + Piece.LEAD_IN
            val b = ((nowMs() - t0) / 60000.0 * bpm * SNIPPET_SPEED) % span - Piece.LEAD_IN
            picture.setScene(Scene(t.feetAt(b), mapOf('L' to t.weights('L', b), 'F' to t.weights('F', b))), nowMs())
            picture.draw(stage.draw, nowMs())
        }
        window.requestAnimationFrame { tick() }
    }

    override fun attach() { start() }
    override fun detach() { running = false }
}

// ------------------------------------------------------------------ explore

/** Explore (the user's layout draft, 2026-10-28): three groups that fold. Each row: the name, its versions as pills
 *  when there are several (the starting conditions of an element), otherwise where it starts and ends; then three
 *  pictures of consecutive steps, centred on the step that stands out most. Drag a row's pictures sideways to move
 *  through its steps; a tap opens the dance floor on the first step of the version shown, which is remembered. */
class Explore : Screen {
    override val root: HTMLElement = el("div", "page")
    private val model = ExploreModel(App.assets.data, App.assets.store)
    private class RowStrip(val stage: Stage, val strip: StripPainter, var centre: Int)
    private val strips = ArrayList<RowStrip>()
    private val onResize = { _: Event -> drawAll() }

    init {
        val head = el("div", "head")
        head.add(iconButton("undo-2", Texts.BACK) { App.back() })
        head.add(el("h1", "", Texts.EXPLORE))
        root.add(head)
        group(Texts.ELEMENTS, "elements", true, model.elements)
        group(Texts.COMPOSITIONS, "compositions", true, model.compositions)
        group(Texts.BUILDING_BLOCKS, "blocks", false, model.blocks)
    }

    private fun group(title: String, key: String, openByDefault: Boolean, rows: List<ExploreModel.Row>) {
        val g = el("div", "group")
        var open = Prefs.bool("explore_open_$key", openByDefault)
        fun refresh() { if (open) g.classList.remove("folded") else g.classList.add("folded") }
        val headBtn = el("button", "ghead") {
            add(el("span", "tri")); add(el("span", "", title))
            onclick = { open = !open; Prefs.put("explore_open_$key", open); refresh(); if (open) drawAll(); null }
        }
        g.add(headBtn)
        val box = el("div", "rows")
        for (r in rows) box.add(row(r))
        g.add(box)
        refresh()
        root.add(g)
    }

    private fun row(r: ExploreModel.Row): HTMLElement {
        val v = el("div", "row")
        val name = el("button", "name press")
        name.add(el("div", "t", r.title))
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val rs = RowStrip(Stage(canvas), StripPainter(1.0).apply { cellsAcross = 3.0; separators = true }, 0)
        strips.add(rs)
        var chosen = Prefs.int(r.key, 0).coerceIn(0, r.versions.size - 1)
        val pills = ArrayList<HTMLElement>()

        fun show(i: Int) {
            chosen = i
            val ver = r.versions[i]
            for ((k, p) in pills.withIndex()) if (k == i) p.classList.add("on") else p.classList.remove("on")
            rs.strip.cells = ver.cells
            rs.centre = model.centre(ver)
            draw(rs, recentre = true)
        }

        if (r.versions.size == 1) name.add(el("div", "f", r.versions[0].facing + " · " + r.versions[0].foot))
        else {
            val box = el("div", "pills")
            for ((i, ver) in r.versions.withIndex()) {
                val p = el("span", "pill") { add(el("div", "", ver.facing)); add(el("small", "", ver.footShort)) }
                p.onclick = { it.stopPropagation(); Prefs.put(r.key, i); show(i); null }
                pills.add(p); box.add(p)
            }
            name.add(box)
        }
        val open = { App.player.unlock(); App.go(App.floorPath(r.versions[chosen].piece.kind, r.versions[chosen].piece.name, r.versions[chosen].piece.variant, false)) }
        name.onclick = { open(); null }
        v.add(name)
        val pics = el("div", "pics")
        pics.add(canvas)
        v.add(pics)

        // drag the pictures sideways; a tap opens the floor. The other rows go back to where they began.
        var down = false; var dragging = false; var downX = 0.0; var lastX = 0.0
        canvas.addEventListener("pointerdown", { e -> e as MouseEvent; down = true; dragging = false; downX = e.clientX.toDouble(); lastX = downX })
        canvas.addEventListener("pointermove", { e ->
            e as MouseEvent
            if (!down) return@addEventListener
            val x = e.clientX.toDouble()
            if (!dragging && abs(x - downX) > 8) {
                dragging = true; lastX = x
                try { canvas.asDynamic().setPointerCapture(e.asDynamic().pointerId) } catch (t: Throwable) {}
                for (o in strips) if (o !== rs) draw(o, recentre = true)
            }
            if (dragging) { rs.strip.offset = (rs.strip.offset - (x - lastX)).coerceIn(0.0, rs.strip.maxOffset()); lastX = x; draw(rs, recentre = false) }
        })
        canvas.addEventListener("pointerup", { if (down) { down = false; if (!dragging) open(); dragging = false } })
        canvas.addEventListener("pointercancel", { down = false; dragging = false })

        show(chosen)
        return v
    }

    private fun draw(rs: RowStrip, recentre: Boolean) {
        val changed = rs.stage.fit()
        if (rs.stage.width <= 0) return   // folded away
        if (changed) rs.strip.setSize(rs.stage.width, rs.stage.height)
        if (recentre || changed) rs.strip.offset = rs.strip.targetFor(rs.centre)
        rs.stage.g.clearRect(0.0, 0.0, rs.stage.width, rs.stage.height)
        rs.strip.draw(rs.stage.draw)
    }

    private fun drawAll() { for (rs in strips) draw(rs, recentre = false) }

    override fun attach() {
        window.addEventListener("resize", onResize)
        for (rs in strips) draw(rs, recentre = true)
    }
    override fun detach() { window.removeEventListener("resize", onResize) }
}

// ------------------------------------------------------------------ settings and the heart page

/** Settings: what is heard when the dance floor's mute button is off. */
class Settings : Screen {
    override val root: HTMLElement = el("div", "page plain")

    init {
        val head = el("div", "head")
        head.add(iconButton("undo-2", Texts.BACK) { App.back() })
        head.add(el("h1", "", Texts.SETTINGS))
        root.add(head)
        fun bind(label: String, key: String, fallback: Boolean) {
            var on = Prefs.bool(key, fallback)
            val b = el("button", "setting box press")
            fun refresh() { if (on) b.classList.add("on") else b.classList.remove("on"); b.setAttribute("aria-pressed", on.toString()) }
            b.add(el("span", "sw")); b.add(el("span", "", label))
            b.onclick = { on = !on; Prefs.put(key, on); refresh(); null }
            refresh()
            root.add(b)
        }
        bind(Texts.SET_OVERLAY, "metronome_over_music", false)
        bind(Texts.SET_SNARE, "snare_on", true)
        bind(Texts.SET_HIHAT, "hihat_on", true)
        bind(Texts.SET_VOICE, "speak_annotations", true)
        root.add(el("div", "note", Texts.SET_MUTE_NOTE))
        val about = el("div", "note", Texts.about("web"))
        root.add(about)
        // which build of the page this is
        window.fetch("version.txt").then({ r -> if (r.ok) r.text().then({ v -> about.textContent = Texts.about("web " + v.trim()) }) })
    }
}

/** The heart page: a note from the user, and a link that writes them an email. */
class Heart : Screen {
    override val root: HTMLElement = el("div", "page")

    init {
        val head = el("div", "head")
        head.add(iconButton("undo-2", Texts.BACK) { App.back() })
        head.add(el("h1", "", Texts.HEART))
        root.add(head)
        root.add(el("div", "heartnote", Texts.HEART_NOTE))
        root.add(el("a", "linkbtn press") {
            val enc = { s: String -> window.asDynamic().encodeURIComponent(s) as String }
            setAttribute("href", "mailto:" + enc(Texts.EMAIL) + "?subject=" + enc(Texts.APP_NAME))
            add(icon("at-sign")); add(el("span", "", Texts.EMAIL_ME))
        })
    }
}
