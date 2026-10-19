package org.lior.sixstep

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Plays a composition on the music: the dance drawn in time, annotations shown large and read aloud, the music changing
 *  between tracks with the snare and hi-hat filling the gap, and the whole thing kept in one beat clock.
 *  Swipe left: the next annotation block. Swipe right: replay this block, or the previous one when this one just began.
 *  Swipe down: the options bar (the track, the snare, hi-hat and voice, the tempo, explore this element); swipe up closes it. */
class PlayerActivity : AppCompatActivity() {
    companion object {
        const val BACK_REPLAY_THRESHOLD_SECONDS = 2.0   // as in the Workout Instructinator app
        val BPM_OFFSETS = intArrayOf(-40, -30, -20, -10, 0, 10, 20)
        const val HANDOVER_MS = 220L        // a freshly started player runs this long before its clock takes over
        const val CROSSFADE_MS = 120L
        const val START_LATENCY_MS = 30L    // MediaPlayer.start() to the position actually moving, roughly
        const val END_LEAD_MS = 250L        // the drums take over this long before the music file ends, so the beat never stalls
        const val DUCK_LEVEL = 0.6f         // the music under a spoken annotation
    }

    private lateinit var floor: FloorView
    private lateinit var status: TextView
    private lateinit var bar: View
    private lateinit var trackPicker: LinearLayout
    private lateinit var bpmPicker: LinearLayout
    private lateinit var bpmLabel: TextView
    private lateinit var snareToggle: TextView
    private lateinit var hatToggle: TextView
    private lateinit var voiceToggle: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var data: DanceData
    private lateinit var store: CompositionStore
    private lateinit var tracks: List<Track>
    private lateinit var metronome: Metronome
    private lateinit var speaker: Speaker
    private val music by lazy { MusicPlayer(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private var duckRamp: Runnable? = null

    private var timeline: Timeline? = null
    private var clock: BeatClock? = null
    private var trackOrder = ArrayList<Track>()
    private var trackIdx = -1
    private var nextChoice = -1                  // the track the user picked to come next (index in trackOrder), -1 for the order's own
    private var baseGrid: BeatGrid? = null       // the current track's grid as annotated
    private var grid: BeatGrid? = null           // the same grid at the tempo being played
    private var playingVariant: TrackVariant? = null
    private var bpmOffset = 0                    // the session's tempo: this many BPM away from the original, for every track
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
    private var pendingSwitch: Runnable? = null
    private var pendingEnd: Runnable? = null     // the hand-over to the drums just before the music file ends
    private var handover: Handover? = null
    private var fade: Runnable? = null
    private var barShown = false
    private val trackChips = ArrayList<TextView>()
    private val bpmChips = ArrayList<TextView>()

    /** a hand-over in progress: the music clock that takes over once its player has settled, and the player fading out */
    private class Handover(val target: MusicClock, val oldPlayer: MediaPlayer?, val fire: Runnable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        floor = findViewById(R.id.floor); status = findViewById(R.id.status); bar = findViewById(R.id.optionsBar)
        trackPicker = findViewById(R.id.trackPicker); bpmPicker = findViewById(R.id.bpmPicker); bpmLabel = findViewById(R.id.bpmLabel)
        snareToggle = findViewById(R.id.snareToggle); hatToggle = findViewById(R.id.hatToggle); voiceToggle = findViewById(R.id.voiceToggle)
        data = DanceData.load(this); store = CompositionStore(this); tracks = Track.loadAll(this)
        metronome = Metronome(this); speaker = Speaker(this)
        speaker.onSpeaking = { duckMusic(it) }
        prefs = getSharedPreferences("6step", MODE_PRIVATE)
        overlay = prefs.getBoolean("metronome_over_music", false); speakOn = prefs.getBoolean("speak_annotations", true)
        bpmOffset = prefs.getInt("bpm_offset", 0)
        metronome.snareOn = prefs.getBoolean("snare_on", true); metronome.hatOn = prefs.getBoolean("hihat_on", true)
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
        buildBar()

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { togglePause(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dy) > 80 && abs(dy) > abs(dx) * 1.2) { if (dy > 0) showBar() else hideBar(); return true }
                if (abs(dx) > 80 && abs(dx) > abs(dy) * 1.2) { if (dx < 0) skipNext() else skipBack(); return true }
                return false
            }
        })
        floor.setOnTouchListener { v, ev -> gestures.onTouchEvent(ev); if (ev.action == MotionEvent.ACTION_UP) v.performClick(); true }
        val focus = takeAudioFocus()
        startTrack(0, 0.0, countIn = true)
        if (!focus) { togglePause(); status.text = "audio is busy (a call?) — tap to start" }   // the tap asks again
        handler.post(frame)
    }

    // ------------------------------------------------------------------ audio focus, and the voice over the music

    /** another app took the sound (a call, a prompt that wants it all): pause; the user taps to go on. A loss that "may
     *  duck" is ignored: this app keeps playing at full volume, and having asked to be paused rather than ducked
     *  (setWillPauseWhenDucked) the system never dims it on its own, which would take the music and the voice down together. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (!paused && !finished && clock != null) togglePause() }
            else -> {}   // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: keep playing; AUDIOFOCUS_GAIN: no automatic resume
        }
    }

    /** the sound for this session: asked for once at the start and again at every resume (after a loss the players stay
     *  muted until the app asks again); false when the phone will not give it, as during a call */
    private fun takeAudioFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, handler).build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** the music a little lower (DUCK_LEVEL) under a spoken annotation and back to full over 250 ms afterwards, so that
     *  music plus voice stays under full scale (a phone's own limiter pulling the whole output down is the other way to a
     *  dim mix). The ramp only ever heads for a level, so a restore cannot be missed or pile up. */
    private fun duckMusic(speaking: Boolean) {
        if (isDestroyed) return
        duckRamp?.let { handler.removeCallbacks(it) }
        val from = music.duckLevel; val to = if (speaking) DUCK_LEVEL else 1f
        val ms = if (speaking) 100.0 else 250.0
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val u = ((SystemClock.uptimeMillis() - t0 + 20) / ms).coerceIn(0.0, 1.0)
                music.duck(from + (to - from) * u.toFloat())
                if (u < 1.0) handler.postDelayed(this, 20) else duckRamp = null
            }
        }
        duckRamp = r; r.run()
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
    private fun currentTrack(): Track? = if (trackIdx < 0 || trackOrder.isEmpty()) null else trackOrder[trackIdx % trackOrder.size]
    private fun nextTrackIdx(): Int = if (nextChoice >= 0) nextChoice else trackIdx + 1
    private fun musicClockOf(c: BeatClock?): MusicClock? = when (c) { is MusicClock -> c; is SlewClock -> c.inner as? MusicClock; else -> null }
    private fun cancelPendingStart() { pendingStart?.let { handler.removeCallbacks(it) }; pendingStart = null }
    private fun cancelSwitch() { pendingSwitch?.let { handler.removeCallbacks(it) }; pendingSwitch = null; music.dropNext() }
    private fun cancelTrackEnd() { pendingEnd?.let { handler.removeCallbacks(it) }; pendingEnd = null }

    /** the drums take over END_LEAD_MS before the file of `mc`, now at `posSec`, runs out (its completion callback would
     *  come late and the beat would stall meanwhile); the callback itself is then a no-op */
    private fun armTrackEnd(mc: MusicClock, posSec: Double) {
        cancelTrackEnd()
        val r = Runnable { pendingEnd = null; if (!paused && !finished && musicClockOf(clock) === mc) onTrackEnded() }
        pendingEnd = r
        handler.postAtTime(r, SystemClock.uptimeMillis() + ((mc.grid.duration - posSec) * 1000.0).toLong() - END_LEAD_MS)
    }

    /** "Hot Swing — Kevin MacLeod  · 144 → 104 BPM  · steady", for the variant of the session's offset unless another is given */
    private fun statusLine(t: Track, variant: TrackVariant? = null): String {
        val v = variant ?: t.variantFor(bpmOffset)
        val bpm = if (v.offset == 0) "${t.bpm.roundToInt()} BPM" else "${t.bpm.roundToInt()} → ${v.bpm.roundToInt()} BPM"
        return "${t.title} — ${t.artist}  · $bpm" + (if (t.difficulty.isNotBlank()) "  · " + t.difficulty else "")
    }

    // ------------------------------------------------------------------ the options bar

    /** one chip per track, the snare, hi-hat and voice toggles, a chip per tempo offset, explore, close */
    private fun buildBar() {
        trackPicker.removeAllViews(); trackChips.clear()
        for (t in tracks) {
            val hint = t.difficulty.substringBefore(":").substringBefore(",").trim()
            val c = chip("${t.title}\n${t.bpm.roundToInt()} BPM" + (if (hint.isNotEmpty()) " · $hint" else ""), 12f, 12)
            c.setOnClickListener { chooseTrack(t) }
            trackPicker.addView(c); trackChips.add(c)
        }
        bpmPicker.removeAllViews(); bpmChips.clear()
        for (off in BPM_OFFSETS) {
            val c = chip(if (off > 0) "+$off" else off.toString(), 13f, 8)
            c.setOnClickListener { setBpmOffset(off) }
            bpmPicker.addView(c); bpmChips.add(c)
        }
        snareToggle.setOnClickListener { drum(snare = true) }
        hatToggle.setOnClickListener { drum(snare = false) }
        voiceToggle.setOnClickListener { toggleVoice() }
        findViewById<ImageButton>(R.id.exploreHere).setOnClickListener { explore() }
        findViewById<ImageButton>(R.id.closeBar).setOnClickListener { hideBar() }
        updateBar()
    }

    private fun chip(text: String, size: Float, hpadDp: Int): TextView {
        val d = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = text; tv.textSize = size; tv.gravity = Gravity.CENTER; tv.maxLines = 2
        tv.setBackgroundResource(R.drawable.bg_chip); tv.minHeight = (36 * d).toInt()
        tv.setPadding((hpadDp * d).toInt(), (4 * d).toInt(), (hpadDp * d).toInt(), (4 * d).toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.marginEnd = (6 * d).toInt()
        tv.layoutParams = lp
        return tv
    }

    /** a chip's look: on (filled), pending (outlined: the track that comes next) or off */
    private fun paint(tv: TextView, on: Boolean, pending: Boolean = false) {
        tv.isSelected = on; tv.isActivated = pending && !on
        tv.setTextColor(getColor(if (on) R.color.ink else if (pending) R.color.accent else R.color.floor))
    }

    private fun updateBar() {
        val cur = currentTrack()
        val next = if (nextChoice >= 0 && nextChoice < trackOrder.size) trackOrder[nextChoice] else null
        for ((i, c) in trackChips.withIndex()) { val t = tracks[i]; paint(c, t === cur, t === next) }
        for ((i, c) in bpmChips.withIndex()) paint(c, BPM_OFFSETS[i] == bpmOffset)
        val heard = overlay || musicClockOf(clock) == null   // over the music only with the overlay; always in the gap between tracks
        paint(snareToggle, metronome.snareOn && heard); paint(hatToggle, metronome.hatOn && heard)
        paint(voiceToggle, speakOn)
        bpmLabel.text = if (cur == null) "" else { val v = cur.variantFor(bpmOffset); if (v.offset == 0) "${cur.bpm.roundToInt()} BPM" else "→ ${v.bpm.roundToInt()} BPM" }
    }

    /** the drums of the bar are what is heard: a drum switched on while the music plays without the overlay turns the
     *  overlay on with that drum alone; both off turns the overlay off. The main screen's switch follows the same preference. */
    private fun drum(snare: Boolean) {
        val inMusic = musicClockOf(clock) != null
        if (inMusic && !overlay) { overlay = true; metronome.snareOn = snare; metronome.hatOn = !snare }
        else {
            if (snare) metronome.snareOn = !metronome.snareOn else metronome.hatOn = !metronome.hatOn
            if (inMusic) overlay = metronome.snareOn || metronome.hatOn
        }
        prefs.edit().putBoolean("snare_on", metronome.snareOn).putBoolean("hihat_on", metronome.hatOn).putBoolean("metronome_over_music", overlay).apply()
        if (inMusic) { metronome.enabled = overlay; if (!paused) metronome.resync() }
        updateBar()
    }

    /** the annotations read aloud or silent (they are still shown): the same preference as the main screen's switch */
    private fun toggleVoice() {
        speakOn = !speakOn
        prefs.edit().putBoolean("speak_annotations", speakOn).apply()
        if (!speakOn) speaker.stop() else timeline?.let { speaker.prefetch(announcements(it.composed)) }
        updateBar()
    }

    private fun showBar() { if (!barShown) { barShown = true; updateBar(); bar.visibility = View.VISIBLE; bar.translationY = -bar.height.toFloat(); bar.animate().translationY(0f).setDuration(180).start() } }
    private fun hideBar() { if (barShown) { barShown = false; bar.animate().translationY(-bar.height.toFloat()).setDuration(180).withEndAction { bar.visibility = View.GONE }.start() } }

    /** the track the user tapped comes next, at the next block boundary, and the order carries on after it; tapping the
     *  pending one again (or the current one while it plays) undoes the choice */
    private fun chooseTrack(t: Track) {
        val j = trackOrder.indexOf(t); if (j < 0 || finished || timeline == null) return
        val b = currentBeat()
        val playingMusic = musicClockOf(clock) != null
        if (nextChoice == j || (nextChoice >= 0 && t === currentTrack() && playingMusic)) {
            nextChoice = -1
            if (playingMusic) cancelPendingStart() else if (!paused) scheduleNextTrack(b)
            updateBar(); return
        }
        if (t === currentTrack() && playingMusic) return   // already playing
        nextChoice = j
        if (!paused) scheduleNextTrack(b, j)   // paused: the resume schedules it
        updateBar()
    }

    /** the session's tempo offset: kept for the next tracks, the music that is playing switched over (see switchVariant), and
     *  the drums in the gap between tracks re-paced at once */
    private fun setBpmOffset(off: Int) {
        if (off == bpmOffset) return
        bpmOffset = off; prefs.edit().putInt("bpm_offset", off).apply()
        val tc = clock as? TempoClock; val t = currentTrack(); val base = baseGrid
        if (tc != null && t != null && base != null && !paused && !finished) {
            val g = base.scaled(t.variantFor(off).tempo); grid = g
            val b = tc.beatNow(); tc.reanchor(b, SystemClock.uptimeMillis()); tc.bpm = g.bpm
            metronome.resync(); scheduleNextTrack(b)   // the next track's start moves with the new pace
            status.text = "snare and hi-hat until the next track  · ${g.bpm.roundToInt()} BPM"
        }
        updateBar()
        if (!paused && !finished) switchVariant()   // paused: applied at the resume
    }

    /** pause and open this element in the exploration floor at the beat being danced; back returns here, still paused */
    private fun explore() {
        val tl = timeline ?: return
        if (finished) return
        val b = currentBeat()
        val idx = tl.elementIndexAt(b.coerceAtLeast(0.0)).let { if (it < 0) 0 else it }
        val el = tl.composed.elements.getOrNull(idx) ?: return
        val vi = data.variants(el.name).indexOfFirst { it.startState == el.startState && it.leaderFreeStart == el.free }.coerceAtLeast(0)
        val atBeat = (b - el.start).coerceIn(0.0, el.beats.toDouble())
        if (!paused) togglePause()
        hideBar()
        startActivity(Intent(this, FloorActivity::class.java).putExtra("kind", "element").putExtra("name", el.name).putExtra("variant", vi).putExtra("atBeat", atBeat))
    }

    // ------------------------------------------------------------------ the music

    /** start track i of the order so that its first dance bar is dance beat `atBeat`, at the tempo of the session's offset */
    private fun startTrack(i: Int, atBeat: Double, countIn: Boolean) {
        val t = trackOrder[i % trackOrder.size]; trackIdx = i; nextChoice = -1; pendingStart = null
        pendingSwitch?.let { handler.removeCallbacks(it) }; pendingSwitch = null   // a cued player is kept if it is this file
        completeHandover(slew = false); cancelTrackEnd()
        val v = t.variantFor(bpmOffset)
        val base = BeatGrid.loadFor(this, t); baseGrid = base
        val g = base.scaled(v.tempo); grid = g; k0 = g.startBeatIndex(); playingVariant = v
        val mp = music.next?.takeIf { music.nextPath == v.file } ?: music.prepareNext(v.file)   // opened early by scheduleNextTrack when it could be
        val startSec = g.beats[(k0 - (if (countIn) 4 else 0)).coerceAtLeast(0)]
        music.onComplete = { onTrackEnded() }
        val old = clock
        val direct = old == null || countIn
        // a track still playing (the user chose this one over it) fades out under the new one; a finished one just goes
        val fadeOut: MediaPlayer? = if (direct) { music.swapToNext()?.let { music.releasePlayer(it) }; null }
            else music.swapToNext()?.let { if (music.playing(it)) it else { music.releasePlayer(it); null } }
        music.setVolume(mp, if (fadeOut != null) 0f else 1f)
        music.startAt(startSec)
        val mc = MusicClock(mp, g, k0, atBeat)
        if (direct) {
            clock = mc; armTrackEnd(mc, startSec)
        } else {
            // the music starts a little late: hand over without a jump, fading the difference out over a second
            if (fadeOut != null) crossfade(fadeOut, mp)
            beginHandover(mc, fadeOut, HANDOVER_MS)
        }
        metronome.clock = clock; metronome.swing = g.swing; metronome.enabled = overlay; metronome.resync(); metronome.start()
        status.text = statusLine(t)
        updateBar()
    }

    /** the music is running out (END_LEAD_MS before the file ends, or its completion when that comes first): keep the beat
     *  going on the snare and hi-hat, start the next track at the next block */
    private fun onTrackEnded() {
        if (finished || paused || musicClockOf(clock) == null) return   // already on the drums
        cancelSwitch(); completeHandover(slew = false); cancelTrackEnd()
        val g = grid ?: return
        val b = currentBeat()
        val tempo = TempoClock(g.bpm, b, SystemClock.uptimeMillis())
        clock = tempo; metronome.clock = tempo; metronome.enabled = true; metronome.resync()
        scheduleNextTrack(b)
        status.text = "snare and hi-hat until the next track"
    }

    /** start track `nextIdx` of the order at the first block boundary at least 2 beats ahead of `fromBeat` */
    private fun scheduleNextTrack(fromBeat: Double, nextIdx: Int = nextTrackIdx()) {
        val tl = timeline ?: return; val c = clock ?: return
        cancelPendingStart()
        val next = tl.composed.elements.map { it.start }.firstOrNull { it >= fromBeat + 2.0 } ?: (floor(fromBeat) + 4.0)
        if (next >= tl.length) return
        val delay = (c.uptimeOfBeat(next) - SystemClock.uptimeMillis() - 90).coerceAtLeast(0L)
        val r = Runnable { if (!finished && !paused) startTrack(nextIdx, next, countIn = false) }
        pendingStart = r
        handler.postDelayed(r, delay)
        // the file is opened now, so that nothing heavy happens on the beat it starts on (a cued tempo switch keeps the slot)
        if (pendingSwitch == null && delay > 300) {
            val file = trackOrder[nextIdx % trackOrder.size].variantFor(bpmOffset).file
            if (music.nextPath != file) try { music.prepareNext(file) } catch (e: Exception) {}
        }
    }

    /** the running music switches to the variant of the session's offset: the file is cued to the next whole dance beat (at
     *  least 0.35 s ahead), started on that beat, crossfaded in over 120 ms, and its clock handed over like a late start */
    private fun switchVariant() {
        cancelSwitch()
        val t = currentTrack() ?: return
        val v = t.variantFor(bpmOffset)
        if (paused || finished || v.offset == playingVariant?.offset) return
        if (handover != null) {   // one hand-over at a time: again once the current one is done
            val r = Runnable { pendingSwitch = null; switchVariant() }
            pendingSwitch = r; handler.postDelayed(r, HANDOVER_MS + 50); return
        }
        val c = clock ?: return
        val mc = musicClockOf(c) ?: return   // between tracks: the next track takes the offset
        val base = baseGrid ?: return
        val g = base.scaled(v.tempo)
        val now = SystemClock.uptimeMillis()
        var b = floor(c.beatNow()) + 1.0
        while (c.uptimeOfBeat(b) < now + 350) b += 1.0
        val sec = g.beatSeconds(b - mc.danceBeatOffset + mc.k0) + g.offset
        if (sec < 0 || sec >= g.duration - 0.5) {   // the faster file is over by then: this one plays out, the next track takes the offset
            status.text = statusLine(t, playingVariant) + "  · ${v.bpm.roundToInt()} BPM from the next track"
            return
        }
        val mp = try { music.prepareNext(v.file) } catch (e: Exception) { return }
        music.setVolume(mp, 0f); music.seekTo(mp, sec)
        val r = Runnable {
            pendingSwitch = null
            if (paused || finished || clock !== c) { music.dropNext(); return@Runnable }
            val old = music.swapToNext()
            mp.start()
            grid = g; playingVariant = v
            crossfade(old, mp)
            beginHandover(MusicClock(mp, g, mc.k0, mc.danceBeatOffset), old, HANDOVER_MS)
            status.text = statusLine(t)
        }
        pendingSwitch = r
        handler.postAtTime(r, c.uptimeOfBeat(b) - START_LATENCY_MS)
    }

    /** the current track again on the variant of the session's offset, from dance beat b (a resume after the tempo was
     *  changed while paused): the clock keeps its k0 and beat offset, so the dance is exactly where it was */
    private fun restartMusic(mc: MusicClock, b: Double) {
        val t = currentTrack() ?: return; val base = baseGrid ?: return
        val v = t.variantFor(bpmOffset); val g = base.scaled(v.tempo)
        val sec = g.beatSeconds(b - mc.danceBeatOffset + mc.k0) + g.offset
        grid = g; playingVariant = v
        if (sec >= 0 && sec < g.duration - 1.0) {
            val mp = music.prepareNext(v.file)
            music.swapToNext()?.let { music.releasePlayer(it) }
            music.setVolume(mp, 1f)
            music.startAt(sec)
            val fresh = MusicClock(mp, g, mc.k0, mc.danceBeatOffset); clock = fresh; armTrackEnd(fresh, sec)
        } else {
            // the faster file is already over here: the snare and hi-hat until the next track
            clock = TempoClock(g.bpm, b, SystemClock.uptimeMillis()); metronome.enabled = true; scheduleNextTrack(b)
        }
    }

    /** the music of `target` has just started: its clock takes over after `afterMs`, the difference faded out over 1.2 s */
    private fun beginHandover(target: MusicClock, oldPlayer: MediaPlayer?, afterMs: Long) {
        completeHandover(slew = false)
        val r = Runnable { if (!finished) completeHandover(slew = true) }
        handover = Handover(target, oldPlayer, r)
        handler.postDelayed(r, afterMs)
    }

    /** the pending hand-over's clock takes over: gently (slew) or at once (before a pause, a jump or another start) */
    private fun completeHandover(slew: Boolean) {
        val h = handover ?: return
        handover = null; handler.removeCallbacks(h.fire)
        fade?.let { handler.removeCallbacks(it) }; fade = null
        music.setVolume(h.target.player, 1f)
        val old = clock
        h.target.resync()
        clock = if (slew && old != null && !paused) SlewClock(h.target, old.beatNow() - h.target.beatNow(), 1200) else h.target
        h.oldPlayer?.let { music.releasePlayer(it) }
        metronome.clock = clock; metronome.resync()
        if (!paused) armTrackEnd(h.target, h.target.positionSec())
    }

    /** an equal-power crossfade from the player being replaced to the new one over 120 ms (the hand-over releases the old one) */
    private fun crossfade(old: MediaPlayer?, fresh: MediaPlayer) {
        fade?.let { handler.removeCallbacks(it) }
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val u = ((SystemClock.uptimeMillis() - t0 + 20).toDouble() / CROSSFADE_MS).coerceIn(0.0, 1.0)
                music.setVolume(fresh, sin(u * PI / 2).toFloat())
                old?.let { music.setVolume(it, cos(u * PI / 2).toFloat()) }
                if (u < 1.0) handler.postDelayed(this, 20) else fade = null
            }
        }
        fade = r; r.run()
    }

    // ------------------------------------------------------------------ moving about

    /** move the whole session to a beat: the music seeks when it is playing, the tempo clock is re-anchored otherwise */
    private fun jumpTo(b0: Double) {
        val tl = timeline ?: return
        val b = b0.coerceIn(0.0, tl.length - 1e-3)
        cancelSwitch(); completeHandover(slew = false)
        val c = clock
        val mc = musicClockOf(c)
        if (mc != null && music.player != null) {
            val sec = mc.grid.beatSeconds(b - mc.danceBeatOffset + mc.k0) + mc.grid.offset
            if (sec < mc.grid.duration - 1.0) {
                music.seekTo(sec); mc.resync()
                clock = mc; armTrackEnd(mc, sec)
                if (nextChoice >= 0 && !paused) scheduleNextTrack(b, nextChoice)   // the chosen track's start moves with the jump
            } else {
                val tempo = TempoClock(mc.grid.bpm, b, SystemClock.uptimeMillis()); clock = tempo; metronome.enabled = true; music.pause()
                cancelTrackEnd(); scheduleNextTrack(b)
            }
        } else {
            val tempo = (c as? TempoClock) ?: TempoClock(grid?.bpm ?: 120.0, b, SystemClock.uptimeMillis())
            tempo.reanchor(b, SystemClock.uptimeMillis()); clock = tempo
            scheduleNextTrack(b)
        }
        if (paused) pausedAtBeat = b
        metronome.clock = clock; metronome.resync()
        speaker.stop(); lastAnnouncedElement = -1; banner = null
        if (!paused) switchVariant()   // a tempo change still on its way is planned afresh from here
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
            paused = true; pausedAtBeat = c.beatNow()
            cancelSwitch(); cancelTrackEnd(); completeHandover(slew = false)
            music.pause(); metronome.stop(); speaker.stop()
            cancelPendingStart()
            status.text = "paused — tap to continue, swipe for the next or previous part"
        } else {
            if (!takeAudioFocus()) { status.text = "audio is busy (a call?) — tap again"; return }
            paused = false
            val mc = musicClockOf(clock)
            val t = currentTrack()
            if (mc != null && music.player != null) {
                if (t != null && t.variantFor(bpmOffset).offset != playingVariant?.offset) restartMusic(mc, pausedAtBeat)   // the tempo changed while paused
                else {
                    val sec = mc.grid.beatSeconds(pausedAtBeat - mc.danceBeatOffset + mc.k0) + mc.grid.offset
                    music.seekTo(sec); music.resume(); mc.resync(); clock = mc; armTrackEnd(mc, sec)
                }
                if (nextChoice >= 0) scheduleNextTrack(pausedAtBeat, nextChoice)
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
        handler.removeCallbacksAndMessages(null); duckRamp = null
        handover?.oldPlayer?.let { music.releasePlayer(it) }; handover = null
        music.release(); metronome.release(); speaker.release()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }; focusRequest = null
    }
}
