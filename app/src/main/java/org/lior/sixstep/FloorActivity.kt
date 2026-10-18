package org.lior.sixstep

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.max

/** The exploration dance floor: swipe through the beats, strategic view (skeleton + dashed arrows) or flow view (the
 *  transition plays), swipe down for the options bar (view toggle, play on the thump tick-tick, BPM). */
class FloorActivity : AppCompatActivity() {
    private lateinit var floor: FloorView
    private lateinit var bar: View
    private lateinit var toggle: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var bpmBar: SeekBar
    private lateinit var bpmLabel: TextView
    private lateinit var data: DanceData
    private lateinit var store: CompositionStore
    private lateinit var metronome: Metronome
    private val handler = Handler(Looper.getMainLooper())

    private var timeline: Timeline? = null
    private var stops: List<Double> = emptyList()
    private var idx = 0
    private var strategic = true
    private var beat = 0.0               // the beat being shown (flow view animates it)
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
        data = DanceData.load(this); store = CompositionStore(this); metronome = Metronome(this)
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        strategic = prefs.getBoolean("strategic", true); bpm = prefs.getInt("bpm", 120)

        val kind = intent.getStringExtra("kind") ?: "element"; val name = intent.getStringExtra("name") ?: ""
        val composed = when (kind) {
            "element" -> {
                val v = data.variants(name).getOrNull(intent.getIntExtra("variant", 0))
                if (v == null) null else Composer.compose(data, listOf(SeqItem(name, null, null, 0, true, name)), v.startState, 0.67, v.leaderFreeStart)
            }
            "sub" -> store.subs.firstOrNull { it.name == name }?.let { Composer.compose(data, store.sequence(it), it.startState, 0.67) }
            else -> store.find(name)?.let { Composer.compose(data, store.sequence(it), it.startState, 0.67) }
        }
        if (composed == null || composed.errors.isNotEmpty()) {
            floor.scene = Scene(emptyMap(), emptyMap(), emptyList(), name, "", emptyList(), composed?.errors?.joinToString("\n") ?: "nothing to show"); return
        }
        val tl = Timeline(composed, 0.67); timeline = tl
        stops = listOf(0.0) + tl.beatInstants().filter { it > 0 }
        val xs = composed.steps.map { it.x } + composed.start.values.map { it.x }; val ys = composed.steps.map { it.y } + composed.start.values.map { it.y }
        floor.post { floor.fitTo(xs, ys, 1.2, 6.0); show() }

        toggle.setOnClickListener { strategic = !strategic; prefs.edit().putBoolean("strategic", strategic).apply(); updateBar(); show() }
        playBtn.setOnClickListener { if (playing) stopPlay() else startPlay() }
        findViewById<ImageButton>(R.id.closeBar).setOnClickListener { hideBar() }
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

    private fun updateBar() {
        toggle.setImageResource(if (strategic) R.drawable.ic_strategic else R.drawable.ic_flow)
        toggle.contentDescription = getString(if (strategic) R.string.strategic_view else R.string.flow_view)
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        bpmLabel.text = "$bpm BPM"
    }
    private fun showBar() { if (!barShown) { barShown = true; bar.visibility = View.VISIBLE; bar.translationY = -bar.height.toFloat(); bar.animate().translationY(0f).setDuration(180).start() } }
    private fun hideBar() { if (barShown) { barShown = false; bar.animate().translationY(-bar.height.toFloat()).setDuration(180).withEndAction { bar.visibility = View.GONE }.start() } }

    /** the scene at a beat: in strategic view the landed feet of that instant carry their weight and the feet that
     *  moved onto this instant get a dashed arrow from where they were, both dancers at once */
    private fun sceneAt(b: Double, settled: Boolean): Scene {
        val tl = timeline!!
        val feet = tl.feetAt(b)
        val wt = if (settled) b + Timeline.RAMP else b
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
        val title = when { el == null -> ""; el.group != null -> el.name + "   (" + el.group + ")"; else -> el.name }
        return Scene(feet, weights, tl.handsAt(b), title, tl.countLabel(b), arrows)
    }

    private fun show() { val tl = timeline ?: return; beat = stops[idx]; floor.scene = sceneAt(beat, true) }

    /** move one beat instant forward or back; the flow view plays the transition with a short acceleration and deceleration */
    private fun step(dir: Int) {
        val tl = timeline ?: return
        val next = idx + dir
        if (next < 0 || next >= stops.size) return
        anim?.cancel()
        val from = stops[idx]; val to = stops[next]; idx = next
        if (strategic) { show(); return }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 450
        a.interpolator = TrapezoidInterpolator(1f / 6f / 0.45f)
        a.addUpdateListener { v -> val u = v.animatedValue as Float; beat = from + (to - from) * u; floor.scene = sceneAt(beat, u >= 0.999f) }
        a.start(); anim = a
    }

    /** the beat moves along the thump tick-tick at the slider's tempo; strategic view jumps from instant to instant */
    private fun startPlay() {
        val tl = timeline ?: return
        anim?.cancel()
        val c = TempoClock(bpm.toDouble(), stops[idx], SystemClock.uptimeMillis()); clock = c
        metronome.clock = c; metronome.swing = 0.67; metronome.enabled = true; metronome.resync(); metronome.start()
        playing = true; updateBar()
        handler.post(object : Runnable {
            override fun run() {
                if (!playing) return
                var b = c.beatNow()
                if (b >= tl.length) { b -= tl.length; c.reanchor(b, SystemClock.uptimeMillis()); metronome.resync() }
                var i = 0; for (k in stops.indices) if (stops[k] <= b + 1e-6) i = k
                idx = i
                beat = if (strategic) stops[i] else b
                floor.scene = sceneAt(beat, strategic || b - stops[i] > Timeline.RAMP)
                handler.postDelayed(this, 16)
            }
        })
    }
    private fun stopPlay() { playing = false; metronome.stop(); clock = null; updateBar(); show() }

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
