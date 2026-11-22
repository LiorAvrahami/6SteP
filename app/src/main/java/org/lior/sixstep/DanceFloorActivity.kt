package org.lior.sixstep

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
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
import kotlin.math.abs

/** The one dance floor (the user's layout draft, 2026-10-28), as the Android app shows it: every element, building block
 *  and composition is danced here, the daily exercise included. Opened with the extras kind, name, variant (see Piece)
 *  and autoplay, always on the first step.
 *
 *  What the floor does is the shared DanceFloor's (shared/.../DanceFloor.kt; since 2026-11-21 the very code the web
 *  page runs): stepping by swipes, play and pause, the annotations, the swipes while it plays with their lead-in of
 *  rock triple triples, when the bars show. This activity is its screen (FloorHost): it passes on the gestures and the
 *  buttons, and shows what the floor tells it to: the picture (FloorView), the bottom bar (StepStrip), the bars sliding
 *  in and out, the buttons' faces, the menus. The sound is Playback, which the floor drives as its FloorPlayer.
 *
 *  A tap on the floor plays or pauses (there is no double tap); a swipe left or right is the next or previous step, or
 *  part while it plays; a swipe down or up brings the bars, and the next one sends them away.
 *  Top bar: play/pause, the tempo, the song, mute (music, drums and voice), back. */
class DanceFloorActivity : AppCompatActivity(), FloorHost {
    private lateinit var floorView: FloorView
    private lateinit var topBar: View
    private lateinit var stripView: StepStrip
    private lateinit var hintView: HintView
    private lateinit var playBtn: ImageButton
    private lateinit var rateBtn: TextView
    private lateinit var songBtn: TextView
    private lateinit var muteBtn: ImageButton
    private lateinit var pb: Playback
    private lateinit var dance: DanceFloor
    private var barsWanted = false
    private var barsFrac = 0f
    private var barsAnim: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dance_floor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        floorView = findViewById(R.id.floor); topBar = findViewById(R.id.topBar); stripView = findViewById(R.id.strip)
        hintView = findViewById(R.id.hints)
        // the interface makes a little of the hint's move with it: the top bar peeks in as its finger swipes down
        // (HintPainter.nudge)
        hintView.onNudge = { h, n ->
            val dp = resources.displayMetrics.density
            if (barsFrac == 0f && barsAnim?.isRunning != true)
                topBar.translationY = -topBar.height + if (h == Hint.SWIPE_DOWN) (maxOf(0.0, n) * HintPainter.BAR_PEEK * dp).toFloat() else 0f
        }
        playBtn = findViewById(R.id.play); rateBtn = findViewById(R.id.rate); songBtn = findViewById(R.id.song)
        muteBtn = findViewById(R.id.mute)
        val back = findViewById<ImageButton>(R.id.back)
        topBar.setBackgroundColor(Ui.BAR)
        for (v in listOf<View>(playBtn, rateBtn, muteBtn, back)) v.background = Ui.box(this, Color.TRANSPARENT, 2.5f, 11f)
        songBtn.background = Ui.box(this, Ui.BAR, 1f, 2f, Color.rgb(128, 128, 128))
        stripView.barColor = Ui.BAR; stripView.calloutBandDp = 32f; stripView.cellWidthDp = 64f; stripView.gapDp = 8f

        pb = Playback(this)
        dance = DanceFloor(DanceData.load(this), CompositionStore(this), intent.getStringExtra("kind") ?: Piece.ELEMENT,
            intent.getStringExtra("name") ?: "", intent.getIntExtra("variant", 0), intent.getBooleanExtra("autoplay", false), pb, this)

        playBtn.setOnClickListener { dance.playPause() }
        rateBtn.setOnClickListener { menu(rateBtn, dance.tempoChoices()) { key -> dance.chooseTempo(key) } }
        songBtn.setOnClickListener { menu(songBtn, dance.songChoices()) { key -> dance.chooseSong(key) } }
        muteBtn.setOnClickListener { dance.toggleMute() }
        back.setOnClickListener { finish() }   // back to where the floor was opened from (user 2026-10-28)
        TooltipCompat.setTooltipText(back, getString(R.string.back))
        TooltipCompat.setTooltipText(rateBtn, getString(R.string.tempo))
        TooltipCompat.setTooltipText(songBtn, getString(R.string.song))
        stripView.onTap = { k -> dance.tapStop(k) }
        updateButtons()

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            // a tap, the moment the finger lifts: play, or pause (user 2026-11-21: a single tap for both)
            override fun onSingleTapUp(e: MotionEvent): Boolean { dance.tap(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dy) > 80 && abs(dy) > abs(dx) * 1.2f) { if (dy > 0) dance.swipeDown() else dance.swipeUp(); return true }
                if (abs(dx) > 60 && abs(dx) > abs(dy) * 1.2f) { dance.swipe(dx < 0); return true }
                return false
            }
        })
        // every tap is a tap of its own: no waiting for a second one, and a finger that rests a while still taps
        gestures.setOnDoubleTapListener(null)
        gestures.setIsLongpressEnabled(false)
        floorView.setOnTouchListener { v, ev -> gestures.onTouchEvent(ev); if (ev.action == MotionEvent.ACTION_UP) v.performClick(); true }
        // once the views have their sizes: the bars out of sight, then the first picture (playing at once for the daily
        // exercise, otherwise standing on its first step; the bars stay away until a swipe down or up brings them)
        floorView.post {
            applyBars(0f)
            dance.open()
        }
    }

    // ------------------------------------------------------------------ what the floor asks of its screen (FloorHost)

    override fun nowMs(): Double = SystemClock.uptimeMillis().toDouble()
    override fun nextFrame(callback: () -> Unit) { floorView.postOnAnimation { callback() } }
    override fun show(scene: Scene) { floorView.scene = scene }
    override fun strip(cells: List<StepCell>, callouts: List<Pair<Int, String>>) { stripView.cells = cells; stripView.callouts = callouts }
    override fun stripCurrent(k: Int) { stripView.current = k }
    override fun stripScrollTo(k: Int, animate: Boolean) { stripView.scrollToCell(k, animate) }
    override fun stripFollow(k: Int) { stripView.follow(k) }
    override fun bars(shown: Boolean) { barsWanted = shown; moveBars() }
    override fun buttons() { updateButtons() }
    override fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    /** the settings page's switch for the lead-in after a swipe (on unless it was switched off) */
    override val leadIn: Boolean get() = getSharedPreferences("6step", MODE_PRIVATE).getBoolean("lead_in", true)

    // the hints (the shared Hint): which one to show, and what this user has already done once. The flags are kept in
    // the app's preferences, so they stay until the app's data is cleared.
    override fun hint(hint: Hint?, first: Double) { hintView.show(hint, first) }
    override fun knows(what: String): Boolean = getSharedPreferences("6step", MODE_PRIVATE).getBoolean(Hint.flagOf(what), false)
    override fun learned(what: String) { getSharedPreferences("6step", MODE_PRIVATE).edit().putBoolean(Hint.flagOf(what), true).apply() }

    // ------------------------------------------------------------------ the bars

    private fun moveBars() {
        barsAnim?.cancel()
        val a = ValueAnimator.ofFloat(barsFrac, if (barsWanted) 1f else 0f)
        a.duration = 200
        a.addUpdateListener { applyBars(it.animatedValue as Float) }
        a.start(); barsAnim = a
    }

    /** the bars slid in by `f` (0 hidden, 1 shown); the camera keeps the dancers between them */
    private fun applyBars(f: Float) {
        barsFrac = f
        val th = topBar.height.toFloat(); val bh = stripView.height.toFloat()
        topBar.translationY = -th * (1 - f); stripView.translationY = bh * (1 - f)
        floorView.insetTop = th * f; floorView.insetBottom = bh * f
        hintView.bottomBar = bh * f
    }

    // ------------------------------------------------------------------ the buttons and their menus

    private fun updateButtons() {
        val playing = dance.playing
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        muteBtn.setImageResource(if (dance.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteBtn.contentDescription = getString(if (dance.muted) R.string.unmute else R.string.mute)
        for (v in listOf(playBtn, muteBtn)) TooltipCompat.setTooltipText(v, v.contentDescription)
        val bpm = dance.tempoLabel()
        val s = SpannableString(bpm + "\nBPM")
        s.setSpan(RelativeSizeSpan(0.6f), bpm.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rateBtn.text = s
        songBtn.text = dance.songLabel()
    }

    /** a menu under one of the bar's buttons: the floor's choices (the tempos of the song; "Play all", the songs,
     *  "Shuffle play"), the one in force marked; `pick` gets the key of the line that was chosen */
    private fun menu(anchor: View, choices: List<DanceFloor.Choice>, pick: (String) -> Unit) {
        if (choices.isEmpty()) return
        val m = PopupMenu(this, anchor)
        for ((i, c) in choices.withIndex()) m.menu.add(if (c.checkable) 0 else 1, i, i, c.label)
        m.menu.setGroupCheckable(0, true, true)
        val on = choices.indexOfFirst { it.checked && it.checkable }
        if (on >= 0) m.menu.findItem(on)?.isChecked = true
        m.setOnMenuItemClickListener { item -> pick(choices[item.itemId].key); true }
        m.show()
    }

    /** every touch, anywhere on the floor or its bars, before it does anything else: a hint on the screen goes, and
     *  waits for the user to leave the screen alone again (DanceFloor.touched) */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::dance.isInitialized) dance.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() { super.onResume(); pb.readSettings() }
    override fun onPause() { super.onPause(); dance.hidden() }
    override fun onDestroy() {
        super.onDestroy()
        barsAnim?.cancel()
        dance.release()
    }
}
