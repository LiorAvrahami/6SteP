package org.lior.sixstep

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tap the 1: the track plays, you tap the screen on every count 1, and the app learns which beat of the grid is the 1
 *  and how far the beats you hear sit from the measured ones. The snare plays on the 1 as soon as it has a guess, so you
 *  hear whether it is right. Saved per track; the player and the metronome then follow it. */
class SyncActivity : AppCompatActivity() {
    private lateinit var tracks: List<Track>
    private lateinit var pick: Spinner
    private lateinit var info: TextView
    private lateinit var tapArea: View
    private val music by lazy { MusicPlayer(this) }
    private lateinit var metronome: Metronome
    private val handler = Handler(Looper.getMainLooper())
    private var grid: BeatGrid? = null
    private var clock: MusicClock? = null
    private var track: Track? = null
    private val taps = ArrayList<Double>()      // player positions (s) of the taps
    private var phase = 0
    private var offset = 0.0
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tracks = Track.loadAll(this); metronome = Metronome(this)
        pick = findViewById(R.id.syncTrack); info = findViewById(R.id.syncInfo); tapArea = findViewById(R.id.tapArea)
        pick.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tracks.map { "${it.title} (${it.bpm.roundToInt()})" })
        findViewById<MaterialButton>(R.id.syncStart).setOnClickListener { start() }
        findViewById<MaterialButton>(R.id.syncSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.syncReset).setOnClickListener { reset() }
        tapArea.setOnTouchListener { v, ev -> if (ev.action == MotionEvent.ACTION_DOWN) { tap(ev.eventTime); v.performClick() }; true }
        showSaved()
    }

    private fun prefs() = getSharedPreferences("6step", MODE_PRIVATE)

    private fun showSaved() {
        val t = tracks[pick.selectedItemPosition]
        val p = prefs()
        val saved = if (p.contains("sync_${t.key}_phase")) "saved: the 1 is beat ${p.getInt("sync_${t.key}_phase", 0) + 1} of the grid's bar, offset ${p.getInt("sync_${t.key}_offset_ms", 0)} ms" else "not calibrated yet (using the analysis)"
        info.text = "Press start, then tap the big area on every count 1.\n$saved"
    }

    private fun start() {
        stopAll()
        track = tracks[pick.selectedItemPosition]
        val t = track!!
        val g = BeatGrid.load(this, t.gridPath)      // the raw analysis: the taps decide the phase and offset
        grid = g; taps.clear(); phase = g.phase; offset = 0.0
        val mp = music.prepare(t)
        music.onComplete = { stopAll(); info.text = "The track ended. Press start to go again, or save." }
        music.startAt(0.0)
        val c = MusicClock(mp, g, 0, 0.0); clock = c
        metronome.clock = c; metronome.swing = g.swing; metronome.enabled = false; metronome.start()
        started = true
        info.text = "Playing ${t.title}. Tap on every 1."
    }

    private fun tap(eventUptime: Long) {
        val c = clock ?: return; val g = grid ?: return
        if (!started) return
        val pos = c.positionSec() - (SystemClock.uptimeMillis() - eventUptime) / 1000.0
        taps.add(pos)
        if (taps.size < 3) { info.text = "Tap ${taps.size}: keep going, on every 1"; return }
        // nearest grid beat of every tap: its residual and its position in the bar
        val ks = taps.map { p -> g.beatAt(p).roundToInt().coerceIn(0, g.beats.size - 1) }
        val res = taps.mapIndexed { i, p -> p - g.beats[ks[i]] }
        val counts = IntArray(4); ks.forEach { counts[it % 4]++ }
        phase = (0 until 4).maxByOrNull { counts[it] } ?: 0
        val onPhase = res.filterIndexed { i, _ -> ks[i] % 4 == phase }.sorted()
        offset = if (onPhase.isEmpty()) 0.0 else onPhase[onPhase.size / 2]
        val spread = if (onPhase.size > 1) (onPhase.last() - onPhase.first()) * 1000 else 0.0
        val agree = counts[phase] * 100 / taps.size
        // play the snare on the 1 as now understood: dance beat 0 = the next bar start of this phase
        g.phase = phase; g.offset = offset
        val k0 = g.startBeatIndex()
        val cc = MusicClock(music.player!!, g, k0, 0.0); clock = cc
        metronome.clock = cc; metronome.enabled = true; metronome.resync()
        info.text = "${taps.size} taps: the 1 is beat ${phase + 1} of the grid's bar ($agree% of your taps agree), offset ${(offset * 1000).roundToInt()} ms, spread ${spread.roundToInt()} ms.\n" +
            "The snare now plays on the 1 as understood. Keep tapping to refine, then save."
    }

    private fun save() {
        val t = track ?: tracks[pick.selectedItemPosition]
        if (taps.size < 3) { info.text = "Tap at least three 1s first."; return }
        prefs().edit().putInt("sync_${t.key}_phase", phase).putInt("sync_${t.key}_offset_ms", (offset * 1000).roundToInt()).apply()
        stopAll()
        info.text = "Saved for ${t.title}: the 1 is beat ${phase + 1} of the grid's bar, offset ${(offset * 1000).roundToInt()} ms."
    }

    private fun reset() {
        val t = tracks[pick.selectedItemPosition]
        prefs().edit().remove("sync_${t.key}_phase").remove("sync_${t.key}_offset_ms").apply()
        stopAll(); taps.clear(); showSaved()
    }

    private fun stopAll() { started = false; music.pause(); metronome.stop() }

    override fun onPause() { super.onPause(); stopAll() }
    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); music.release(); metronome.release() }
}
