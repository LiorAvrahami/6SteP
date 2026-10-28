package org.lior.sixstep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The beat the dance floor dances to. Moved out of the old PlayerActivity (2026-10-28) so that the one dance floor
 *  plays elements, building blocks and compositions alike; the music logic is the player's, unchanged where it could be.
 *
 *  Unmuted: the music, one track after another (the chosen song first, then the others shuffled), at the session's tempo
 *  offset (the shipped tempo variants); the snare and hi-hat keep the beat in the gap between two tracks (and over the
 *  music when the settings say so); the voice reads the annotations with the music ducked under it. A track starts with a
 *  count-in of four beats, its first bar on the dance beat it was started for; a later one at an element start at least
 *  two beats ahead, crossfaded. Muted: a steady clock at the same tempo, and no sound at all.
 *
 *  Beats are dance beats on one clock that never wraps; the floor maps a piece that loops onto its own timeline, and
 *  tells this class where elements start (boundaryAfter) so that tracks start on them. */
class Playback(private val ctx: Context) {
    companion object {
        val BPM_OFFSETS = intArrayOf(-40, -30, -20, -10, 0, 10, 20)
        const val SHUFFLE = "shuffle"
        const val HANDOVER_MS = 220L        // a freshly started player runs this long before its clock takes over
        const val CROSSFADE_MS = 120L
        const val START_LATENCY_MS = 30L    // MediaPlayer.start() to the position actually moving, roughly
        const val END_LEAD_MS = 250L        // the drums take over this long before the music file ends, so the beat never stalls
        const val DUCK_LEVEL = 0.6f         // the music under a spoken annotation
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("6step", Context.MODE_PRIVATE)
    val tracks: List<Track> = Track.loadAll(ctx)
    private val music = MusicPlayer(ctx)
    private val metronome = Metronome(ctx)
    private val speakerLazy = lazy { Speaker(ctx).also { s -> s.onSpeaking = { duckMusic(it) } } }
    private val speaker by speakerLazy
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var duckRamp: Runnable? = null

    /** where a track may start: the first element start at or after a dance beat; null after the end of the piece */
    var boundaryAfter: (Double) -> Double? = { null }
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

    private var clock: BeatClock? = null
    private var paused = true
    private var pausedAt = 0.0
    private var released = false
    private var trackOrder = ArrayList<Track>()
    private var trackIdx = -1
    private var nextChoice = -1                  // the track the user picked to come next (index in trackOrder), -1 for the order's own
    private var inGap = false                    // the drums between two tracks
    private var baseGrid: BeatGrid? = null       // the current track's grid as annotated
    private var grid: BeatGrid? = null           // the same grid at the tempo being played
    private var playingVariant: TrackVariant? = null
    private var pendingStart: Runnable? = null
    private var pendingSwitch: Runnable? = null
    private var pendingEnd: Runnable? = null     // the hand-over to the drums just before the music file ends
    private var handover: Handover? = null
    private var fade: Runnable? = null

    /** a hand-over in progress: the music clock that takes over once its player has settled, and the player fading out */
    private class Handover(val target: MusicClock, val oldPlayer: MediaPlayer?, val fire: Runnable)

    init { readSettings(); order() }

    val playing: Boolean get() = clock != null && !paused
    fun beatNow(): Double = if (paused) pausedAt else (clock?.beatNow() ?: pausedAt)
    /** the track playing, or the one the next start plays */
    fun currentTrack(): Track? = if (trackOrder.isEmpty()) null else trackOrder[maxOf(trackIdx, 0) % trackOrder.size]

    /** the tempo danced (BPM): what plays, or what the next start will play */
    fun bpm(): Double {
        if (playing) { (clock as? TempoClock)?.let { return it.bpm }; grid?.let { return it.bpm } }
        return currentTrack()?.variantFor(bpmOffset)?.bpm ?: 120.0
    }

    /** the settings page may have changed these since */
    fun readSettings() {
        overlay = prefs.getBoolean("metronome_over_music", false); speakOn = prefs.getBoolean("speak_annotations", true)
        metronome.snareOn = prefs.getBoolean("snare_on", true); metronome.hatOn = prefs.getBoolean("hihat_on", true)
        if (playing && !muted && !inGap) { metronome.enabled = overlay; metronome.resync() }
    }

    /** the chosen song first, then the others shuffled (all of them shuffled for "shuffle") */
    private fun order() {
        val chosen = tracks.firstOrNull { it.key == song }
        trackOrder = ArrayList<Track>().apply { if (chosen != null) add(chosen); addAll(tracks.filter { it !== chosen }.shuffled()) }
        trackIdx = -1; nextChoice = -1
    }

    private fun nextTrackIdx(): Int = if (nextChoice >= 0) nextChoice else trackIdx + 1
    private fun musicClockOf(c: BeatClock?): MusicClock? = when (c) { is MusicClock -> c; is SlewClock -> c.inner as? MusicClock; else -> null }
    private fun cancelPendingStart() { pendingStart?.let { handler.removeCallbacks(it) }; pendingStart = null }
    private fun cancelSwitch() { pendingSwitch?.let { handler.removeCallbacks(it) }; pendingSwitch = null; music.dropNext() }
    private fun cancelTrackEnd() { pendingEnd?.let { handler.removeCallbacks(it) }; pendingEnd = null }

    // ------------------------------------------------------------------ playing, pausing, moving

    /** play from dance beat b. The music goes on from there when the track that played covers it; otherwise a track
     *  starts with a count-in, its first bar on the even beat at or before b (the floor holds the dancers until the clock
     *  reaches b). Muted, a steady clock starts at b at once. */
    fun play(b: Double) {
        if (released || playing) return
        readSettings()
        cancelPendingStart(); cancelSwitch(); cancelTrackEnd(); completeHandover(slew = false)
        if (muted || trackOrder.isEmpty()) {
            music.pause(); metronome.stop()
            clock = TempoClock(bpm(), b, SystemClock.uptimeMillis()); paused = false
            return
        }
        if (!takeAudioFocus()) { onMessage?.invoke(ctx.getString(R.string.audio_busy)); return }
        val mc = musicClockOf(clock)
        val t = currentTrack()
        paused = false
        if (mc != null && music.player != null && t != null && trackIdx >= 0 && !inGap) {
            if (t.variantFor(bpmOffset).offset != playingVariant?.offset) { restartMusic(mc, b); goOn(b); return }   // the tempo changed meanwhile
            val sec = mc.grid.beatSeconds(b - mc.danceBeatOffset + mc.k0) + mc.grid.offset
            if (sec >= 0 && sec < mc.grid.duration - 1.0) {
                music.seekTo(sec); music.resume(); mc.resync(); clock = mc; armTrackEnd(mc, sec)
                goOn(b); return
            }
        }
        // a fresh start: the track that was playing (the next one after a track ran out, the first before anything played)
        val i = when { trackIdx < 0 -> 0; inGap -> nextTrackIdx(); else -> trackIdx }
        startTrack(i, if (b < 0) 0.0 else 2 * floor(b / 2), countIn = true)
    }

    private fun goOn(b: Double) {
        if (nextChoice >= 0) scheduleNextTrack(b, nextChoice)
        metronome.clock = clock; metronome.resync(); metronome.start()
    }

    /** stop where it is; the track and its place are kept for play */
    fun pause() {
        val c = clock ?: return
        if (paused) return
        pausedAt = c.beatNow(); paused = true
        cancelSwitch(); cancelTrackEnd(); completeHandover(slew = false)
        music.pause(); metronome.stop(); silence()
        cancelPendingStart()
    }

    /** move the dance to beat b while it plays: the music seeks when it covers b, the drums take over otherwise */
    fun jumpTo(b: Double) {
        val c = clock ?: return
        cancelSwitch(); completeHandover(slew = false)
        val now = SystemClock.uptimeMillis()
        val mc = musicClockOf(clock)
        if (mc != null && music.player != null && !muted) {
            val sec = mc.grid.beatSeconds(b - mc.danceBeatOffset + mc.k0) + mc.grid.offset
            if (sec >= 0 && sec < mc.grid.duration - 1.0) {
                music.seekTo(sec); mc.resync()
                clock = mc; armTrackEnd(mc, sec)
                if (nextChoice >= 0 && !paused) scheduleNextTrack(b, nextChoice)
            } else {
                clock = TempoClock(mc.grid.bpm, b, now); inGap = true; metronome.enabled = true; music.pause()
                cancelTrackEnd(); scheduleNextTrack(b)
            }
        } else {
            val tempo = (c as? TempoClock) ?: TempoClock(bpm(), b, now)
            tempo.reanchor(b, now); clock = tempo
            if (!muted) scheduleNextTrack(b)
        }
        if (paused) pausedAt = b
        metronome.clock = clock; metronome.resync()
        silence()
        if (!paused && !muted) switchVariant()   // a tempo change still on its way is planned afresh from here
    }

    // ------------------------------------------------------------------ the song, the tempo, mute

    /** the song chosen in the bar: while the music plays it comes next, at the next element start at least two beats
     *  ahead; otherwise the next play starts with it. Shuffle leaves the track that plays alone. */
    fun chooseSong(key: String) {
        song = key; prefs.edit().putString("song", key).apply()
        val chosen = tracks.firstOrNull { it.key == key }
        val cur = if (trackIdx >= 0) currentTrack() else null
        when {
            chosen == null -> { if (trackIdx < 0) order() }
            chosen === cur && !inGap -> { nextChoice = -1; if (playing && !muted) cancelPendingStart() }
            playing && !muted -> { nextChoice = trackOrder.indexOf(chosen); scheduleNextTrack(beatNow(), nextChoice) }
            else -> {
                order()
                // muted and playing: the steady clock takes the new song's tempo
                val tc = clock as? TempoClock
                val v = currentTrack()?.variantFor(bpmOffset)
                if (playing && tc != null && v != null) { tc.reanchor(tc.beatNow(), SystemClock.uptimeMillis()); tc.bpm = v.bpm }
            }
        }
        onChange?.invoke()
    }

    /** the session's tempo offset: kept for the next tracks; the music that plays switches over (switchVariant); the
     *  drums between two tracks, or the steady clock when muted, are re-paced at once */
    fun setBpmOffset(off: Int) {
        if (off == bpmOffset) return
        bpmOffset = off; prefs.edit().putInt("bpm_offset", off).apply()
        val tc = clock as? TempoClock; val t = currentTrack()
        if (playing && tc != null && t != null) {
            val v = t.variantFor(off)
            val b = tc.beatNow(); tc.reanchor(b, SystemClock.uptimeMillis()); tc.bpm = v.bpm
            baseGrid?.let { grid = it.scaled(v.tempo) }
            metronome.resync()
            if (!muted) scheduleNextTrack(b)   // the next track's start moves with the new pace
        }
        if (playing && !muted) switchVariant()   // paused: applied at the next play
        onChange?.invoke()
    }

    /** mute: silence at once, the dance going on at the same tempo; unmute: the drums at once and the music from the next
     *  element start at least two beats ahead (the track that played, from its beginning) */
    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m; prefs.edit().putBoolean("muted", m).apply()
        if (playing) {
            val b = beatNow()
            if (m) {
                val now = bpm()
                cancelPendingStart(); cancelSwitch(); cancelTrackEnd(); completeHandover(slew = false)
                music.pause(); metronome.stop(); silence()
                clock = TempoClock(now, b, SystemClock.uptimeMillis()); inGap = false
            } else if (takeAudioFocus()) {
                val tc = (clock as? TempoClock) ?: TempoClock(bpm(), b, SystemClock.uptimeMillis())
                tc.reanchor(b, SystemClock.uptimeMillis()); clock = tc
                metronome.clock = tc; metronome.swing = grid?.swing ?: Piece.SWING; metronome.enabled = true; metronome.resync(); metronome.start()
                inGap = true; nextChoice = maxOf(trackIdx, 0)   // the track that played comes back, after the drums
                scheduleNextTrack(b, nextChoice)
            } else onMessage?.invoke(ctx.getString(R.string.audio_busy))
        }
        onChange?.invoke()
    }

    // ------------------------------------------------------------------ the voice

    fun prefetch(texts: Collection<String>) { if (!muted && speakOn && texts.isNotEmpty()) speaker.prefetch(texts) }
    fun speak(text: String) { if (!muted && speakOn && playing) speaker.speak(text) }
    fun silence() { if (speakerLazy.isInitialized()) speaker.stop() }

    // ------------------------------------------------------------------ audio focus, and the voice over the music

    /** another app took the sound (a call, a prompt that wants it all): pause; the user presses play to go on. A loss
     *  that "may duck" is ignored: this app keeps playing at full volume, and having asked to be paused rather than
     *  ducked (setWillPauseWhenDucked) the system never dims it on its own, which would take the music and the voice
     *  down together. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (playing) onFocusLoss?.invoke() }
            else -> {}   // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: keep playing; AUDIOFOCUS_GAIN: no automatic resume
        }
    }

    /** the sound for this session: asked for at every play (after a loss the players stay muted until the app asks
     *  again); false when the phone will not give it, as during a call */
    private fun takeAudioFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, handler).build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** the music a little lower (DUCK_LEVEL) under a spoken annotation and back to full over 250 ms afterwards, so that
     *  music plus voice stays under full scale. The ramp only ever heads for a level, so a restore cannot be missed. */
    private fun duckMusic(speaking: Boolean) {
        if (released) return
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

    // ------------------------------------------------------------------ the music (the old player's, unchanged)

    /** the drums take over END_LEAD_MS before the file of `mc`, now at `posSec`, runs out (its completion callback would
     *  come late and the beat would stall meanwhile); the callback itself is then a no-op */
    private fun armTrackEnd(mc: MusicClock, posSec: Double) {
        cancelTrackEnd()
        val r = Runnable { pendingEnd = null; if (!paused && musicClockOf(clock) === mc) onTrackEnded() }
        pendingEnd = r
        handler.postAtTime(r, SystemClock.uptimeMillis() + ((mc.grid.duration - posSec) * 1000.0).toLong() - END_LEAD_MS)
    }

    /** start track i of the order so that its first dance bar is dance beat `atBeat`, at the tempo of the session's offset */
    private fun startTrack(i: Int, atBeat: Double, countIn: Boolean) {
        val t = trackOrder[i % trackOrder.size]; trackIdx = i; nextChoice = -1; pendingStart = null; inGap = false
        pendingSwitch?.let { handler.removeCallbacks(it) }; pendingSwitch = null   // a cued player is kept if it is this file
        completeHandover(slew = false); cancelTrackEnd()
        val v = t.variantFor(bpmOffset)
        val base = BeatGrid.loadFor(ctx, t); baseGrid = base
        val g = base.scaled(v.tempo); grid = g; playingVariant = v
        val k0 = g.startBeatIndex()
        val mp = music.next?.takeIf { music.nextPath == v.file } ?: music.prepareNext(v.file)   // opened early by scheduleNextTrack when it could be
        val startSec = g.beats[(k0 - (if (countIn) 4 else 0)).coerceAtLeast(0)]
        music.onComplete = { onTrackEnded() }
        val direct = clock == null || countIn
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
        onChange?.invoke()
    }

    /** the music is running out (END_LEAD_MS before the file ends, or its completion when that comes first): keep the beat
     *  going on the snare and hi-hat, start the next track at the next element start */
    private fun onTrackEnded() {
        if (released || paused || musicClockOf(clock) == null) return   // already on the drums
        cancelSwitch(); completeHandover(slew = false); cancelTrackEnd()
        val g = grid ?: return
        val b = beatNow()
        val tempo = TempoClock(g.bpm, b, SystemClock.uptimeMillis())
        clock = tempo; inGap = true; metronome.clock = tempo; metronome.enabled = true; metronome.resync()
        scheduleNextTrack(b)
        onChange?.invoke()
    }

    /** start track `nextIdx` of the order at the first element start at least 2 beats ahead of `fromBeat` */
    private fun scheduleNextTrack(fromBeat: Double, nextIdx: Int = nextTrackIdx()) {
        val c = clock ?: return
        cancelPendingStart()
        if (muted) return
        val next = boundaryAfter(fromBeat + 2.0) ?: return
        val delay = (c.uptimeOfBeat(next) - SystemClock.uptimeMillis() - 90).coerceAtLeast(0L)
        val r = Runnable { if (!paused && !released) startTrack(nextIdx, next, countIn = false) }
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
        if (paused || v.offset == playingVariant?.offset) return
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
            onMessage?.invoke(ctx.getString(R.string.tempo_next_track, v.bpm.roundToInt()))
            return
        }
        val mp = try { music.prepareNext(v.file) } catch (e: Exception) { return }
        music.setVolume(mp, 0f); music.seekTo(mp, sec)
        val r = Runnable {
            pendingSwitch = null
            if (paused || clock !== c) { music.dropNext(); return@Runnable }
            val old = music.swapToNext()
            mp.start()
            grid = g; playingVariant = v
            crossfade(old, mp)
            beginHandover(MusicClock(mp, g, mc.k0, mc.danceBeatOffset), old, HANDOVER_MS)
            onChange?.invoke()
        }
        pendingSwitch = r
        handler.postAtTime(r, c.uptimeOfBeat(b) - START_LATENCY_MS)
    }

    /** the current track again on the variant of the session's offset, from dance beat b (a play after the tempo was
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
            clock = TempoClock(g.bpm, b, SystemClock.uptimeMillis()); inGap = true; metronome.enabled = true; scheduleNextTrack(b)
        }
    }

    /** the music of `target` has just started: its clock takes over after `afterMs`, the difference faded out over 1.2 s */
    private fun beginHandover(target: MusicClock, oldPlayer: MediaPlayer?, afterMs: Long) {
        completeHandover(slew = false)
        val r = Runnable { if (!released) completeHandover(slew = true) }
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

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null); duckRamp = null
        handover?.oldPlayer?.let { music.releasePlayer(it) }; handover = null
        music.release(); metronome.release()
        if (speakerLazy.isInitialized()) speaker.release()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }; focusRequest = null
    }
}
