package org.lior.sixstep.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.lior.sixstep.KnownSong
import org.lior.sixstep.OnsetCurve
import org.lior.sixstep.Onsets
import org.lior.sixstep.Texts
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get
import kotlin.math.max
import kotlin.math.roundToInt

/** The window in which the listener adds a song of their own (the song menu's "Upload…", user 2026-11-21: "it will open
 *  a dialog in the same theme as the app that lists the supported tracks, and an open file dialogue where the user
 *  selects a local file to be used"). It lists the songs the page knows the beats of but does not bring, and has a
 *  button that opens the device's file chooser.
 *
 *  The chosen file never leaves the device. The page listens to it (the browser unpacks it, whatever kind of sound
 *  file it is), makes its onset curve (Onsets, a few frames at a time so that the page stays alive) and slides that
 *  along the curve of every known song. When it is one of them, the file is kept on the device (WebAssets.addUserSong)
 *  and the song is one of the listener's songs from then on, played on the known song's beat grid moved to where the
 *  music is in their copy. When it is none of them, the window says so and offers the email that asks for the song
 *  to be mapped (the user's words, 2026-11-19). `added`: told the song's key once it is among the tracks. */
class UploadWindow(private val added: (String) -> Unit) {
    private val m = Modal("beer upload")
    private val status = el("div", "status") { hidden = true }
    private val pick = el("button", "raised choose", Texts.CHOOSE_FILE)
    private val mail = el("a", "raised mail", Texts.WRITE_EMAIL) { hidden = true }
    private val input = (document.createElement("input") as HTMLInputElement).apply {
        type = "file"; accept = "audio/*,.mp3,.m4a,.aac,.ogg,.oga,.opus,.wav,.flac"; style.display = "none"
    }
    private val rows = HashMap<String, HTMLElement>()
    private var busy = false
    private var turn = 0   // each file chosen is a turn; closing the window ends the turn under way

    init {
        m.box.add(iconButton("close", Texts.CLOSE, "x press") { m.close() })
        m.box.add(el("div", "title", Texts.UPLOAD_TITLE))
        m.box.add(el("div", "words", Texts.UPLOAD_WORDS))
        val list = el("div", "songs")
        for (s in App.assets.known) {
            val row = el("div", "song") {
                add(el("span", "t", if (s.artist.isEmpty()) s.title else s.title + " · " + s.artist))
                add(el("span", "d", Texts.minutes(s.duration.roundToInt())))
                add(el("span", "have") { add(icon("check")); add(el("span", "", Texts.ADDED)) })
            }
            if (App.assets.hasUserSong(s.key)) row.classList.add("on")
            rows[s.key] = row
            list.add(row)
        }
        m.box.add(list)
        m.box.add(pick); m.box.add(input)
        m.box.add(status); m.box.add(mail)
        pick.onclick = { if (!busy) input.click(); null }
        input.onchange = {
            val f = input.files?.get(0)
            input.value = ""   // the same file can be chosen again
            if (f != null && !busy) take(f)
            null
        }
        m.onClose = { turn++; busy = false }
    }

    fun open() = m.open()

    private fun say(text: String) { status.textContent = text; status.hidden = false }

    private fun end(text: String) { busy = false; pick.removeAttribute("disabled"); say(text) }

    /** a file was chosen: read it, listen to it, and see which song it is */
    private fun take(file: File) {
        val mine = ++turn
        busy = true
        pick.setAttribute("disabled", "")
        mail.hidden = true
        say(Texts.listening(0))
        val reader = FileReader()
        reader.onerror = { if (mine == turn) end(Texts.SONG_UNREADABLE) }
        reader.onload = {
            val bytes = reader.result.unsafeCast<ArrayBuffer>()
            if (mine == turn) unpack(bytes) { samples, fs, seconds ->
                if (mine != turn) return@unpack
                if (samples == null || seconds < 20.0) { end(Texts.SONG_UNREADABLE); return@unpack }
                // the curve, a slice at a time: the page draws and takes touches in between
                val meter = Onsets.Meter(samples, fs)
                fun work() {
                    if (mine != turn) return
                    val t0 = nowMs()
                    while (!meter.step(32) && nowMs() - t0 < 30.0) { /* another slice */ }
                    say(Texts.listening(meter.done * 100 / max(1, meter.frames)))
                    if (meter.done < meter.frames) window.setTimeout({ work() }, 0)
                    else compare(meter.curve(), bytes, file, seconds, mine)
                }
                work()
            }
        }
        reader.readAsArrayBuffer(file)
    }

    /** the file's sound as one channel at 48 kHz (the rate every curve is made at), and how long it is */
    private fun unpack(bytes: ArrayBuffer, done: (FloatArray?, Int, Double) -> Unit) {
        try {
            val w: dynamic = window
            val kind: dynamic = if (jsTypeOf(w.OfflineAudioContext) != "undefined") w.OfflineAudioContext else w.webkitOfflineAudioContext
            val make: dynamic = js("(function (C, fs) { return new C(1, 1, fs); })")
            val ctx: dynamic = make(kind, Onsets.FS)
            val copy = bytes.asDynamic().slice(0)   // unpacking takes the bytes away; the file itself is kept as it is
            var told = false
            val ok = { buf: dynamic ->
                if (!told) {
                    told = true
                    val n = (buf.length as Int); val channels = (buf.numberOfChannels as Int)
                    val out = FloatArray(n)
                    for (c in 0 until channels) {
                        val data = buf.getChannelData(c).unsafeCast<FloatArray>()
                        for (i in 0 until n) out[i] += data[i]
                    }
                    if (channels > 1) for (i in 0 until n) out[i] /= channels
                    done(out, (buf.sampleRate as Number).toInt(), buf.duration as Double)
                }
            }
            val bad = { _: dynamic -> if (!told) { told = true; done(null, 0, 0.0) } }
            val p: dynamic = ctx.decodeAudioData(copy, ok, bad)
            if (p != null && jsTypeOf(p) != "undefined" && jsTypeOf(p.then) == "function") p.then({ _: dynamic -> }, bad)
        } catch (e: Throwable) { done(null, 0, 0.0) }
    }

    /** the curves of the known songs, then: which of them is this file? */
    private fun compare(curve: OnsetCurve, bytes: ArrayBuffer, file: File, seconds: Double, mine: Int) {
        val known = App.assets.known
        val book = ArrayList<Pair<KnownSong, OnsetCurve>>()
        var left = known.size
        if (left == 0) { unknown(file, null, emptyList()); return }
        for (k in known) App.assets.curve(k) { c ->
            if (c != null) book.add(Pair(k, c))
            if (--left == 0 && mine == turn) {
                val fits = Onsets.fits(curve, book)
                val found = Onsets.recognised(fits)
                if (found == null) unknown(file, Onsets.soundsLike(fits), fits)
                else App.assets.addUserSong(found.first, bytes, file.type, found.second.shift, seconds) { kept ->
                    if (mine != turn) return@addUserSong
                    if (kept == null) { end(Texts.SONG_NOT_KEPT); return@addUserSong }
                    App.player.tracksChanged()
                    rows[found.first.key]?.classList?.add("on")
                    end(if (kept) Texts.songAdded(found.first.title) else Texts.songForNow(found.first.title))
                    report(found.first.key, found.second, "")
                    added(found.first.key)
                }
            }
        }
    }

    /** not a recording the page knows (`like`: a known song that it sounds like all the same): the email that asks for
     *  it to be mapped */
    private fun unknown(file: File, like: KnownSong?, fits: List<Pair<KnownSong, Onsets.Fit>>) {
        val name = file.name.substringBeforeLast('.').ifBlank { file.name }
        val enc = { s: String -> window.asDynamic().encodeURIComponent(s) as String }
        mail.setAttribute("href", "mailto:" + enc(Texts.EMAIL) + "?subject=" + enc(Texts.APP_NAME) + "&body=" + enc(Texts.mapRequest(name)))
        mail.hidden = false
        end(if (like != null) Texts.songOtherVersion(like.title) else Texts.SONG_UNKNOWN)
        val best = fits.maxByOrNull { it.second.score }
        report("", best?.second, like?.key ?: "")
    }

    /** what came of the last file, for whoever tests the page from outside (window.sixstepUpload): the song it is
     *  (key, empty when none), how the best candidate fits, and the song it only sounds like */
    private fun report(key: String, fit: Onsets.Fit?, like: String) {
        val o: dynamic = js("({})")
        o.key = key; o.like = like; o.score = fit?.score; o.shift = fit?.shift; o.whole = fit?.whole; o.cover = fit?.cover
        window.asDynamic().sixstepUpload = o
    }
}
