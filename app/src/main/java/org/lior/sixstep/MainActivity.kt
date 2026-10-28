package org.lior.sixstep

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The launcher (the user's layout draft, 2026-10-28): the daily exercise with how long it takes, explore, and in the
 *  left column the title, a little floor dancing the "quick recap" at 1.5 times the exercise's tempo, and the heart,
 *  settings and help buttons. Help puts two explanations with arrows to their buttons where the little floor is. */
class MainActivity : AppCompatActivity() {
    companion object { const val SNIPPET_SPEED = 1.5 }

    private lateinit var snippet: FloorView
    private lateinit var helpPanel: View
    private lateinit var arrows: HelpArrows
    private lateinit var time: TextView
    private lateinit var recapLabel: View
    private var dailyName: String? = null
    private var dailyBeats = 0.0
    private var tl: Timeline? = null              // the quick recap
    private var bpm = 120.0
    private var t0 = 0L
    private var running = false
    private var helpOn = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t = tl
            if (t != null && t.length > 0) {
                val span = t.length + Piece.LEAD_IN
                val b = ((SystemClock.uptimeMillis() - t0) / 60000.0 * bpm * SNIPPET_SPEED) % span - Piece.LEAD_IN
                snippet.scene = Scene(t.feetAt(b), mapOf('L' to t.weights('L', b), 'F' to t.weights('F', b)))
            }
            snippet.postOnAnimation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.bars(this, Ui.LAUNCHER_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.LAUNCHER_BG)
        findViewById<View>(R.id.logo).background = Ui.box(this, Ui.TITLE, ripple = false)
        findViewById<View>(R.id.snippetBox).background = Ui.box(this, Color.WHITE, ripple = false)
        val daily = findViewById<View>(R.id.daily); daily.background = Ui.box(this, Ui.DAILY)
        val explore = findViewById<View>(R.id.explore); explore.background = Ui.box(this, Ui.EXPLORE)
        val heart = findViewById<ImageButton>(R.id.heart); heart.background = Ui.box(this, Ui.HEART)
        val gear = findViewById<ImageButton>(R.id.gear); gear.background = Ui.box(this, Ui.GEAR)
        val help = findViewById<ImageButton>(R.id.help); help.background = Ui.box(this, Ui.HELP)
        val helpDaily = findViewById<View>(R.id.helpDaily); helpDaily.setBackgroundColor(Ui.DAILY)
        val helpExplore = findViewById<View>(R.id.helpExplore); helpExplore.setBackgroundColor(Ui.EXPLORE)
        for (b in listOf(heart, gear, help)) TooltipCompat.setTooltipText(b, b.contentDescription)
        snippet = findViewById(R.id.snippet); snippet.showLabels = false; recapLabel = findViewById(R.id.recapLabel)
        helpPanel = findViewById(R.id.helpPanel); arrows = findViewById(R.id.arrows); time = findViewById(R.id.dailyTime)
        arrows.pairs = listOf(Pair(helpDaily, findViewById<View>(R.id.dailyIcon)), Pair(helpExplore, findViewById<View>(R.id.exploreIcon)))

        daily.setOnClickListener {
            val n = dailyName ?: return@setOnClickListener
            startActivity(Intent(this, DanceFloorActivity::class.java)
                .putExtra("kind", Piece.COMPOSITION).putExtra("name", n).putExtra("autoplay", true))
        }
        explore.setOnClickListener { startActivity(Intent(this, ExploreActivity::class.java)) }
        heart.setOnClickListener { startActivity(Intent(this, HeartActivity::class.java)) }
        gear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        help.setOnClickListener { setHelp(!helpOn) }

        // composed once: the compositions are not edited on the phone any more
        val store = CompositionStore(this)
        val d = store.daily()
        dailyName = d?.name
        if (d != null) {
            val data = DanceData.load(this)
            val whole = Composer.compose(data, store.sequence(d), d.startState, Piece.SWING)
            if (whole.errors.isEmpty()) dailyBeats = whole.length
            // the quick recap (user 2026-10-28): every part of the daily exercise once, in its order: no "x N", the quiet
            // bridges kept (they join the parts), no annotations (the little floor is silent)
            val once = d.elements.filter { !it.trim().lowercase().startsWith("say:") }.map { Entry.parse(it).copy(times = 1).text() }
            val recap = Composer.compose(data, store.expand(once), d.startState, Piece.SWING)
            if (recap.errors.isEmpty()) tl = Timeline(recap, Piece.SWING)
        }
    }

    override fun onResume() {
        super.onResume()
        // the dance floor may have changed the song or the tempo
        bpm = tempo()
        time.text = if (dailyBeats <= 0) "" else ((dailyBeats + 4) * 60.0 / bpm).roundToInt().let { s -> getString(R.string.minutes, s / 60, s % 60) }
        if (!helpOn) startSnippet()
    }

    override fun onPause() { super.onPause(); running = false }

    private fun startSnippet() {
        if (running) return
        running = true; t0 = SystemClock.uptimeMillis()
        snippet.postOnAnimation(tick)
    }

    private fun setHelp(on: Boolean) {
        helpOn = on
        helpPanel.visibility = if (on) View.VISIBLE else View.GONE
        snippet.visibility = if (on) View.INVISIBLE else View.VISIBLE
        recapLabel.visibility = snippet.visibility
        arrows.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { running = false; arrows.post { arrows.invalidate() } } else startSnippet()
    }

    /** the tempo the daily exercise is danced at: the chosen song's at the chosen offset, or the songs' average when they
     *  are shuffled */
    private fun tempo(): Double {
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        val off = prefs.getInt("bpm_offset", 0)
        val tracks = Track.loadAll(this)
        val chosen = tracks.firstOrNull { it.key == prefs.getString("song", Playback.SHUFFLE) }
        if (chosen != null) return chosen.variantFor(off).bpm
        return if (tracks.isEmpty()) 120.0 else tracks.map { it.variantFor(off).bpm }.average()
    }
}

/** The help view's arrows: from each explanation to the icon of the button it explains (dark blue, as in the draft). */
class HelpArrows @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var pairs: List<Pair<View, View>> = emptyList()
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ARROW; style = Paint.Style.FILL }
    private val path = Path()
    private val me = IntArray(2)
    private val a = IntArray(2)
    private val b = IntArray(2)

    override fun onDraw(c: Canvas) {
        getLocationInWindow(me)
        for ((from, to) in pairs) {
            from.getLocationInWindow(a); to.getLocationInWindow(b)
            val x0 = a[0] - me[0] + from.width - 18 * d; val y0 = a[1] - me[1] + from.height * 0.5f
            val x1 = b[0] - me[0] - 8 * d; val y1 = b[1] - me[1] + to.height * 0.5f
            arrow(c, x0, y0, x1, y1)
        }
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val len = hypot(x1 - x0, y1 - y0); if (len < 1f) return
        val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
        val head = 30 * d; val half = 17 * d; val shaft = 4.5f * d
        val bx = x1 - ux * head; val by = y1 - uy * head   // the middle of the head's base
        path.reset()
        path.moveTo(x0 - uy * shaft, y0 + ux * shaft); path.lineTo(bx - uy * shaft, by + ux * shaft)
        path.lineTo(bx - uy * half, by + ux * half); path.lineTo(x1, y1); path.lineTo(bx + uy * half, by - ux * half)
        path.lineTo(bx + uy * shaft, by - ux * shaft); path.lineTo(x0 + uy * shaft, y0 - ux * shaft)
        path.close()
        c.drawPath(path, paint)
    }
}
