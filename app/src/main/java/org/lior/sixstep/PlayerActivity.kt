package org.lior.sixstep

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.floor

/** Plays a composition on the music: the dance drawn in time, annotations shown large and read aloud, the music changing
 *  between tracks with the snare and hi-hat filling the gap, and the whole thing kept in one beat clock.
 *  Swipe left: the next annotation block. Swipe right: replay this block, or the previous one when this one just began. */
class PlayerActivity : AppCompatActivity() {
    companion object { const val BACK_REPLAY_THRESHOLD_SECONDS = 2.0 }   // as in the Workout Instructinator app

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
    private var lastAnnouncedElement = -1
    private var banner: String? = null
    private var bannerUntilBeat = -1.0
    private var blockStarts: List<Double> = emptyList()
    private var pendingStart: Runnable? = null

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
        val g = BeatGrid.loadFor(this, first)
        val composed = Composer.compose(data, store.sequence(comp), comp.startState, g.swing)
        if (composed.errors.isNotEmpty()) { status.text = composed.errors.joinToString("\n"); return }
        val tl = Timeline(composed, g.swing); timeline = tl
        // the places a swipe jumps to: annotation blocks (the daily exercise), else every element
        val starts = composed.elements.filter { it.annotation != null || (daily && it.entryStart) }.map { it.start }
        blockStarts = (if (starts.isEmpty()) composed.elements.map { it.start } else starts).distinct().sorted()
        if (speakOn) speaker.prefetch(announcements(composed))

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { togglePause(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dx) > 80 && abs(dx) > abs(dy) * 1.2) { if (dx < 0) skipNext() else skipBack(); return true }
                return false
            }
        })
        floor.setOnTouchListener { v, ev -> gestures.onTouchEvent(ev); if (ev.action == MotionEvent.ACTION_UP) v.performClick(); true }
        startTrack(0, 0.0, countIn = true)
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

    private fun currentBeat(): Double = if (paused) pausedAtBeat else (clock?.beatNow() ?: 0.0)

    /** start track i of the shuffled order so that its first dance bar is dance beat `atBeat` */
    private fun startTrack(i: Int, atBeat: Double, countIn: Boolean) {
        val t = trackOrder[i % trackOrder.size]; trackIdx = i
        val g = BeatGrid.loadFor(this, t); grid = g; k0 = g.startBeatIndex()
        val mp = music.prepare(t)
        val startSec = g.beats[(k0 - (if (countIn) 4 else 0)).coerceAtLeast(0)]
        music.onComplete = { onTrackEnded() }
        val old = clock
        music.startAt(startSec)
        val mc = MusicClock(mp, g, k0, atBeat)
        if (old == null || countIn) {
            clock = mc
        } else {
            // the music starts a little late: hand over without a jump, fading the difference out over a second
            clock = old
            handler.postDelayed({
                if (finished || paused) return@postDelayed
                mc.resync()
                val off = old.beatNow() - mc.beatNow()
                clock = SlewClock(mc, off, 1200)
                metronome.clock = clock; metronome.enabled = overlay; metronome.resync()
                status.text = "${t.title} — ${t.artist}" + (if (t.difficulty.isNotBlank()) "  · " + t.difficulty else "")
            }, 220)
        }
        metronome.clock = clock; metronome.swing = g.swing; metronome.enabled = overlay; metronome.resync(); metronome.start()
        status.text = "${t.title} — ${t.artist}" + (if (t.difficulty.isNotBlank()) "  · " + t.difficulty else "")
    }

    /** the music ran out: keep the beat going on the snare and hi-hat, start the next track at the next block */
    private fun onTrackEnded() {
        if (finished || paused) return
        val g = grid ?: return
        val b = currentBeat()
        val tempo = TempoClock(g.bpm, b, SystemClock.uptimeMillis())
        clock = tempo; metronome.clock = tempo; metronome.enabled = true; metronome.resync()
        scheduleNextTrack(b)
        status.text = "snare and hi-hat until the next track"
    }

    /** start the next track at the first block boundary at least 2 beats ahead of `fromBeat` */
    private fun scheduleNextTrack(fromBeat: Double) {
        val tl = timeline ?: return; val c = clock ?: return
        pendingStart?.let { handler.removeCallbacks(it) }; pendingStart = null
        val next = tl.composed.elements.map { it.start }.firstOrNull { it >= fromBeat + 2.0 } ?: (floor(fromBeat) + 4.0)
        if (next >= tl.length) return
        val nextIdx = trackIdx + 1
        val delay = (c.uptimeOfBeat(next) - SystemClock.uptimeMillis() - 90).coerceAtLeast(0L)
        val r = Runnable { if (!finished && !paused) startTrack(nextIdx, next, countIn = false) }
        pendingStart = r
        handler.postDelayed(r, delay)
    }

    /** move the whole session to a beat: the music seeks when it is playing, the tempo clock is re-anchored otherwise */
    private fun jumpTo(b0: Double) {
        val tl = timeline ?: return
        val b = b0.coerceIn(0.0, tl.length - 1e-3)
        val c = clock
        val mc = when (c) { is MusicClock -> c; is SlewClock -> c.inner as? MusicClock; else -> null }
        if (mc != null && music.player != null) {
            val sec = mc.grid.beatSeconds(b - mc.danceBeatOffset + mc.k0) + mc.grid.offset
            if (sec < mc.grid.duration - 1.0) {
                music.seekTo(sec); mc.resync()
                clock = mc
            } else {
                val tempo = TempoClock(mc.grid.bpm, b, SystemClock.uptimeMillis()); clock = tempo; music.pause()
                scheduleNextTrack(b)
            }
        } else {
            val tempo = (c as? TempoClock) ?: TempoClock(grid?.bpm ?: 120.0, b, SystemClock.uptimeMillis())
            tempo.reanchor(b, SystemClock.uptimeMillis()); clock = tempo
            scheduleNextTrack(b)
        }
        if (paused) pausedAtBeat = b
        metronome.clock = clock; metronome.resync()
        speaker.stop(); lastAnnouncedElement = -1; banner = null
    }

    private fun skipNext() {
        val b = currentBeat()
        val next = blockStarts.firstOrNull { it > b + 0.05 } ?: return
        jumpTo(next)
    }

    private fun skipBack() {
        val b = currentBeat()
        val g = grid
        val thresholdBeats = BACK_REPLAY_THRESHOLD_SECONDS * (g?.bpm ?: 120.0) / 60.0
        val cur = blockStarts.lastOrNull { it <= b + 0.05 } ?: 0.0
        val target = if (b - cur > thresholdBeats) cur else (blockStarts.lastOrNull { it < cur - 0.05 } ?: 0.0)
        jumpTo(target)
    }

    private fun togglePause() {
        if (finished) { finish(); return }
        val c = clock ?: return
        if (!paused) {
            paused = true; pausedAtBeat = c.beatNow(); music.pause(); metronome.stop(); speaker.stop()
            pendingStart?.let { handler.removeCallbacks(it) }; pendingStart = null
            status.text = "paused — tap to continue, swipe for the next or previous part"
        } else {
            paused = false
            val mc = when (c) { is MusicClock -> c; is SlewClock -> c.inner as? MusicClock; else -> null }
            if (mc != null && music.player != null) {
                music.seekTo(mc.grid.beatSeconds(pausedAtBeat - mc.danceBeatOffset + mc.k0) + mc.grid.offset); music.resume(); mc.resync(); clock = mc
            } else {
                clock = TempoClock(grid?.bpm ?: 120.0, pausedAtBeat, SystemClock.uptimeMillis()); scheduleNextTrack(pausedAtBeat)
            }
            metronome.clock = clock; metronome.resync(); metronome.start()
            status.text = ""
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            val tl = timeline; val c = clock
            if (tl != null && c != null && !finished) {
                val b = currentBeat()
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
        val idx = tl.elementIndexAt(b)
        val el = if (idx < 0) null else tl.composed.elements[idx]
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
