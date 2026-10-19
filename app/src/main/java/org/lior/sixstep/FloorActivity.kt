package org.lior.sixstep

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.floor

/** The exploration dance floor: swipe through the beats, strategic view (skeleton + dashed arrows) or flow view (the
 *  transition plays), swipe down for the options bar (view toggle, play on the snare and hi-hat, BPM, the element map).
 *  Play loops the piece; when it closes on itself (same state and free foot at the end) the loop is danced on from where
 *  the dancers are, exactly as the player does, so the last beat flows into the first. */
class FloorActivity : AppCompatActivity() {
    private lateinit var floor: FloorView
    private lateinit var bar: View
    private lateinit var toggle: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var bpmBar: SeekBar
    private lateinit var bpmLabel: TextView
    private lateinit var mapOverlay: View
    private lateinit var mapGrid: RecyclerView
    private lateinit var data: DanceData
    private lateinit var store: CompositionStore
    private lateinit var metronome: Metronome
    private val handler = Handler(Looper.getMainLooper())

    private var timeline: Timeline? = null       // the piece itself: what the swipes step through
    private var loopTl: Timeline? = null         // the piece padded and chained for play; null when it cannot close
    private var loopBeats = 0.0                  // one padded loop inside loopTl
    private var stops: List<Double> = emptyList()
    private var loopStops: List<Double> = emptyList()
    private var idx = 0
    private var strategic = true
    private var beat = 0.0
    private var anim: ValueAnimator? = null
    private var playing = false
    private var clock: TempoClock? = null
    private var bpm = 120
    private var barShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        floor = findViewById(R.id.floor); bar = findViewById(R.id.optionsBar); toggle = findViewById(R.id.viewToggle)
        playBtn = findViewById(R.id.playPause); bpmBar = findViewById(R.id.bpm); bpmLabel = findViewById(R.id.bpmLabel)
        mapOverlay = findViewById(R.id.mapOverlay); mapGrid = findViewById(R.id.mapGrid)
        data = DanceData.load(this); store = CompositionStore(this); metronome = Metronome(this)
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        strategic = prefs.getBoolean("strategic", true); bpm = prefs.getInt("bpm", 120)

        val kind = intent.getStringExtra("kind") ?: "element"; val name = intent.getStringExtra("name") ?: ""
        val stateExtra = intent.getStringExtra("state"); val freeExtra = intent.getStringExtra("free")?.firstOrNull()
        var seq: List<SeqItem>? = null; var startState = "side"; var free: Char? = null
        when (kind) {
            "element" -> { val v = data.variants(name).getOrNull(intent.getIntExtra("variant", 0)); if (v != null) { seq = listOf(SeqItem(name, null, null, 0, true, name)); startState = v.startState; free = v.leaderFreeStart } }
            "sub" -> store.subs.firstOrNull { it.name == name }?.let { seq = store.sequence(it); startState = stateExtra ?: it.startState; free = freeExtra }
            else -> store.find(name)?.let { seq = store.sequence(it); startState = it.startState }
        }
        val s0 = seq
        val composed = if (s0 == null) null else Composer.compose(data, s0, startState, 0.67, free)
        if (s0 == null || composed == null || composed.errors.isNotEmpty()) {
            floor.scene = Scene(emptyMap(), emptyMap(), emptyList(), name, "", emptyList(), composed?.errors?.joinToString("\n") ?: "nothing to show"); return
        }
        val tl = Timeline(composed, 0.67); timeline = tl
        stops = listOf(0.0) + tl.beatInstants().filter { it > 0 }
        // opened from the player at a beat inside the element: start on that instant, in the strategic view, not playing
        if (intent.hasExtra("atBeat")) { val at = intent.getDoubleExtra("atBeat", 0.0); var i = 0; for (k in stops.indices) if (stops[k] <= at + 1e-6) i = k; idx = i; strategic = true }
        loopTl = chainedLoop(s0, startState, free)
        loopStops = loopTl?.let { listOf(0.0) + it.beatInstants().filter { b -> b > 0 } } ?: stops
        floor.post { show() }

        toggle.setOnClickListener { strategic = !strategic; prefs.edit().putBoolean("strategic", strategic).apply(); updateBar(); show() }
        playBtn.setOnClickListener { if (playing) stopPlay() else startPlay() }
        findViewById<ImageButton>(R.id.closeBar).setOnClickListener { hideBar() }
        findViewById<ImageButton>(R.id.showMap).setOnClickListener { showMap() }
        findViewById<ImageButton>(R.id.closeMap).setOnClickListener { mapOverlay.visibility = View.GONE }
        bpmBar.max = 140; bpmBar.progress = bpm - 60
        bpmBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { bpm = p + 60; bpmLabel.text = "$bpm BPM"; clock?.let { c -> c.reanchor(c.beatNow(), SystemClock.uptimeMillis()); c.bpm = bpm.toDouble() }; metronome.resync() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt("bpm", bpm).apply() }
        })
        updateBar()

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dy) > abs(dx) * 1.5 && abs(dy) > 80) { if (dy > 0) showBar() else hideBar(); return true }
                if (abs(dx) > 60 && !playing) { if (dx < 0) step(1) else step(-1); return true }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (playing) stopPlay(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { if (!playing) startPlay(); return true }
        })
        floor.setOnTouchListener { v, ev -> gestures.onTouchEvent(ev); if (ev.action == MotionEvent.ACTION_UP) v.performClick(); true }
    }

    /** the piece padded with triple steps until it closes, then chained many times: the loop for play mode */
    private fun chainedLoop(seq: List<SeqItem>, startState: String, free: Char?): Timeline? {
        val one = Composer.compose(data, seq, startState, 0.67, free, padToLoop = true)
        if (one.errors.isNotEmpty() || !one.closes) return null
        loopBeats = one.length
        val names = one.elements.map { SeqItem(it.name, it.group, null, it.entryIndex, it.entryStart, it.entryLabel) }
        val n = (400.0 / one.length).toInt().coerceIn(2, 64)
        val chain = ArrayList<SeqItem>(); repeat(n) { chain.addAll(names) }
        val c = Composer.compose(data, chain, startState, 0.67, free)
        if (c.errors.isNotEmpty()) return null
        val m = one.elements.size
        val elements = c.elements.mapIndexed { k, e -> e.copy(filler = one.elements[k % m].filler) }
        val steps = c.steps.map { s -> s.copy(filler = elements[s.element].filler) }
        return Timeline(Composed(steps, elements, c.start, c.startHands, c.startState, c.startFree, c.endState, c.endFree, c.length, c.errors), 0.67)
    }

    private fun updateBar() {
        toggle.setImageResource(if (strategic) R.drawable.ic_strategic else R.drawable.ic_flow)
        toggle.contentDescription = getString(if (strategic) R.string.strategic_view else R.string.flow_view)
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        bpmLabel.text = "$bpm BPM"
    }
    private fun showBar() { if (!barShown) { barShown = true; bar.visibility = View.VISIBLE; bar.translationY = -bar.height.toFloat(); bar.animate().translationY(0f).setDuration(180).start() } }
    private fun hideBar() { if (barShown) { barShown = false; bar.animate().translationY(-bar.height.toFloat()).setDuration(180).withEndAction { bar.visibility = View.GONE }.start() } }

    /** the scene at a beat of a timeline. settled: the weight has arrived on whatever landed at this instant (the stop view).
     *  In strategic view every foot that landed on this instant gets a dashed arrow from where it was, both dancers at once. */
    private fun sceneAt(tl: Timeline, b: Double, settled: Boolean): Scene {
        val feet = tl.feetAt(b)
        val wt = if (settled) tl.settledAfter(b) else b
        val weights = mapOf('L' to tl.weights('L', wt), 'F' to tl.weights('F', wt))
        val arrows = ArrayList<Arrow>()
        if (strategic) {
            for (d in listOf('L', 'F')) for (f in listOf('L', 'R')) {
                val track = tl.dancers[d]!!.tracks[f]!!
                val (cur, _) = track.around(b)
                if (abs(cur.tLand - b) < 1e-6) {
                    val i = track.landings.indexOf(cur)
                    if (i > 0) { val prev = track.landings[i - 1].p; if (abs(prev.x - cur.p.x) > 0.5 || abs(prev.y - cur.p.y) > 0.5) arrows.add(Arrow(d, prev, cur.p)) }
                }
            }
        }
        val el = tl.elementAt(b)
        val title = when { el == null -> ""; el.filler -> el.name + " — triple step filler"; el.group != null -> el.name + "   (" + el.group + ")"; else -> el.name }
        return Scene(feet, weights, tl.handsAt(b), title, tl.countLabel(b), arrows, null, if (el?.filler == true) getColor(R.color.filler) else null)
    }

    private fun show() { val tl = timeline ?: return; beat = stops[idx]; floor.scene = sceneAt(tl, beat, true) }

    /** move one beat instant forward or back. The flow view plays the transition from the settled state of one instant to
     *  the settled state of the next, at twice the beat length of the BPM slider, with a constant acceleration and
     *  deceleration of about a sixth of a second at each end. */
    private fun step(dir: Int) {
        val tl = timeline ?: return
        val next = idx + dir
        if (next < 0 || next >= stops.size) return
        anim?.cancel()
        val from = stops[idx]; val to = stops[next]; idx = next
        if (strategic) { show(); return }
        val tFrom = tl.settledAfter(from); val tTo = tl.settledAfter(to)
        val durationMs = (abs(to - from) * 60.0 / bpm * 2.0 * 1000.0).coerceIn(250.0, 4000.0)
        val ramp = (1.0 / 6.0 * 1000.0 / durationMs).coerceAtMost(0.45)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs.toLong()
        a.interpolator = TrapezoidInterpolator(ramp.toFloat())
        a.addUpdateListener { v -> val u = (v.animatedValue as Float).toDouble(); val t = tFrom + (tTo - tFrom) * u
            beat = t; floor.scene = sceneAt(tl, t, false) }
        a.start(); anim = a
    }

    /** the beat moves along the snare and hi-hat at the slider's tempo over the chained loop; strategic view jumps from instant to instant */
    private fun startPlay() {
        val tl = timeline ?: return
        val lt = loopTl ?: tl
        val lstops = if (loopTl != null) loopStops else stops
        anim?.cancel()
        val c = TempoClock(bpm.toDouble(), stops[idx], SystemClock.uptimeMillis()); clock = c
        metronome.clock = c; metronome.swing = 0.67; metronome.enabled = true; metronome.resync(); metronome.start()
        playing = true; updateBar()
        handler.post(object : Runnable {
            override fun run() {
                if (!playing) return
                var b = c.beatNow()
                if (b >= lt.length) { b -= lt.length; c.reanchor(b, SystemClock.uptimeMillis()); metronome.resync() }
                var i = 0; for (k in lstops.indices) if (lstops[k] <= b + 1e-6) i = k
                beat = b
                floor.scene = if (strategic) sceneAt(lt, lstops[i], true) else sceneAt(lt, b, false)
                handler.postDelayed(this, 16)
            }
        })
    }

    private fun stopPlay() {
        val tl = timeline
        playing = false; metronome.stop(); clock = null; updateBar()
        if (tl != null) {
            // back to the piece itself, at the beat the loop was showing
            val local = if (loopTl != null && loopBeats > 0) beat - floor(beat / loopBeats) * loopBeats else beat
            var i = 0; if (local < tl.length) for (k in stops.indices) if (stops[k] <= local + 1e-6) i = k
            idx = i
        }
        show()
    }

    /** the map: one small floor per element of the piece, with its name, group and annotation; tap to jump there */
    private fun showMap() {
        val tl = timeline ?: return
        hideBar()
        val items = tl.composed.elements.mapIndexed { k, e -> Pair(k, e) }
        mapGrid.layoutManager = GridLayoutManager(this, 4)
        mapGrid.adapter = object : RecyclerView.Adapter<MapHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MapHolder {
                val col = LinearLayout(parent.context); col.orientation = LinearLayout.VERTICAL; col.setPadding(8, 8, 8, 8)
                col.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val thumb = FloorView(parent.context); thumb.autoCamera = false; thumb.showLabels = false
                val side = (parent.width / 4 - 16).coerceAtLeast(120)
                thumb.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (side * 0.75).toInt())
                val label = TextView(parent.context); label.textSize = 12f; label.setTextColor(parent.context.getColor(R.color.ink)); label.gravity = Gravity.CENTER_HORIZONTAL
                val note = TextView(parent.context); note.textSize = 11f; note.setTextColor(parent.context.getColor(R.color.accent)); note.gravity = Gravity.CENTER_HORIZONTAL
                col.addView(thumb); col.addView(label); col.addView(note)
                return MapHolder(col, thumb, label, note)
            }
            override fun onBindViewHolder(h: MapHolder, position: Int) {
                val (k, e) = items[position]
                val t = tl.settledAfter(e.start)
                val s = Scene(tl.feetAt(t), mapOf('L' to tl.weights('L', t), 'F' to tl.weights('F', t)), tl.handsAt(t), "", "")
                h.thumb.scene = s
                h.thumb.post { fitThumb(h.thumb, s) }
                h.label.text = "${k + 1}. ${e.name}" + (if (e.group != null) "\n${e.group}" else "")
                h.note.text = e.annotation ?: (if (e.entryStart && e.entryLabel != e.name) e.entryLabel else "")
                h.itemView.setOnClickListener {
                    if (playing) stopPlay()
                    var i = 0; for (j in stops.indices) if (stops[j] <= e.start + 1e-6) i = j
                    idx = i; mapOverlay.visibility = View.GONE; show()
                }
            }
        }
        mapOverlay.visibility = View.VISIBLE
    }
    private fun fitThumb(v: FloorView, s: Scene) {
        if (v.width == 0 || s.feet.isEmpty()) return
        val xs = s.feet.values.map { it.x }; val ys = s.feet.values.map { it.y }
        v.cx = (xs.min() + xs.max()) / 2; v.cy = (ys.min() + ys.max()) / 2
        v.scale = minOf(v.width / (xs.max() - xs.min() + 60.0), v.height / (ys.max() - ys.min() + 60.0))
        v.invalidate()
    }
    class MapHolder(view: View, val thumb: FloorView, val label: TextView, val note: TextView) : RecyclerView.ViewHolder(view)

    override fun onPause() { super.onPause(); if (playing) stopPlay() }
    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); metronome.release() }
}

/** Position with a constant acceleration for the first `ramp` of the time, constant speed, then the mirror image. */
class TrapezoidInterpolator(private val ramp: Float) : android.view.animation.Interpolator {
    override fun getInterpolation(t: Float): Float {
        val r = ramp.coerceIn(0.05f, 0.5f)
        val vmax = 1f / (1f - r)   // so that the area under the velocity profile is 1
        return when {
            t < r -> 0.5f * vmax / r * t * t
            t < 1f - r -> 0.5f * vmax * r + vmax * (t - r)
            else -> { val u = 1f - t; 1f - 0.5f * vmax / r * u * u }
        }
    }
}
