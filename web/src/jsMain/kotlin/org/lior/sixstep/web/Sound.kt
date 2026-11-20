package org.lior.sixstep.web

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.lior.sixstep.BeatGrid
import org.lior.sixstep.CompositionStore
import org.lior.sixstep.DanceData
import org.lior.sixstep.DrumPattern
import org.lior.sixstep.FloorPlayer
import org.lior.sixstep.MusicPlan
import org.lior.sixstep.Texts
import org.lior.sixstep.Track
import kotlin.math.max

// ------------------------------------------------------------------ the browser's sound system, as far as the page uses it

external class AudioContext {
    val currentTime: Double
    val destination: AudioNode
    val state: String
    fun createGain(): GainNode
    fun createBufferSource(): AudioBufferSourceNode
    fun decodeAudioData(data: ArrayBuffer, success: (AudioBuffer) -> Unit, failure: (dynamic) -> Unit): dynamic
    fun resume(): dynamic
}
external interface AudioNode { fun connect(destination: AudioNode): AudioNode }
external interface AudioParam {
    var value: Double
    fun setValueAtTime(value: Double, startTime: Double): AudioParam
    fun linearRampToValueAtTime(value: Double, endTime: Double): AudioParam
    fun cancelScheduledValues(startTime: Double): AudioParam
}
external interface GainNode : AudioNode { val gain: AudioParam }
external interface AudioBuffer { val duration: Double }
external interface AudioBufferSourceNode : AudioNode {
    var buffer: AudioBuffer?
    var onended: ((dynamic) -> Unit)?
    fun start(at: Double, offset: Double)
    fun start(at: Double)
    fun stop()
}

// ------------------------------------------------------------------ the files

/** The dance data, the tracks and their grids, read once when the page opens; and the music files, each fetched when it
 *  is first needed and, where the browser has a cache for pages (the Cache API), kept there. The rest of the music is
 *  fetched into that cache in the background, so that a song chosen later is already on the device. */
class WebAssets {
    lateinit var data: DanceData
    lateinit var store: CompositionStore
    lateinit var tracks: List<Track>
    val grids = HashMap<String, BeatGrid>()

    private val caches: dynamic = try { window.asDynamic().caches } catch (e: Throwable) { null }
    private var cache: dynamic = null
    private val queue = ArrayList<String>()   // music still to fetch in the background, in order
    private var fetching = false

    private fun text(url: String, done: (String?) -> Unit) {
        window.fetch(url).then({ r -> if (r.ok) r.text().then({ done(it) }, { done(null) }) else done(null) }, { done(null) })
    }

    /** read everything but the music; `done(null)` when it worked, otherwise what could not be read */
    fun load(done: (String?) -> Unit) {
        text("dance/skeletons.json") { sk ->
            if (sk == null) { done("dance/skeletons.json"); return@text }
            text("dance/compositions.json") { co ->
                if (co == null) { done("dance/compositions.json"); return@text }
                text("dance/tracks.json") { tr ->
                    if (tr == null) { done("dance/tracks.json"); return@text }
                    try {
                        data = DanceData.parse(sk); store = CompositionStore(co); tracks = Track.parseAll(tr)
                    } catch (e: Throwable) { done(e.message ?: "the dance data"); return@text }
                    var left = tracks.size
                    if (left == 0) { done(null); return@text }
                    var failed: String? = null
                    for (t in tracks) text(t.gridPath) { g ->
                        if (g == null) failed = t.gridPath else try { grids[t.key] = BeatGrid.parse(g) } catch (e: Throwable) { failed = t.gridPath }
                        if (--left == 0) done(failed)
                    }
                }
            }
        }
    }

    private fun withCache(then: (dynamic) -> Unit) {
        if (cache != null || caches == null || caches == undefined) { then(cache); return }
        try {
            caches.open("6step-music-1").then({ c: dynamic -> cache = c; then(c) }, { _: dynamic -> then(null) })
        } catch (e: Throwable) { then(null) }
    }

    private fun fetchBytes(url: String, store: dynamic, done: (ArrayBuffer?) -> Unit) {
        window.fetch(url).then({ r ->
            if (!r.ok) { done(null); return@then }
            if (store != null) try { store.put(url, r.clone()) } catch (e: Throwable) {}
            r.arrayBuffer().then({ done(it) }, { done(null) })
        }, { done(null) })
    }

    /** the bytes of a music file: from the page cache when it is there, otherwise fetched (and put there) */
    fun bytes(url: String, done: (ArrayBuffer?) -> Unit) {
        withCache { c ->
            if (c == null) { fetchBytes(url, null, done); return@withCache }
            c.match(url).then({ hit: dynamic ->
                if (hit != null && hit != undefined) hit.arrayBuffer().then({ ab: ArrayBuffer -> done(ab) }, { _: dynamic -> fetchBytes(url, c, done) })
                else fetchBytes(url, c, done)
            }, { _: dynamic -> fetchBytes(url, c, done) })
        }
    }

    /** fetch these music files into the page cache, one after the other, behind whatever the page is doing; files asked
     *  for again move to the front */
    fun fetchInBackground(urls: List<String>) {
        if (caches == null || caches == undefined) return   // nowhere to keep them: each is fetched when it is needed
        for (u in urls.reversed()) { queue.remove(u); queue.add(0, u) }
        pump()
    }

    private fun pump() {
        if (fetching || queue.isEmpty()) return
        fetching = true
        val url = queue.removeAt(0)
        withCache { c ->
            if (c == null) { fetching = false; queue.clear(); return@withCache }
            val next = { fetching = false; window.setTimeout({ pump() }, 150) }
            c.match(url).then({ hit: dynamic ->
                if (hit != null && hit != undefined) next()
                else window.fetch(url).then({ r -> if (r.ok) c.put(url, r).then({ _: dynamic -> next() }, { _: dynamic -> next() }) else next() }, { next() })
            }, { _: dynamic -> next() })
        }
    }
}

// ------------------------------------------------------------------ the player

/** The beat the dance floor dances to, in a browser: the same plan as the Android app's Playback (MusicPlan: every dance
 *  beat has one place in the audio), played through the browser's sound system, which starts a sound at an exact time
 *  on its own clock. So the music, the drums and the picture all read one clock, and nothing has to be estimated: a
 *  track starts at the sample its beat needs, the drums are scheduled a little ahead, and the picture shows the beat
 *  that is being heard (the clock less the delay of the sound's way out).
 *
 *  A song that is not on the device yet is fetched and unpacked first: `loading` is true meanwhile, and the dance waits
 *  on the step it starts from. Muted: a steady clock, no sound. The voice is the browser's own. */
class WebPlayer(private val assets: WebAssets) : FloorPlayer {
    companion object {
        const val START_DELAY = 0.06     // s: a sound is scheduled this far ahead, so that it starts exactly on time
        const val AHEAD = 0.15           // s: the drums are scheduled this far ahead
        const val TICK_MS = 25
        const val KEEP = 2               // unpacked songs kept in memory (about 60 MB each)
    }

    private val ctx = AudioContext()
    private val musicGain = ctx.createGain().also { it.connect(ctx.destination) }
    private val drumGain = ctx.createGain().also { it.connect(ctx.destination) }
    private val plan = MusicPlan(assets.tracks) { t -> assets.grids[t.key] ?: BeatGrid(t.bpm, 60.0 / t.bpm, 0.67, t.duration, DoubleArray(0), DoubleArray(0)) }
    private val buffers = LinkedHashMap<String, AudioBuffer>()                 // unpacked music, the last used last
    private val waiting = HashMap<String, ArrayList<(AudioBuffer?) -> Unit>>() // who waits for a file being unpacked
    private var snare: AudioBuffer? = null
    private var hihat: AudioBuffer? = null

    override val tracks: List<Track> get() = plan.tracks
    override var muted = Prefs.bool("muted", false)
        private set
    override val song: String get() = plan.song
    override val bpmOffset: Int get() = plan.bpmOffset
    override var loading = false
        private set
    override var boundaryAfter: (Double) -> Double?
        get() = plan.boundaryAfter
        set(v) { plan.boundaryAfter = v }
    override var onChange: (() -> Unit)? = null
    override var onFocusLoss: (() -> Unit)? = null
    override var onMessage: ((String) -> Unit)? = null

    private var overlay = false
    private var speakOn = true
    private var snareOn = true
    private var hatOn = true

    /** dance beats against time: the sound system's clock, or the page's own when muted (`wall`) */
    private abstract class Clock(val from: Double, val bpm: Double, val swing: Double, val wall: Boolean) {
        abstract fun beat(t: Double): Double
        abstract fun time(b: Double): Double
    }
    private class Steady(bpm: Double, val beat0: Double, from: Double, swing: Double, wall: Boolean) : Clock(from, bpm, swing, wall) {
        override fun beat(t: Double) = beat0 + (t - from) * bpm / 60.0
        override fun time(b: Double) = from + (b - beat0) * 60.0 / bpm
    }
    /** segment s sounding since `from`, where the file was at `offset` seconds */
    private class OnMusic(val s: MusicPlan.Segment, from: Double, val offset: Double) : Clock(from, s.grid.bpm, s.grid.swing, false) {
        override fun beat(t: Double) = s.beatAt(offset + (t - from))
        override fun time(b: Double) = from + (s.seconds(b) - offset)
    }

    // the playing, all of it forgotten by stop()
    private var running = false
    private var generation = 0
    private var startBeat = 0.0
    private var playFrom = 0.0                 // the time the first clock of this play starts at: before it, the beat is startBeat
    private var clock: Clock? = null
    private var segment: MusicPlan.Segment? = null
    private val sounding = ArrayList<AudioBufferSourceNode>()
    private val later = ArrayList<Pair<Double, () -> Unit>>()   // (a time on the sound system's clock, what happens then)
    private var drumsOn = false
    private var nextHit = Double.NaN
    private var timer: Int? = null
    private var speakGen = 0
    private var voicePrimed = false

    init {
        plan.song = Prefs.str("song", MusicPlan.SHUFFLE)
        plan.bpmOffset = Prefs.int("bpm_offset", 0)
        readSettings()
        sample("sounds/snare.wav") { snare = it }
        sample("sounds/hihat.wav") { hihat = it }
    }

    private fun sample(url: String, done: (AudioBuffer?) -> Unit) {
        assets.bytes(url) { ab -> if (ab == null) done(null) else decode(ab, done) }
    }

    private fun decode(ab: ArrayBuffer, done: (AudioBuffer?) -> Unit) {
        try { ctx.decodeAudioData(ab, { done(it) }, { done(null) }) } catch (e: Throwable) { done(null) }
    }

    /** a music file unpacked for playing: at once when it is in memory, otherwise fetched and unpacked first */
    private fun music(file: String, done: (AudioBuffer?) -> Unit) {
        buffers.remove(file)?.let { buffers[file] = it; done(it); return }
        waiting[file]?.let { it.add(done); return }
        waiting[file] = arrayListOf(done)
        fun finish(buf: AudioBuffer?) {
            if (buf != null) { buffers[file] = buf; while (buffers.size > KEEP) buffers.remove(buffers.keys.first()) }
            waiting.remove(file)?.forEach { it(buf) }
        }
        assets.bytes(file) { ab -> if (ab == null) finish(null) else decode(ab) { finish(it) } }
    }

    /** the delay of the sound's way out of the device, in seconds, as far as the browser tells */
    private fun latency(): Double {
        val out = number(ctx.asDynamic().outputLatency)
        if (out > 0.0 && out < 1.0) return out
        val base = number(ctx.asDynamic().baseLatency)
        return if (base > 0.0 && base < 1.0) base else 0.0
    }
    /** a value of the browser's as a number; not a number when it is something else (or missing) */
    private fun number(v: dynamic): Double = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else Double.NaN

    /** the first tap on the page lets the browser make sound at all: call this from the tap itself. The voice too
     *  must first speak from a tap on some phones, so it says nothing, once. */
    fun unlock() {
        try { if (ctx.state != "running") ctx.resume() } catch (e: Throwable) {}
        if (!voicePrimed) {
            voicePrimed = true
            try {
                val synth: dynamic = window.asDynamic().speechSynthesis
                if (synth != null && synth != undefined) { val u: dynamic = js("new SpeechSynthesisUtterance()"); u.text = " "; u.volume = 0; synth.speak(u) }
            } catch (e: Throwable) {}
        }
    }

    override val playing: Boolean get() = running
    override fun beatNow(): Double {
        val c = clock ?: return startBeat
        val t = if (c.wall) nowMs() / 1000.0 else ctx.currentTime - latency()
        return if (t < playFrom) startBeat else c.beat(t)
    }
    override fun currentTrack(): Track? = segment?.track ?: plan.order.firstOrNull()
    override fun swing(): Double? = currentTrack()?.let { assets.grids[it.key]?.swing }
    override fun bpm(): Double = clock?.bpm ?: segment?.grid?.bpm ?: plan.firstTempo()

    override fun readSettings() {
        overlay = Prefs.bool("metronome_over_music", false); speakOn = Prefs.bool("speak_annotations", true)
        snareOn = Prefs.bool("snare_on", true); hatOn = Prefs.bool("hihat_on", true)
        if (running && segment != null) drumsOn = overlay   // between two tracks the drums play anyway
    }

    // ------------------------------------------------------------------ playing and stopping

    override fun play(b: Double) {
        stop()
        readSettings()
        running = true; startBeat = b
        val gen = generation
        if (muted || tracks.isEmpty()) {
            val now = nowMs() / 1000.0
            playFrom = now
            clock = Steady(plan.tempoAt(b), b, now, 0.67, true)
        } else {
            unlock()
            val s = plan.segmentAt(b)
            if (s != null && b < s.until) {
                segment = s
                loading = buffers[s.file] == null
                music(s.file) { buf ->
                    if (gen != generation) return@music
                    loading = false
                    val t = ctx.currentTime + START_DELAY
                    playFrom = t
                    if (buf != null) sound(s, b, t, buf, gen, first = true)
                    else { segment = null; gap(b, s.grid.bpm, s.grid.swing, t, gen) }   // the file could not be had: the drums
                    onChange?.invoke()
                }
            } else {
                val t = ctx.currentTime + START_DELAY
                playFrom = t
                gap(b, plan.tempoAt(b), 0.67, t, gen)
            }
            timer = window.setInterval({ tick() }, TICK_MS)
        }
        onChange?.invoke()
    }

    /** segment s sounds from dance beat b at time t: the file starts at b's place in it, exactly then. When its file
     *  runs out the drums take over on the same beat, and the next track of the plan comes in on its own. */
    private fun sound(s: MusicPlan.Segment, b: Double, t: Double, buf: AudioBuffer, gen: Int, first: Boolean = false) {
        val off = max(0.0, s.seconds(b))
        val src = ctx.createBufferSource()
        src.buffer = buf; src.connect(musicGain); src.start(t, off)
        keep(src)
        val c = OnMusic(s, t, off)
        val enter = { segment = s; clock = c; drumsOn = overlay; nextHit = Double.NaN }
        if (first) enter() else at(t, gen) { enter(); onChange?.invoke() }
        val tEnd = c.time(s.until)
        at(tEnd, gen) {
            if (segment === s) { segment = null; gap(s.until, s.grid.bpm, s.grid.swing, tEnd, gen); onChange?.invoke() }
        }
        plan.nextAfter(b)?.let { n -> music(n.file) {} }   // the next track is made ready well before it is due
    }

    /** no music from dance beat b at time t: the snare and hi-hat at `bpm`, and the next track of the plan on its beat */
    private fun gap(b: Double, bpm: Double, swing: Double, t: Double, gen: Int) {
        val c = Steady(bpm, b, t, swing, false)
        clock = c; drumsOn = true; nextHit = Double.NaN
        val next = plan.nextAfter(b) ?: return
        music(next.file) { buf ->
            if (gen != generation || clock !== c || buf == null) return@music
            val due = c.time(next.from)
            val soonest = ctx.currentTime + START_DELAY
            if (due >= soonest) sound(next, next.from, due, buf, gen)
            else sound(next, c.beat(soonest), soonest, buf, gen)   // it came late: it joins where the dance is by then
        }
    }

    private fun keep(src: AudioBufferSourceNode) {
        sounding.add(src)
        src.onended = { sounding.remove(src) }
    }

    private fun at(time: Double, gen: Int, action: () -> Unit) {
        later.add(Pair(time) { if (gen == generation) action() })
        later.sortBy { it.first }
    }

    /** every 25 ms while playing: what is due by now, and the drum hits of the next 150 ms, each at its exact time */
    private fun tick() {
        if (!running) return
        val now = ctx.currentTime
        while (later.isNotEmpty() && later[0].first <= now + 0.005) later.removeAt(0).second()
        val c = clock ?: return
        if (c.wall || !drumsOn) return
        val b = c.beat(now)
        if (nextHit.isNaN() || nextHit < b - 1.0) nextHit = DrumPattern.nextAfter(b - 0.01, c.swing)
        var guard = 0
        while (guard++ < 8) {
            val due = c.time(nextHit)
            if (due > now + AHEAD) break
            val isSnare = DrumPattern.isSnare(nextHit)
            val buf = if (isSnare) snare else hihat
            // a drum that is switched off is simply not played; the pattern keeps its timing either way
            if (buf != null && (if (isSnare) snareOn else hatOn) && due >= playFrom - 0.001) {
                val src = ctx.createBufferSource()
                src.buffer = buf; src.connect(drumGain); src.start(max(due, now))
                keep(src)
            }
            nextHit = DrumPattern.nextAfter(nextHit, c.swing)
        }
    }

    override fun stop() {
        generation++
        timer?.let { window.clearInterval(it) }; timer = null
        later.clear()
        for (s in sounding.toList()) { s.onended = null; try { s.stop() } catch (e: Throwable) {} }
        sounding.clear()
        silence()
        try { musicGain.gain.cancelScheduledValues(ctx.currentTime); musicGain.gain.value = 1.0 } catch (e: Throwable) {}   // the next music starts at full volume
        clock = null; segment = null; running = false; loading = false; drumsOn = false; nextHit = Double.NaN
    }

    // ------------------------------------------------------------------ the song, the tempo, mute

    override fun chooseSong(key: String) {
        plan.song = key; Prefs.put("song", key)
        again()
    }

    override fun setBpmOffset(off: Int) {
        if (off == plan.bpmOffset) return
        plan.bpmOffset = off; Prefs.put("bpm_offset", off)
        again()
    }

    override fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m; Prefs.put("muted", m)
        again()
    }

    /** a change while playing: a stop, and a play from the beat it is at */
    private fun again() { if (running) play(beatNow()) else onChange?.invoke() }

    /** the music files to have on the device first: the songs of the plan at the chosen tempo, then every other tempo */
    fun wanted(): List<String> {
        val first = plan.files()
        val rest = tracks.flatMap { t -> t.variants.map { it.file } }.filter { it !in first }
        return first + rest
    }

    // ------------------------------------------------------------------ the voice

    override fun prefetch(texts: Collection<String>) {}   // the browser's voice needs nothing made ready

    override fun speak(text: String) {
        if (muted || !speakOn || !running || text.isBlank()) return
        val synth: dynamic = window.asDynamic().speechSynthesis
        if (synth == null || synth == undefined) return
        val gen = ++speakGen
        try {
            synth.cancel()
            val u: dynamic = js("new SpeechSynthesisUtterance()")
            u.text = Texts.spoken(text); u.lang = "en-US"
            u.onstart = { if (gen == speakGen) duck(true) }
            u.onend = { if (gen == speakGen) duck(false) }
            u.onerror = { if (gen == speakGen) duck(false) }
            synth.speak(u)
            // the music is back at the latest after about 15 characters a second plus a margin, whatever becomes of the phrase
            window.setTimeout({ if (gen == speakGen) duck(false) }, text.length * 90 + 2500)
        } catch (e: Throwable) { duck(false) }
    }

    private fun silence() {
        speakGen++
        try { val synth: dynamic = window.asDynamic().speechSynthesis; if (synth != null && synth != undefined) synth.cancel() } catch (e: Throwable) {}
    }

    /** the music a little lower under a spoken annotation, and back to full over a quarter of a second afterwards */
    private fun duck(speaking: Boolean) {
        try {
            val g = musicGain.gain; val now = ctx.currentTime
            g.cancelScheduledValues(now); g.setValueAtTime(g.value, now)
            g.linearRampToValueAtTime(if (speaking) MusicPlan.DUCK_LEVEL else 1.0, now + (if (speaking) 0.1 else 0.25))
        } catch (e: Throwable) {}
    }

    /** the floor is left: everything stops; the sound system stays for the next floor */
    override fun release() {
        stop()
        onChange = null; onFocusLoss = null; onMessage = null
        plan.boundaryAfter = { null }
    }
}
