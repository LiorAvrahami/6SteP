package org.lior.sixstep

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** The one dance floor (the user's layout draft, 2026-10-28): every element, building block and composition is danced
 *  here, the daily exercise included. Opened with the extras kind, name, variant (see Piece), stop (the step to show
 *  first) and autoplay.
 *
 *  Swipe left or right: the next or previous step, in flow mode (the feet travel there) or schema mode (they are there,
 *  dashed lines show where each foot came from; both dancers at once). Swipe down: the top and bottom bars; a tap on
 *  the floor closes them. Double tap or the play button: play (the bars close). A tap while playing pauses; a swipe
 *  while playing jumps to the next part, or back to the start of this one (the previous one in its first 2 s).
 *  Top bar: play/pause, the tempo, the song, mute (music, drums and voice), flow/schema, edit. Bottom bar: every step of
 *  the piece, the current one framed, the annotations pointing at where they start; a tap on a step goes there.
 *  Compositions play once to the end; elements and building blocks loop (chained so that the last beat flows into the
 *  first when they close on themselves, otherwise from the start position again after two beats). */
class DanceFloorActivity : AppCompatActivity() {
    companion object {
        const val BANNER_BEATS = 3.0          // an annotation stays on the screen this long
        const val BACK_REPLAY_SECONDS = 2.0   // swipe right in the first 2 s of a part goes to the previous one (as in Workout Instructinator)
        const val LOOP_REST = 2.0             // beats of the start position before a piece that does not close is danced again
        const val CHAIN_BEATS = 1200.0        // a piece that closes is chained to about this long for play
    }

    private lateinit var floor: FloorView
    private lateinit var topBar: View
    private lateinit var strip: StepStrip
    private lateinit var playBtn: ImageButton
    private lateinit var rateBtn: TextView
    private lateinit var songBtn: TextView
    private lateinit var muteBtn: ImageButton
    private lateinit var modeBtn: ImageButton
    private lateinit var pb: Playback

    private var piece: Piece? = null
    private var tl: Timeline? = null              // the piece danced once: what the swipes and the strip go through
    private var playTl: Timeline? = null          // what plays: the piece itself, or chained into a long loop
    private var loopBeats = 0.0                   // one loop in playTl (the piece and the triple steps that close it); 0: not chained
    private var period = 0.0                      // a piece that does not close: danced again every `period` beats
    private var playStarts = DoubleArray(0)       // the element starts of playTl
    private var stops: List<Double> = emptyList()
    private var parts: List<Double> = emptyList() // where the swipes jump while playing, in beats of the piece
    private var idx = 0
    private var schema = true
    private var playing = false
    private var hold = Double.NEGATIVE_INFINITY   // the dancers wait here until the clock reaches it (a count-in)
    private var pausedClock = Double.NaN          // the clock beat of the last pause: only which loop we are in
    private var anim: ValueAnimator? = null
    private var barsShown = false
    private var barsFrac = 0f
    private var barsAnim: ValueAnimator? = null
    private var lastAnnounced = -1
    private var silentNext = false                // the next announcement is shown, not read aloud (after a jump)
    private var banner: String? = null
    private var bannerUntil = Double.NEGATIVE_INFINITY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dance_floor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        floor = findViewById(R.id.floor); topBar = findViewById(R.id.topBar); strip = findViewById(R.id.strip)
        playBtn = findViewById(R.id.play); rateBtn = findViewById(R.id.rate); songBtn = findViewById(R.id.song)
        muteBtn = findViewById(R.id.mute); modeBtn = findViewById(R.id.mode)
        val edit = findViewById<ImageButton>(R.id.edit)
        topBar.setBackgroundColor(Ui.BAR)
        for (v in listOf<View>(playBtn, rateBtn, muteBtn, modeBtn, edit)) v.background = Ui.box(this, Color.TRANSPARENT, 2.5f, 11f)
        songBtn.background = Ui.box(this, Ui.BAR, 1f, 2f, Color.rgb(128, 128, 128))
        strip.barColor = Ui.BAR; strip.calloutBandDp = 32f; strip.cellWidthDp = 64f; strip.gapDp = 8f

        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        schema = prefs.getBoolean("strategic", true)
        pb = Playback(this)
        pb.onChange = { updateButtons() }
        pb.onFocusLoss = { pause() }
        pb.onMessage = { m -> Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
        playBtn.setOnClickListener { if (playing) pause() else play() }
        rateBtn.setOnClickListener { tempoMenu() }
        songBtn.setOnClickListener { songMenu() }
        muteBtn.setOnClickListener { pb.setMuted(!pb.muted); updateButtons() }
        modeBtn.setOnClickListener {
            schema = !schema; prefs.edit().putBoolean("strategic", schema).apply(); updateButtons()
            if (!playing) { anim?.cancel(); show() }
        }
        edit.setOnClickListener { MaterialAlertDialogBuilder(this).setMessage(R.string.editor_not_ready).setPositiveButton(android.R.string.ok, null).show() }
        TooltipCompat.setTooltipText(edit, getString(R.string.edit))
        TooltipCompat.setTooltipText(rateBtn, getString(R.string.tempo))
        TooltipCompat.setTooltipText(songBtn, getString(R.string.song))
        updateButtons()

        val name = intent.getStringExtra("name") ?: ""
        val data = DanceData.load(this); val store = CompositionStore(this)
        val p = Piece.find(data, store, intent.getStringExtra("kind") ?: Piece.ELEMENT, name, intent.getIntExtra("variant", 0))
        val swing = pb.currentTrack()?.let { BeatGrid.loadFor(this, it).swing } ?: Piece.SWING
        val composed = p?.compose(data, swing)
        if (p == null || composed == null || composed.errors.isNotEmpty()) {
            floor.scene = Scene(emptyMap(), emptyMap(), name, banner = composed?.errors?.joinToString("\n") ?: getString(R.string.nothing_to_show))
            return
        }
        piece = p
        val t = Timeline(composed, swing); tl = t
        stops = stopsOf(t)
        val ptl: Timeline = if (p.isComposition) t else chainedLoop(p, data, swing) ?: t.also { period = t.length + LOOP_REST }
        playTl = ptl
        playStarts = ptl.composed.elements.map { it.start }.toDoubleArray()
        parts = (if (p.isComposition) composed.elements.filter { it.entryStart || it.annotation != null } else composed.elements)
            .map { it.start }.ifEmpty { composed.elements.map { it.start } }
        pb.boundaryAfter = { b -> boundaryAfter(b) }
        strip.cells = cellsOf(t, stops, getString(R.string.start))
        strip.callouts = callouts(p, t)
        strip.onTap = { k -> tapStop(k) }
        if (p.isComposition) pb.prefetch(composed.elements.mapNotNull { announcement(it) }.distinct())
        idx = intent.getIntExtra("stop", 0).coerceIn(0, stops.size - 1)
        val autoplay = intent.getBooleanExtra("autoplay", false)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (barsShown) hideBars() else if (playing) pause(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { if (playing) pause() else play(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dy) > 80 && abs(dy) > abs(dx) * 1.2f) { if (dy > 0) showBars() else hideBars(); return true }
                if (abs(dx) > 60 && abs(dx) > abs(dy) * 1.2f) { if (playing) skip(dx < 0) else step(if (dx < 0) 1 else -1); return true }
                return false
            }
        })
        floor.setOnTouchListener { v, ev -> gestures.onTouchEvent(ev); if (ev.action == MotionEvent.ACTION_UP) v.performClick(); true }
        floor.post {
            applyBars(0f)
            show()
            if (autoplay) play() else showBars()
        }
    }

    // ------------------------------------------------------------------ beats: the clock's, the playing timeline's, the piece's

    /** the length of one loop in clock beats; 0 for a composition, which plays once */
    private fun cycle(): Double = if (loopBeats > 0) loopBeats else period

    /** the beat of playTl shown at clock beat b */
    private fun playBeat(b: Double): Double {
        val ptl = playTl ?: return b
        return when {
            b < 0 -> b
            loopBeats > 0 -> b % ptl.length
            period > 0 -> { val m = b % period; if (m < ptl.length) m else m - period }
            else -> b
        }
    }

    /** the beat of the piece (danced once) at clock beat b; NaN in the triple steps that close a loop */
    private fun pieceBeat(b: Double): Double {
        val t = tl ?: return b
        if (b < 0 || cycle() <= 0) return b
        if (loopBeats > 0) { val x = b % loopBeats; return if (x < t.length) x else Double.NaN }
        return playBeat(b)
    }

    /** the clock beat of beat p of the piece, in the loop that clock beat `near` is in (the first when there is none) */
    private fun clockBeat(p: Double, near: Double): Double {
        val c = cycle()
        if (c <= 0 || near.isNaN() || near < 0) return p
        return floor(near / c) * c + p
    }

    /** where a new track may start: the first element start at or after clock beat b; null after a composition's end */
    private fun boundaryAfter(b: Double): Double? {
        val ptl = playTl ?: return null
        val wrap = if (loopBeats > 0) ptl.length else period
        if (wrap <= 0) return playStarts.firstOrNull { it >= b }
        val base = if (b < 0) 0.0 else floor(b / wrap) * wrap
        for (k in 0..1) for (s in playStarts) { val x = base + k * wrap + s; if (x >= b) return x }
        return null
    }

    /** the starts of the parts in clock beats, around clock beat b */
    private fun partsNear(b: Double): List<Double> {
        val c = cycle()
        if (c <= 0) return parts
        val base = if (b < 0) 0.0 else floor(b / c) * c
        return listOf(base - c, base, base + c).filter { it >= 0 }.flatMap { s -> parts.map { s + it } }
    }

    /** the piece padded with triple steps until it closes, then chained to about CHAIN_BEATS: the loop that plays, so
     *  that the last beat flows into the first. Null when the piece does not close on itself. */
    private fun chainedLoop(p: Piece, data: DanceData, swing: Double): Timeline? {
        val one = p.compose(data, swing, padToLoop = true)
        if (one.errors.isNotEmpty() || !one.closes) return null
        val names = one.elements.map { SeqItem(it.name, it.group, null, it.entryIndex, it.entryStart, it.entryLabel, it.endState) }   // the versions the first loop chose
        val n = (CHAIN_BEATS / one.length).toInt().coerceIn(2, 600)
        val chain = ArrayList<SeqItem>(); repeat(n) { chain.addAll(names) }
        val c = Composer.compose(data, chain, p.startState, swing, p.free)
        if (c.errors.isNotEmpty()) return null
        val m = one.elements.size
        val elements = c.elements.mapIndexed { k, e -> e.copy(filler = one.elements[k % m].filler) }
        val steps = c.steps.map { s -> s.copy(filler = elements[s.element].filler) }
        loopBeats = one.length
        return Timeline(Composed(steps, elements, c.start, c.startHands, c.startState, c.startFree, c.endState, c.endFree, c.length, c.errors), swing)
    }

    // ------------------------------------------------------------------ what the floor shows

    private fun weightsAt(t: Timeline, b: Double) = mapOf('L' to t.weights('L', b), 'F' to t.weights('F', b))
    private fun fillerColor(el: CElement?): Int? = if (el?.filler == true) getColor(R.color.filler) else null

    private fun titleOf(el: CElement?, b: Double, counting: Boolean): String = when {
        b < 0 -> getString(if (counting) R.string.ready else R.string.start_position)
        el == null -> ""
        el.filler -> el.name + getString(R.string.filler_suffix)
        el.group != null -> el.name + "   (" + el.group + ")"
        else -> el.name
    }

    /** a stop: the feet where they landed, the weight settled on them; in schema mode a dashed arrow for every foot that
     *  landed here, from where it was (both dancers at once) */
    private fun stopScene(t: Timeline, b: Double, banner: String?): Scene {
        val arrows = ArrayList<Arrow>()
        if (schema) for (d in listOf('L', 'F')) for (f in listOf('L', 'R')) {
            val track = t.dancers[d]!!.tracks[f]!!
            val (cur, _) = track.around(b)
            if (abs(cur.tLand - b) < 1e-6) {
                val i = track.landings.indexOf(cur)
                if (i > 0) { val prev = track.landings[i - 1].p; if (abs(prev.x - cur.p.x) > 0.5 || abs(prev.y - cur.p.y) > 0.5) arrows.add(Arrow(d, prev, cur.p)) }
            }
        }
        val el = t.elementAt(b)
        return Scene(t.feetAt(b), weightsAt(t, t.settledAfter(b)), titleOf(el, b, false), if (b < 0) "" else t.countLabel(b), arrows, banner, fillerColor(el))
    }

    /** any instant of the dance as it moves */
    private fun flowScene(t: Timeline, b: Double, banner: String?, counting: Boolean): Scene {
        val el = t.elementAt(b)
        return Scene(t.feetAt(b), weightsAt(t, b), titleOf(el, b, counting), if (b < 0) "" else t.countLabel(b), emptyList(), banner, fillerColor(el))
    }

    /** stop k as a beat of the clock: in the loop of the last pause (the first loop before any) */
    private fun stopBeat(k: Int): Double = clockBeat(stops[k], pausedClock)

    /** the stop we are on, drawn from the timeline that plays, in the loop of the last pause, so that play starts from
     *  exactly this picture */
    private fun show(banner: String? = null) {
        val ptl = playTl ?: return
        floor.scene = stopScene(ptl, playBeat(stopBeat(idx)), banner)
        strip.current = idx
        if (barsShown) strip.scrollToCell(idx, true)
    }

    /** "side to side x10" -> "side to side, 10 times" */
    private fun spoken(label: String): String {
        val m = Regex("^(.*?)\\s*x(\\d+)$").matchEntire(label)
        return if (m != null) "${m.groupValues[1]}, ${m.groupValues[2]} times" else label
    }
    /** what is said, and shown, as an element of a composition starts: its "say:" text, and its entry's name */
    private fun phrases(e: CElement): List<String> = listOfNotNull(e.annotation, if (e.entryStart) spoken(e.entryLabel) else null)
    private fun announcement(e: CElement): String? = phrases(e).joinToString(". ").ifEmpty { null }

    /** the bottom bar's annotations: a composition's "say:" texts and entry names; a building block's element names */
    private fun callouts(p: Piece, t: Timeline): List<Pair<Int, String>> = t.composed.elements.mapNotNull { e ->
        val text = when (p.kind) {
            Piece.COMPOSITION -> listOfNotNull(e.annotation, if (e.entryStart) e.entryLabel else null).joinToString(" · ")
            Piece.BLOCK -> e.name
            else -> ""
        }
        if (text.isEmpty()) null else Pair(stopIndex(stops, e.start), text)
    }

    // ------------------------------------------------------------------ stepping by hand

    /** one stop forward or back; past the last comes the first again, and before the first the last (user 2026-10-19).
     *  Flow mode plays the transition from the settled state of one stop to the settled state of the next, at twice the
     *  beat length of the tempo, with a constant acceleration and deceleration of about a sixth of a second at each end;
     *  going round the end is a cut. */
    private fun step(dir: Int) {
        val ptl = playTl ?: return
        if (stops.size < 2) return
        var next = idx + dir
        val wrapped = next < 0 || next >= stops.size
        if (wrapped) next = if (next < 0) stops.size - 1 else 0
        anim?.cancel()
        val from = playBeat(stopBeat(idx)); val to = playBeat(stopBeat(next)); idx = next
        if (schema || wrapped) { show(); return }
        strip.current = idx
        if (barsShown) strip.scrollToCell(idx, true)
        val tFrom = ptl.settledAfter(from); val tTo = ptl.settledAfter(to)
        val ms = (abs(to - from) * 60.0 / pb.bpm() * 2.0 * 1000.0).coerceIn(250.0, 4000.0)
        val ramp = (1.0 / 6.0 * 1000.0 / ms).coerceAtMost(0.45)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ms.toLong()
        a.interpolator = TrapezoidInterpolator(ramp.toFloat())
        a.addUpdateListener { v -> val u = (v.animatedValue as Float).toDouble(); floor.scene = flowScene(ptl, tFrom + (tTo - tFrom) * u, null, false) }
        a.start(); anim = a
    }

    private fun tapStop(k: Int) {
        if (playing) startAt(k, pb.beatNow(), quiet = true)
        else { anim?.cancel(); idx = k; show() }
    }

    // ------------------------------------------------------------------ playing: everything made afresh from a step

    private fun play() {
        if (playing || tl == null) return
        if (cycle() <= 0 && idx >= stops.size - 1) idx = 0   // a composition at its end starts again
        startAt(idx, pausedClock, quiet = false)
    }

    /** play from stop k, in the loop of clock beat `near`, with nothing kept from before (user 2026-10-28): the music
     *  from that step's place in the plan, the dance from exactly the picture of the step, the weight settled on what
     *  landed there. From the start position (unmuted), a count-in of four beats of music first, the dancers waiting.
     *  quiet: a jump while playing, whose part is shown but not read aloud (so that the music does not dip). */
    private fun startAt(k: Int, near: Double, quiet: Boolean) {
        val ptl = playTl ?: return
        anim?.cancel()
        val wasPlaying = playing
        val b = clockBeat(stops[k], near)
        val countIn = k == 0 && !pb.muted
        val from = if (countIn) b - (Playback.COUNT_IN - Piece.LEAD_IN) else b + (ptl.settledAfter(playBeat(b)) - playBeat(b))
        pb.play(from)
        if (!pb.playing) { standAt(from); return }   // the sound is busy (a call)
        playing = true
        hold = if (countIn) b else Double.NEGATIVE_INFINITY
        lastAnnounced = ptl.elementIndexAt(playBeat(b) - 0.01)   // an element starting on this step is announced, one under way is not
        silentNext = quiet
        banner = null
        hideBars(); updateButtons()
        if (!wasPlaying) floor.postOnAnimation(frame)
    }

    /** pause: everything that played is let go (players, clock, drums, voice); what stays is the step we are on */
    private fun pause() {
        if (!playing) return
        val b = pb.beatNow()
        pb.stop()
        standAt(if (b < hold) hold else b)
    }

    /** not playing, standing on the stop at clock beat b (in the triple steps that close a loop: its start) */
    private fun standAt(b: Double) {
        playing = false
        pausedClock = b
        val p = pieceBeat(b)
        idx = if (p.isNaN()) 0 else stopIndex(stops, p)
        banner = null
        show(); updateButtons()
    }

    /** a composition danced to its end */
    private fun endOfPiece() {
        playing = false; pb.stop(); pausedClock = Double.NaN
        idx = stops.size - 1
        show(getString(R.string.done)); updateButtons(); showBars()
    }

    /** while playing: to the next part, or back to the start of this one (the previous one in its first seconds) */
    private fun skip(forward: Boolean) {
        val b = pb.beatNow()
        val near = partsNear(b)
        val target = if (forward) (near.firstOrNull { it > b + 0.05 } ?: return) else {
            val cur = near.lastOrNull { it <= b + 0.05 } ?: 0.0
            if (b - cur > BACK_REPLAY_SECONDS * pb.bpm() / 60.0) cur else (near.lastOrNull { it < cur - 0.05 } ?: 0.0)
        }
        pb.play(target)   // from scratch, as any start
        if (!pb.playing) { standAt(target); return }
        hold = Double.NEGATIVE_INFINITY; banner = null
        lastAnnounced = playTl?.elementIndexAt(playBeat(target) - 0.01) ?: -1
        silentNext = true
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!playing) return
            val ptl = playTl ?: return
            val b = pb.beatNow()
            if (cycle() <= 0 && b >= ptl.length) { endOfPiece(); return }
            if (b >= hold) hold = Double.NEGATIVE_INFINITY
            drawPlaying(if (b < hold) hold else b)
            floor.postOnAnimation(this)
        }
    }

    private fun drawPlaying(b: Double) {
        val ptl = playTl ?: return
        val x = playBeat(b)
        val ei = ptl.elementIndexAt(x)
        if (piece?.isComposition == true && ei >= 0 && ei != lastAnnounced) {
            lastAnnounced = ei
            val e = ptl.composed.elements[ei]
            val shown = phrases(e)
            if (shown.isNotEmpty()) { banner = shown.joinToString("\n"); bannerUntil = b + BANNER_BEATS }
            if (!silentNext) announcement(e)?.let { pb.speak(it) }
            silentNext = false
        }
        if (b > bannerUntil) banner = null
        floor.scene = flowScene(ptl, x, banner, true)
        val p = pieceBeat(b)
        val k = if (p.isNaN()) -1 else stopIndex(stops, p)
        strip.current = k
        if (barsShown && k >= 0) strip.follow(k)
    }

    // ------------------------------------------------------------------ the bars

    private fun showBars() { if (barsShown) return; barsShown = true; strip.scrollToCell(maxOf(strip.current, 0), false); moveBars() }
    private fun hideBars() { if (!barsShown) return; barsShown = false; moveBars() }

    private fun moveBars() {
        barsAnim?.cancel()
        val a = ValueAnimator.ofFloat(barsFrac, if (barsShown) 1f else 0f)
        a.duration = 200
        a.addUpdateListener { applyBars(it.animatedValue as Float) }
        a.start(); barsAnim = a
    }

    /** the bars slid in by `f` (0 hidden, 1 shown); the camera keeps the dancers between them */
    private fun applyBars(f: Float) {
        barsFrac = f
        val th = topBar.height.toFloat(); val bh = strip.height.toFloat()
        topBar.translationY = -th * (1 - f); strip.translationY = bh * (1 - f)
        floor.insetTop = th * f; floor.insetBottom = bh * f
    }

    private fun updateButtons() {
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        muteBtn.setImageResource(if (pb.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteBtn.contentDescription = getString(if (pb.muted) R.string.unmute else R.string.mute)
        modeBtn.setImageResource(if (schema) R.drawable.ic_schema else R.drawable.ic_flow)
        modeBtn.contentDescription = getString(if (schema) R.string.schema_view else R.string.flow_view)
        for (v in listOf(playBtn, muteBtn, modeBtn)) TooltipCompat.setTooltipText(v, v.contentDescription)
        val bpm = pb.bpm().roundToInt().toString()
        val s = SpannableString(bpm + "\nBPM")
        s.setSpan(RelativeSizeSpan(0.6f), bpm.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rateBtn.text = s
        val title = pb.currentTrack()?.title ?: ""
        songBtn.text = if (pb.song == Playback.SHUFFLE) getString(R.string.shuffle_now, title) else title
    }

    /** the tempo: the song's tempo variants, from 40 BPM slower to 20 BPM faster (the same offset for every song) */
    private fun tempoMenu() {
        val t = pb.currentTrack() ?: return
        val m = PopupMenu(this, rateBtn)
        for ((i, off) in Playback.BPM_OFFSETS.withIndex()) {
            val bpm = t.variantFor(off).bpm.roundToInt()
            val item = m.menu.add(0, i, i, if (off == 0) getString(R.string.bpm_original, bpm) else getString(R.string.bpm, bpm))
            item.isCheckable = true
            item.isChecked = off == pb.bpmOffset
        }
        m.menu.setGroupCheckable(0, true, true)
        m.setOnMenuItemClickListener { item -> pb.setBpmOffset(Playback.BPM_OFFSETS[item.itemId]); updateButtons(); true }
        m.show()
    }

    /** the song: all of them shuffled, or one (the others follow it, shuffled) */
    private fun songMenu() {
        val m = PopupMenu(this, songBtn)
        m.menu.add(0, 0, 0, getString(R.string.shuffle_all)).isCheckable = true
        for ((i, t) in pb.tracks.withIndex()) {
            val hint = t.difficulty.substringBefore(":").substringBefore(",").trim()
            val label = getString(R.string.bpm, t.bpm.roundToInt()).let { b -> if (hint.isEmpty()) "${t.title} · $b" else "${t.title} · $b · $hint" }
            m.menu.add(0, i + 1, i + 1, label).isCheckable = true
        }
        m.menu.setGroupCheckable(0, true, true)
        val sel = if (pb.song == Playback.SHUFFLE) 0 else pb.tracks.indexOfFirst { it.key == pb.song } + 1
        m.menu.findItem(sel)?.isChecked = true
        m.setOnMenuItemClickListener { item ->
            pb.chooseSong(if (item.itemId == 0) Playback.SHUFFLE else pb.tracks[item.itemId - 1].key)
            updateButtons(); true
        }
        m.show()
    }

    override fun onResume() { super.onResume(); pb.readSettings() }
    override fun onPause() { super.onPause(); if (playing) pause() }
    override fun onDestroy() {
        super.onDestroy()
        anim?.cancel(); barsAnim?.cancel(); playing = false
        pb.release()
    }
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
