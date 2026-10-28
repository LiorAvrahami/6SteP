package org.lior.sixstep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** The beat the dance floor dances to, made from scratch at every play (user 2026-10-28: "when pausing there should be no
 *  memory of the animation left over, playing should initialize everything from the step that we are currently in; for
 *  every step the app should know via a lookup which beat this is in the audio").
 *
 *  The plan lays the music along the dance once, for the song and the tempo chosen: the first track's first downbeat
 *  (BeatGrid.startBeatIndex) on dance beat 0, with four beats of it before (the count-in); each next track on the first
 *  element start at least two beats after the previous file runs out, the snare and hi-hat in between. A chosen song
 *  plays over and over; shuffle goes through all of them. So every dance beat has one place in the audio (segmentAt), and
 *  play(b) opens a new player, seeks it to exactly that place and starts it there; stop() releases everything. A change
 *  of song, tempo or mute while playing is a stop and a play from the beat it is at. Muted: a steady clock, no sound.
 *  The voice reads the annotations with the music ducked under it; the snare and hi-hat go over the music when the
 *  settings say so. */
class Playback(private val ctx: Context) {
    companion object {
        val BPM_OFFSETS = intArrayOf(-40, -30, -20, -10, 0, 10, 20)
        const val SHUFFLE = "shuffle"
        const val COUNT_IN = 4.0             // beats of the first track before the dance starts
        const val GAP = 2.0                  // a track starts on the first element start at least this many beats after the last one ran out
        const val END_LEAD = 0.25            // s: a track counts as over this long before its file ends (its completion callback comes late)
        const val HANDOVER_MS = 220L         // a new player runs this long before the music's own clock takes over...
        const val SLEW_MS = 1200L            // ...fading the difference out over this long
        const val SEEK_WAIT_MS = 600L        // a seek that never reports back: start anyway
        const val START_LATENCY_MS = 30L     // MediaPlayer.start() to the music actually moving, roughly
        const val DUCK_LEVEL = 0.6f          // the music under a spoken annotation
    }

    /** a track's place in the dance: grid beat k0 of `grid` (the track at the session's tempo) falls on dance beat `from`;
     *  the music covers the dance from musicFrom (four beats earlier for the first track) to until (the file runs out) */
    class Segment(val track: Track, val file: String, val grid: BeatGrid, val k0: Int, val from: Double, val musicFrom: Double, val until: Double) {
        /** where dance beat b is in the file, in seconds */
        fun seconds(b: Double): Double = grid.beatSeconds(b - from + k0) + grid.offset
    }

    private val ui = Handler(Looper.getMainLooper())      // the voice's duck and the audio focus
    private val sched = Handler(Looper.getMainLooper())   // everything a play schedules; stop() drops all of it
    private val prefs = ctx.getSharedPreferences("6step", Context.MODE_PRIVATE)
    val tracks: List<Track> = Track.loadAll(ctx)
    private val grids = HashMap<String, BeatGrid>()
    private val music = MusicPlayer(ctx)
    private val metronome = Metronome(ctx)
    private val speakerLazy = lazy { Speaker(ctx).also { s -> s.onSpeaking = { duckMusic(it) } } }
    private val speaker by speakerLazy
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var duckRamp: Runnable? = null

    /** where a track may start: the first element start at or after a dance beat; null after the end of the piece */
    var boundaryAfter: (Double) -> Double? = { null }
        set(v) { field = v; replan() }
    /** the song or the tempo changed */
    var onChange: (() -> Unit)? = null
    /** another app took the sound (a call): the floor pauses */
    var onFocusLoss: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    var muted = prefs.getBoolean("muted", false)
        private set
    var song: String = prefs.getString("song", SHUFFLE) ?: SHUFFLE
        private set
    var bpmOffset = prefs.getInt("bpm_offset", 0)
        private set
    private var overlay = false
    private var speakOn = true
    private var order: List<Track> = emptyList()
    private val plan = ArrayList<Segment>()
    private var planDone = false

    // the playing, all of it forgotten by stop()
    private var running = false
    private var released = false
    private var generation = 0
    private var startBeat = 0.0
    private var clock: BeatClock? = null
    private var segment: Segment? = null

    init { readSettings(); reorder() }

    val playing: Boolean get() = running
    /** the dance beat now; until the music has really started, the beat it starts from */
    fun beatNow(): Double = clock?.beatNow() ?: startBeat
    /** the track playing, or the first one of the plan */
    fun currentTrack(): Track? = segment?.track ?: order.firstOrNull()

    /** the tempo danced (BPM): what plays, or the first track's at the chosen offset */
    fun bpm(): Double {
        (clock as? TempoClock)?.let { return it.bpm }
        segment?.let { return it.grid.bpm }
        return order.firstOrNull()?.variantFor(bpmOffset)?.bpm ?: 120.0
    }

    /** the settings page may have changed these since */
    fun readSettings() {
        overlay = prefs.getBoolean("metronome_over_music", false); speakOn = prefs.getBoolean("speak_annotations", true)
        metronome.snareOn = prefs.getBoolean("snare_on", true); metronome.hatOn = prefs.getBoolean("hihat_on", true)
        if (running && segment != null) metronome.enabled = overlay   // between two tracks the drums play anyway
    }

    /** the chosen song, over and over; or every song, shuffled */
    private fun reorder() {
        val chosen = tracks.firstOrNull { it.key == song }
        order = if (chosen != null) listOf(chosen) else tracks.shuffled()
        replan()
    }

    private fun replan() { plan.clear(); planDone = false }
    private fun gridOf(t: Track): BeatGrid = grids.getOrPut(t.key) { BeatGrid.loadFor(ctx, t) }

    /** the segment of the plan that dance beat b is in or after (b is in a gap when it is past its `until`); the plan is
     *  laid out as far as b needs */
    private fun segmentAt(b: Double): Segment? {
        while (!planDone && order.isNotEmpty() && (plan.isEmpty() || plan.last().until <= b + 8)) {
            val first = plan.isEmpty()
            val from = if (first) 0.0 else boundaryAfter(plan.last().until + GAP)
            if (from == null) { planDone = true; break }
            val t = order[plan.size % order.size]
            val v = t.variantFor(bpmOffset)
            val g = gridOf(t).scaled(v.tempo)
            val k0 = g.startBeatIndex()
            val until = from + g.beatAt(g.duration - END_LEAD - g.offset) - k0
            plan.add(Segment(t, v.file, g, k0, from, if (first) from - COUNT_IN else from, until))
        }
        return plan.lastOrNull { it.musicFrom <= b }
    }

    private fun tempoAt(b: Double): Double = segmentAt(b)?.grid?.bpm ?: order.firstOrNull()?.variantFor(bpmOffset)?.bpm ?: 120.0

    // ------------------------------------------------------------------ playing and stopping

    /** play from dance beat b, everything new: the clock, a player opened and sought to b's place in the plan, the drums,
     *  the next track's start. Muted: a steady clock from b at once. */
    fun play(b: Double) {
        if (released) return
        stop()
        readSettings()
        running = true; startBeat = b
        if (muted || tracks.isEmpty()) { clock = TempoClock(tempoAt(b), b, SystemClock.uptimeMillis()) }
        else if (!takeAudioFocus()) { running = false; onMessage?.invoke(ctx.getString(R.string.audio_busy)) }
        else {
            val s = segmentAt(b)
            if (s != null && b < s.until) startMusic(s, b) else drums(b, tempoAt(b))
        }
        onChange?.invoke()
    }

    /** stop and forget: the players released; the clock, the drums, the voice and everything scheduled gone */
    fun stop() {
        generation++
        sched.removeCallbacksAndMessages(null)
        music.releaseAll()
        metronome.stop(); metronome.enabled = false; metronome.clock = null
        silence()
        duckRamp?.let { ui.removeCallbacks(it) }; duckRamp = null; music.duck(1f)   // the next player starts at full volume
        clock = null; segment = null; running = false
    }

    /** segment s from dance beat b: a new player sought there and started once the seek is done; the dance runs on a
     *  steady clock from that instant, and the music's own clock takes over a moment later, gently */
    private fun startMusic(s: Segment, b: Double) {
        val mp = try { music.open(s.file) } catch (e: Exception) { drums(b, s.grid.bpm); return }
        segment = s
        val gen = generation
        var started = false
        val go = Runnable {
            if (started || gen != generation) return@Runnable
            started = true
            mp.setOnSeekCompleteListener(null)
            mp.start()
            val steady = TempoClock(s.grid.bpm, b, SystemClock.uptimeMillis())
            clock = steady
            metronome.clock = steady; metronome.swing = s.grid.swing; metronome.enabled = overlay; metronome.resync(); metronome.start()
            handOver(steady, MusicClock(mp, s.grid, s.k0, s.from))
            endOf(s, mp, ((s.seconds(s.until) - s.seconds(b)) * 1000).toLong())
        }
        mp.setOnSeekCompleteListener { go.run() }
        music.seekTo(mp, s.seconds(b))
        sched.postDelayed(go, SEEK_WAIT_MS)
    }

    /** after HANDOVER_MS the music's own clock drives the dance, the difference from `from` faded out over SLEW_MS */
    private fun handOver(from: BeatClock, mc: MusicClock) {
        val gen = generation
        sched.postDelayed({
            if (gen == generation) {
                mc.resync()
                val c = SlewClock(mc, from.beatNow() - mc.beatNow(), SLEW_MS)
                clock = c; metronome.clock = c; metronome.resync()
            }
        }, HANDOVER_MS)
    }

    /** the music of segment s runs out after `ms` (or reports its end first): the drums take over */
    private fun endOf(s: Segment, mp: MediaPlayer, ms: Long) {
        val gen = generation
        mp.setOnCompletionListener { if (gen == generation && segment === s) toDrums() }
        sched.postDelayed({ if (gen == generation && segment === s) toDrums() }, ms.coerceAtLeast(0L))
    }

    private fun toDrums() {
        val b = beatNow(); val bpm = segment?.grid?.bpm ?: tempoAt(b)
        music.releaseAll(); segment = null
        drums(b, bpm)
        onChange?.invoke()
    }

    /** the snare and hi-hat from dance beat b; the next track of the plan opened now, sought to its first beat, and
     *  started on it */
    private fun drums(b: Double, bpm: Double) {
        val steady = TempoClock(bpm, b, SystemClock.uptimeMillis())
        clock = steady
        metronome.clock = steady; metronome.enabled = true; metronome.resync(); metronome.start()
        segmentAt(b)
        val next = plan.firstOrNull { it.from > b } ?: return
        val mp = try { music.open(next.file) } catch (e: Exception) { return }
        music.seekTo(mp, next.seconds(next.from))
        val gen = generation
        val delay = (steady.uptimeOfBeat(next.from) - SystemClock.uptimeMillis() - START_LATENCY_MS).coerceAtLeast(0L)
        sched.postDelayed({
            if (gen == generation) {
                mp.start(); segment = next
                metronome.enabled = overlay
                handOver(steady, MusicClock(mp, next.grid, next.k0, next.from))
                endOf(next, mp, ((next.seconds(next.until) - next.seconds(next.from)) * 1000).toLong())
                onChange?.invoke()
            }
        }, delay)
    }

    // ------------------------------------------------------------------ the song, the tempo, mute

    fun chooseSong(key: String) {
        song = key; prefs.edit().putString("song", key).apply()
        reorder(); again()
    }

    /** the tempo offset (the shipped tempo variants), the same for every song */
    fun setBpmOffset(off: Int) {
        if (off == bpmOffset) return
        bpmOffset = off; prefs.edit().putInt("bpm_offset", off).apply()
        replan(); again()
    }

    /** mute: no music, no drums, no voice */
    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m; prefs.edit().putBoolean("muted", m).apply()
        again()
    }

    /** a change while playing: a stop, and a play from the beat it is at */
    private fun again() { if (running) play(beatNow()) else onChange?.invoke() }

    // ------------------------------------------------------------------ the voice

    fun prefetch(texts: Collection<String>) { if (!muted && speakOn && texts.isNotEmpty()) speaker.prefetch(texts) }
    fun speak(text: String) { if (!muted && speakOn && running) speaker.speak(text) }
    fun silence() { if (speakerLazy.isInitialized()) speaker.stop() }

    /** another app took the sound (a call, a prompt that wants it all): pause; the user presses play to go on. A loss
     *  that "may duck" is ignored: this app keeps playing at full volume, and having asked to be paused rather than
     *  ducked (setWillPauseWhenDucked) the system never dims it on its own, which would take the music and the voice
     *  down together. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (running) onFocusLoss?.invoke() }
            else -> {}   // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: keep playing; AUDIOFOCUS_GAIN: no automatic resume
        }
    }

    /** the sound, asked for at every play (after a loss the players stay muted until the app asks again); false when the
     *  phone will not give it, as during a call */
    private fun takeAudioFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, ui).build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** the music a little lower (DUCK_LEVEL) under a spoken annotation and back to full over 250 ms afterwards, so that
     *  music plus voice stays under full scale. The ramp only ever heads for a level, so a restore cannot be missed. */
    private fun duckMusic(speaking: Boolean) {
        if (released) return
        duckRamp?.let { ui.removeCallbacks(it) }
        val from = music.duckLevel; val to = if (speaking) DUCK_LEVEL else 1f
        val ms = if (speaking) 100.0 else 250.0
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val u = ((SystemClock.uptimeMillis() - t0 + 20) / ms).coerceIn(0.0, 1.0)
                music.duck(from + (to - from) * u.toFloat())
                if (u < 1.0) ui.postDelayed(this, 20) else duckRamp = null
            }
        }
        duckRamp = r; r.run()
    }

    fun release() {
        stop(); released = true
        ui.removeCallbacksAndMessages(null); duckRamp = null
        metronome.release()
        if (speakerLazy.isInitialized()) speaker.release()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }; focusRequest = null
    }
}
