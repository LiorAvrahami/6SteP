package org.lior.sixstep.web

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.lior.sixstep.BeatGrid
import org.lior.sixstep.CompositionStore
import org.lior.sixstep.DanceData
import org.lior.sixstep.DrumPattern
import org.lior.sixstep.FloorPlayer
import org.lior.sixstep.Json
import org.lior.sixstep.KnownSong
import org.lior.sixstep.MusicPlan
import org.lior.sixstep.OnsetCurve
import org.lior.sixstep.Texts
import org.lior.sixstep.Track
import org.lior.sixstep.TrackVariant
import kotlin.math.max
import kotlin.math.min

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

/** The page's files. The dance data, the tracks and their grids are read when the page opens. The music starts coming
 *  in that same first second (user 2026-11-21: "you should start loading it the second that the app opens"), in the
 *  background: every file is fetched behind whatever the page is doing and kept on the device (the browser's cache
 *  for pages, the Cache API; in memory where a browser has none). A file that is needed before it has come is fetched
 *  at once; when its background fetch is already under way, the two wait for the one download.
 *
 *  A song that the listener added from their own files (the song menu's "Upload", user 2026-11-21) is kept on the
 *  device too: its file in a cache of its own (`6step-user-1`, under the name "user/<song>"; it never leaves the
 *  device), and in the page's storage which song it is and how much later its music starts than in the mapped
 *  recording (`user_songs`). When the page opens, every such song whose file is still there becomes a track again,
 *  after the bundled ones. A browser may throw the file away when the device runs short of room (an iPhone's after a
 *  week without a visit): then the song is simply gone from the menu, and can be added again. */
class WebAssets {
    companion object {
        const val AT_ONCE = 2       // music files fetched side by side in the background
        const val USER = "user/"    // where a song of the listener's own is kept; never fetched from the net
    }

    /** a song of the listener's own: which known song it is, how much later its music starts, how long the file is */
    class UserSong(val key: String, val shift: Double, val seconds: Double)

    lateinit var data: DanceData
    lateinit var store: CompositionStore
    lateinit var tracks: List<Track>
    val grids = HashMap<String, BeatGrid>()
    /** the songs the page knows the beats of but does not bring: the listener can add their own copy */
    var known: List<KnownSong> = emptyList()
        private set
    private val curves = HashMap<String, OnsetCurve>()
    private var userCache: dynamic = null
    private var noUserCache = false

    private val caches: dynamic = try { window.asDynamic().caches } catch (e: Throwable) { null }
    private var cache: dynamic = null
    private var noCache = caches == null || jsTypeOf(caches) == "undefined"
    private val memory = HashMap<String, ArrayBuffer>()                           // a browser without a page cache: the files themselves
    private val coming = HashMap<String, ArrayList<(ArrayBuffer?) -> Unit>>()     // a file being read or fetched now, and who waits for it
    private val queue = ArrayList<String>()                                       // music still to fetch in the background, in order
    private var busy = 0                                                          // background fetches under way

    private fun text(url: String, done: (String?) -> Unit) {
        window.fetch(url).then({ r -> if (r.ok) r.text().then({ done(it) }, { done(null) }) else done(null) }, { done(null) })
    }

    /** read everything but the music, all at once. `onTracks`: the tracks are known (the music can start coming);
     *  `done(null)`: everything is read, otherwise what could not be */
    fun load(onTracks: () -> Unit, done: (String?) -> Unit) {
        var sk: String? = null; var co: String? = null
        var tracksRead = false; var gridsLeft = -1; var songsRead = false
        var failed: String? = null; var finished = false
        fun check() {
            if (finished) return
            val f = failed
            if (f != null) { finished = true; done(f); return }
            val s = sk; val c = co
            if (s == null || c == null || !tracksRead || gridsLeft != 0 || !songsRead) return
            finished = true
            try { data = DanceData.parse(s); store = CompositionStore(c) } catch (e: Throwable) { done(e.message ?: "the dance data"); return }
            restoreUserSongs { done(null) }
        }
        text("dance/skeletons.json") { t -> if (t == null) failed = "dance/skeletons.json" else sk = t; check() }
        text("dance/compositions.json") { t -> if (t == null) failed = "dance/compositions.json" else co = t; check() }
        // the songs that can be added: the page works without the list
        text("dance/songs.json") { t -> known = if (t == null) emptyList() else try { KnownSong.parseAll(t) } catch (e: Throwable) { emptyList() }; songsRead = true; check() }
        text("dance/tracks.json") { t ->
            val list = if (t == null) null else try { Track.parseAll(t) } catch (e: Throwable) { null }
            if (list == null) { failed = "dance/tracks.json"; check(); return@text }
            tracks = list; tracksRead = true
            onTracks()
            gridsLeft = list.size
            if (gridsLeft == 0) check()
            for (tr in list) text(tr.gridPath) { g ->
                if (g == null) failed = tr.gridPath else try { grids[tr.key] = BeatGrid.parse(g) } catch (e: Throwable) { failed = tr.gridPath }
                gridsLeft--; check()
            }
        }
    }

    private fun withCache(then: (dynamic) -> Unit) {
        if (cache != null || noCache) { then(cache); return }
        try {
            caches.open("6step-music-1").then({ c: dynamic -> cache = c; then(c) }, { _: dynamic -> noCache = true; then(null) })
        } catch (e: Throwable) { noCache = true; then(null) }
    }

    /** the bytes of a file: at once when they are on the device (the page cache, or memory), otherwise fetched now and
     *  kept. Whoever asks for a file that is already on its way waits for that one fetch. */
    fun bytes(url: String, done: (ArrayBuffer?) -> Unit) {
        memory[url]?.let { done(it); return }
        if (url.startsWith(USER)) { userBytes(url, done); return }
        coming[url]?.let { it.add(done); return }
        val waiting = arrayListOf(done)
        coming[url] = waiting
        fun finish(ab: ArrayBuffer?) { coming.remove(url); for (w in waiting) w(ab) }
        fun fromNetwork(c: dynamic) {
            window.fetch(url).then({ r ->
                if (!r.ok) { finish(null); return@then }
                if (c != null) try { c.put(url, r.clone()) } catch (e: Throwable) {}
                r.arrayBuffer().then({ ab -> if (c == null) memory[url] = ab; finish(ab) }, { finish(null) })
            }, { finish(null) })
        }
        withCache { c ->
            if (c == null) { fromNetwork(null); return@withCache }
            c.match(url).then({ hit: dynamic ->
                if (hit != null && jsTypeOf(hit) != "undefined") hit.arrayBuffer().then({ ab: ArrayBuffer -> finish(ab) }, { _: dynamic -> fromNetwork(c) })
                else fromNetwork(c)
            }, { _: dynamic -> fromNetwork(c) })
        }
    }

    /** bring these music files to the device behind whatever the page is doing, in this order, a few at a time; files
     *  asked for again move to the front */
    fun fetchInBackground(urls: List<String>) {
        for (u in urls.reversed()) if (!u.startsWith(USER)) { queue.remove(u); queue.add(0, u) }   // the listener's own songs are on the device already
        pump()
    }

    private fun pump() {
        while (busy < AT_ONCE && queue.isNotEmpty()) {
            val url = queue.removeAt(0)
            if (memory.containsKey(url) || coming.containsKey(url)) continue   // here already, or on its way
            busy++
            val next = { busy--; pump() }
            withCache { c ->
                if (c == null) bytes(url) { next() }   // no page cache: the file itself is kept in memory
                else c.match(url).then({ hit: dynamic ->
                    if (hit != null && jsTypeOf(hit) != "undefined") next()   // on the device already: nothing to read
                    else bytes(url) { next() }
                }, { _: dynamic -> bytes(url) { next() } })
            }
        }
    }

    // ------------------------------------------------------------------ the listener's own songs

    private fun withUserCache(then: (dynamic) -> Unit) {
        if (userCache != null || noUserCache || noCache) { then(userCache); return }
        try {
            caches.open("6step-user-1").then({ c: dynamic -> userCache = c; then(c) }, { _: dynamic -> noUserCache = true; then(null) })
        } catch (e: Throwable) { noUserCache = true; then(null) }
    }

    private fun userBytes(url: String, done: (ArrayBuffer?) -> Unit) {
        withUserCache { c ->
            if (c == null) { done(null); return@withUserCache }
            c.match(url).then({ hit: dynamic ->
                if (hit != null && jsTypeOf(hit) != "undefined") hit.arrayBuffer().then({ ab: ArrayBuffer -> done(ab) }, { _: dynamic -> done(null) })
                else done(null)
            }, { _: dynamic -> done(null) })
        }
    }

    private fun readUserSongs(): List<UserSong> = try {
        val a = Json.obj(Prefs.str("user_songs", "{\"songs\":[]}")).arr("songs")
        (0 until a.size).map { i -> val o = a.obj(i); UserSong(o.str("key"), o.num("shift"), o.num("seconds")) }
    } catch (e: Throwable) { emptyList() }

    private fun writeUserSongs(list: List<UserSong>) {
        Prefs.put("user_songs", "{\"songs\":[" + list.joinToString(",") { "{\"key\":\"${it.key}\",\"shift\":${it.shift},\"seconds\":${it.seconds}}" } + "]}")
    }

    /** a song of the listener's own as a track: the known song's name and beat grid, the grid moved to where the music
     *  is in their file. It has one tempo: its own (user 2026-11-20: "added songs don't change tempo"). */
    private fun addUserTrack(k: KnownSong, u: UserSong, grid: BeatGrid) {
        val file = USER + k.key
        grids[k.key] = grid.shifted(u.shift, min(u.seconds, grid.duration + u.shift))
        val t = Track(k.key, k.title, k.artist, "", k.bpm, u.seconds, file, k.gridPath, k.difficulty, listOf(TrackVariant(0, k.bpm, 1.0, file)))
        tracks = tracks.filter { it.key != k.key } + t
    }

    /** when the page opens: the listener's songs whose files are still on the device become tracks again */
    private fun restoreUserSongs(done: () -> Unit) {
        val list = readUserSongs().filter { u -> known.any { it.key == u.key } }
        if (list.isEmpty()) { done(); return }
        var left = list.size
        val still = ArrayList<UserSong>()
        fun one() { if (--left == 0) { writeUserSongs(list.filter { it in still }); done() } }
        for (u in list) {
            val k = known.first { it.key == u.key }
            userBytes(USER + u.key) { ab ->
                if (ab == null) one()   // the browser has thrown the file away: the song is gone from the menu
                else text(k.gridPath) { g ->
                    if (g != null) try { addUserTrack(k, u, BeatGrid.parse(g)); still.add(u) } catch (e: Throwable) {}
                    one()
                }
            }
        }
    }

    /** the onset curve of a known song, read when it is first asked for */
    fun curve(k: KnownSong, done: (OnsetCurve?) -> Unit) {
        curves[k.key]?.let { done(it); return }
        text(k.onsetsPath) { t ->
            val c = if (t == null) null else try { OnsetCurve.parse(t) } catch (e: Throwable) { null }
            if (c != null) curves[k.key] = c
            done(c)
        }
    }

    /** the songs the listener has added */
    fun hasUserSong(key: String): Boolean = tracks.any { it.key == key && it.file.startsWith(USER) }

    /** Keep a file of the listener's as the known song k: the file on the device, the song among the tracks.
     *  `shift`: how many seconds later the music starts in the file than in the mapped recording; `seconds`: the
     *  file's length. done(true): it is kept for good; done(false): the device would not keep it, so it is one of the
     *  songs only until the page is closed; done(null): it could not be added at all. */
    fun addUserSong(k: KnownSong, bytes: ArrayBuffer, type: String, shift: Double, seconds: Double, done: (Boolean?) -> Unit) {
        val url = USER + k.key
        val u = UserSong(k.key, shift, seconds)
        text(k.gridPath) { g ->
            val grid = if (g == null) null else try { BeatGrid.parse(g) } catch (e: Throwable) { null }
            if (grid == null) { done(null); return@text }
            fun finish(kept: Boolean) {
                if (!kept) memory[url] = bytes
                addUserTrack(k, u, grid)
                if (kept) writeUserSongs(readUserSongs().filter { it.key != k.key } + u)
                done(kept)
            }
            withUserCache { c ->
                if (c == null) { finish(false); return@withUserCache }
                try {
                    val make: dynamic = js("(function (b, t) { return new Response(b, { headers: { 'Content-Type': t || 'application/octet-stream' } }); })")
                    c.put(url, make(bytes, type)).then({ _: dynamic ->
                        memory.remove(url)
                        // ask the browser not to throw the song away when the device runs short of room; it may say no
                        try { val st: dynamic = window.navigator.asDynamic().storage; if (st != null && jsTypeOf(st.persist) == "function") st.persist() } catch (e: Throwable) {}
                        finish(true)
                    }, { _: dynamic -> finish(false) })
                } catch (e: Throwable) { finish(false) }
            }
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
    override val canAddSongs: Boolean get() = assets.known.isNotEmpty()
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
        plan.song = Prefs.str("song", MusicPlan.ALL)
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
        preload()
    }

    override fun setBpmOffset(off: Int) {
        if (off == plan.bpmOffset) return
        plan.bpmOffset = off; Prefs.put("bpm_offset", off)
        again()
        preload()
    }

    override fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m; Prefs.put("muted", m)
        again()
    }

    /** a change while playing: a stop, and a play from the beat it is at */
    private fun again() { if (running) play(beatNow()) else onChange?.invoke() }

    /** the listener added a song (WebAssets.addUserSong): it is one of the tracks now */
    fun tracksChanged() {
        plan.tracks = assets.tracks
        again()
        preload()
    }

    /** every music file, in the order it is wanted on the device: the songs of the plan at the chosen tempo (the one
     *  that plays first first), then the other tempos, the nearest to the chosen one first. With the phone's data
     *  saver on, only the songs at the chosen tempo: the others come when they are asked for. */
    fun wanted(): List<String> {
        val first = plan.files().distinct()
        val saver = try { window.navigator.asDynamic().connection.saveData == true } catch (e: Throwable) { false }
        if (saver) return first
        val rest = tracks.flatMap { t -> t.variants.map { v -> Pair(kotlin.math.abs(v.offset - plan.bpmOffset), v.file) } }
            .sortedBy { it.first }.map { it.second }.filter { it !in first }.distinct()
        return first + rest
    }

    /** from the first second of the page, and again when the song or the tempo is changed: the song that will play
     *  first is fetched and unpacked, so that play starts at once, and all the other music is fetched behind it. What
     *  is asked for before it has come is fetched at once (music(), with the loading sign). */
    fun preload() {
        val files = wanted()
        assets.fetchInBackground(files)
        files.firstOrNull()?.let { f -> music(f) {} }
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
