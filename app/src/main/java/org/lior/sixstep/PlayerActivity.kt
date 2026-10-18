package org.lior.sixstep

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.floor

/** Plays a composition on the music: the dance drawn in time, annotations shown large and read aloud, the music changing
 *  between tracks with the thump tick-tick filling the gap, and the whole thing kept in one beat clock. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var floor: FloorView
    private lateinit var status: TextView
    private lateinit var data: DanceData
    private lateinit var store: CompositionStore
    private lateinit var tracks: List<Track>
    private lateinit var metronome: Metronome
    private lateinit var speaker: Speaker
    private val music by lazy { MusicPlayer(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var timeline: Timeline? = null
    private var clock: BeatClock? = null
    private var trackOrder = ArrayList<Track>()
    private var trackIdx = -1
    private var grid: BeatGrid? = null
    private var k0 = 0
    private var paused = false
    private var pausedAtBeat = 0.0
    private var finished = false
    private var daily = false
    private var overlay = false
    private var speakOn = true
    private var fillingUntil = Double.NaN     // the beat where the next track starts, while the metronome fills
    private var lastAnnouncedElement = -1
    private var banner: String? = null
    private var bannerUntilBeat = -1.0
    private val smoother = Smoother(1.2)
    private var follow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        floor = findViewById(R.id.floor); status = findViewById(R.id.status)
        data = DanceData.load(this); store = CompositionStore(this); tracks = Track.loadAll(this)
        metronome = Metronome(this); speaker = Speaker(this)
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        overlay = prefs.getBoolean("metronome_over_music", false); speakOn = prefs.getBoolean("speak_annotations", true)
        daily = intent.getBooleanExtra("daily", false)
        val name = intent.getStringExtra("composition") ?: ""
        val comp = store.find(name) ?: store.subs.firstOrNull { it.name == name }
        if (comp == null) { status.text = "no composition named $name"; return }
        trackOrder = ArrayList(tracks.shuffled())
        val first = trackOrder[0]
        val g = BeatGrid.load(this, first.gridPath)
        val composed = Composer.compose(data, store.sequence(comp), comp.startState, g.swing)
        if (composed.errors.isNotEmpty()) { status.text = composed.errors.joinToString("\n"); return }
        timeline = Timeline(composed, g.swing)
        // camera: the whole dance if it fits, otherwise follow the couple
        val xs = composed.steps.map { it.x } + composed.start.values.map { it.x }
        val ys = composed.steps.map { it.y } + composed.start.values.map { it.y }
        floor.post {
            floor.fitTo(xs, ys, 1.0, 5.0)
            follow = floor.scale < 2.6
            if (follow) floor.scale = 3.2
        }
        if (speakOn) speaker.prefetch(announcements(composed))
        floor.setOnClickListener { togglePause() }
        startTrack(0, 0.0)
        handler.post(frame)
    }

    private fun announcements(c: Composed): List<String> {
        val out = ArrayList<String>()
        for (e in c.elements) { e.annotation?.let { out.add(it) }; if (daily && e.entryStart) out.add(spokenLabel(e)) }
        return out.distinct()
    }

    /** "RTT x12" -> "RTT, 12 times" */
    private fun spokenLabel(e: CElement): String {
        val m = Regex("^(.*?)\\s*x(\\d+)$").matchEntire(e.entryLabel)
        return if (m != null) "${m.groupValues[1]}, ${m.groupValues[2]} times" else e.entryLabel
    }

    /** start track i of the shuffled order so that its first dance bar is dance beat `atBeat` */
    private fun startTrack(i: Int, atBeat: Double) {
        val t = trackOrder[i % trackOrder.size]; trackIdx = i
        val g = BeatGrid.load(this, t.gridPath); grid = g; k0 = g.startBeatIndex()
        val mp = music.prepare(t)
        val countIn = if (atBeat == 0.0) 4 else 0
        val startSec = g.beats[(k0 - countIn).coerceAtLeast(0)]
        music.onComplete = { onTrackEnded() }
        music.startAt(startSec)
        clock = MusicClock(mp, g, k0, atBeat)
        metronome.clock = clock; metronome.swing = g.swing; metronome.enabled = overlay; metronome.resync(); metronome.start()
        fillingUntil = Double.NaN
        status.text = "${t.title} — ${t.artist}"
    }

    /** the music ran out: keep the beat going on the thump tick-tick, start the next track at the next entry boundary */
    private fun onTrackEnded() {
        val g = grid ?: return; val tl = timeline ?: return
        val c = clock ?: return
        val b = c.beatNow()
        val tempo = TempoClock(g.bpm, b, SystemClock.uptimeMillis())
        clock = tempo; metronome.clock = tempo; metronome.enabled = true; metronome.resync()
        // the next element boundary at least 2 beats away (a whole number of beats keeps the counts in step)
        val next = tl.composed.elements.map { it.start }.firstOrNull { it >= b + 2.0 } ?: (floor(b) + 4.0)
        if (next >= tl.length) { fillingUntil = Double.NaN; return }
        fillingUntil = next
        val nextIdx = trackIdx + 1
        val delay = (tempo.uptimeOfBeat(next) - SystemClock.uptimeMillis() - 60).coerceAtLeast(0L)
        handler.postDelayed({ if (!finished && !paused) startTrack(nextIdx, next) }, delay)
        status.text = "thump tick-tick until the next track"
    }

    private fun togglePause() {
        if (finished) { finish(); return }
        val c = clock ?: return
        if (!paused) {
            paused = true; pausedAtBeat = c.beatNow(); music.pause(); metronome.stop(); speaker.stop()
            status.text = "paused — tap to continue"
        } else {
            paused = false
            if (music.player != null && clock is MusicClock) { music.resume() } else {
                clock = TempoClock(grid?.bpm ?: 120.0, pausedAtBeat, SystemClock.uptimeMillis()); metronome.clock = clock
            }
            metronome.resync(); metronome.start()
            status.text = ""
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            val tl = timeline; val c = clock
            if (tl != null && c != null && !finished) {
                val b = if (paused) pausedAtBeat else c.beatNow()
                if (b >= tl.length) {
                    finished = true; music.pause(); metronome.stop()
                    floor.scene = Scene(tl.feetAt(tl.length - 1e-6), mapOf('L' to tl.weights('L', tl.length), 'F' to tl.weights('F', tl.length)), tl.handsAt(tl.length), "done", "", emptyList(), "Done. Tap to leave.")
                } else {
                    draw(tl, b)
                }
            }
            handler.postDelayed(this, 16)
        }
    }

    private fun draw(tl: Timeline, b0: Double) {
        val b = if (b0 < 0) -0.001 else b0
        val el = tl.elementAt(b)
        val idx = if (el == null) -1 else tl.composed.elements.indexOf(el)
        if (el != null && idx != lastAnnouncedElement) {
            lastAnnouncedElement = idx
            val parts = ArrayList<String>()
            el.annotation?.let { parts.add(it) }
            if (daily && el.entryStart) parts.add(spokenLabel(el))
            if (parts.isNotEmpty()) {
                banner = parts.joinToString("\n"); bannerUntilBeat = b + 3.0
                if (speakOn && !paused) speaker.speak(parts.joinToString(". "))
            }
        }
        if (b > bannerUntilBeat) banner = null
        val feet = tl.feetAt(b)
        if (follow) {
            val cxw = feet.values.sumOf { it.x } / 4; val cyw = feet.values.sumOf { it.y } / 4
            val (sx, sy) = smoother.update(cxw, cyw, SystemClock.uptimeMillis()); floor.cx = sx; floor.cy = sy
        }
        val title = when {
            b0 < 0 -> "ready"
            el == null -> ""
            el.filler -> el.name + " — triple step filler"
            el.group != null -> el.name + "   (" + el.group + ")"
            else -> el.name
        }
        floor.scene = Scene(feet, mapOf('L' to tl.weights('L', b), 'F' to tl.weights('F', b)), tl.handsAt(b), title,
            if (b0 < 0) "" else tl.countLabel(b), emptyList(), if (paused) "paused" else banner,
            if (el?.filler == true) getColor(R.color.filler) else null)
    }

    override fun onPause() {
        super.onPause()
        if (!paused && !finished && clock != null) togglePause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        music.release(); metronome.release(); speaker.release()
    }
}
