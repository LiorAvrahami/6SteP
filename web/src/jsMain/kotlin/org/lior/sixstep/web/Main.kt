package org.lior.sixstep.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.lior.sixstep.Piece
import org.lior.sixstep.Texts
import org.w3c.dom.HTMLElement

/** The page: it reads the dance data, then shows one screen at a time, chosen by the address's "#" part, so that the
 *  browser's own back button goes back a screen:
 *    (nothing)                            the launcher
 *    #explore   #settings   #heart
 *    #floor/<kind>/<name>/<variant>       the dance floor on a piece; "/play" after it starts it playing at once */
object App {
    val assets = WebAssets()
    lateinit var player: WebPlayer
    private var current: Screen? = null
    private var depth = 0          // how many screens deep we are from where the page was opened
    private val host: HTMLElement get() = document.getElementById("app") as HTMLElement

    fun start() {
        Display.start()
        assets.load { failed ->
            if (failed != null) { host.textContent = ""; host.appendChild(el("div", "boot", "6SteP could not read $failed. Check the connection and reload the page.") { style.fontSize = "18px"; style.padding = "24px"; style.textAlign = "center" }); return@load }
            player = WebPlayer(assets)
            window.addEventListener("hashchange", { route() })
            // the font first, so that the first drawing already has it; never longer than a moment
            var routed = false
            val go = { if (!routed) { routed = true; route() } }
            try { document.asDynamic().fonts.load("700 20px Inter").then({ _: dynamic -> document.asDynamic().fonts.load("800 13px Inter") }).then({ _: dynamic -> document.asDynamic().fonts.load("600 10px Inter") }).then({ _: dynamic -> go() }, { _: dynamic -> go() }) } catch (e: Throwable) { go() }
            window.setTimeout({ go() }, 1500)
            // the rest of the music comes to the device in the background
            window.setTimeout({ assets.fetchInBackground(player.wanted()) }, 4000)
        }
    }

    private fun enc(s: String): String = window.asDynamic().encodeURIComponent(s) as String
    private fun dec(s: String): String = try { window.asDynamic().decodeURIComponent(s) as String } catch (e: Throwable) { s }

    fun floorPath(kind: String, name: String, variant: Int, autoplay: Boolean): String =
        "floor/$kind/${enc(name)}/$variant" + (if (autoplay) "/play" else "")

    /** to another screen */
    fun go(path: String) { depth++; window.location.hash = path }

    /** back a screen; the launcher when the page was opened on this one */
    fun back() { if (depth > 0) { depth--; window.history.back() } else window.location.hash = "" }

    private fun route() {
        val path = window.location.hash.removePrefix("#").removePrefix("/")
        val parts = path.split('/')
        val next: Screen = when {
            parts[0] == "explore" -> Explore()
            parts[0] == "settings" -> Settings()
            parts[0] == "heart" -> Heart()
            parts[0] == "floor" && parts.size >= 4 -> FloorScreen(parts[1], dec(parts[2]), parts[3].toIntOrNull() ?: 0, parts.getOrNull(4) == "play")
            else -> Launcher()
        }
        current?.detach()
        host.textContent = ""
        host.appendChild(next.root)
        current = next
        window.scrollTo(0.0, 0.0)
        document.title = if (next is FloorScreen) "${dec(parts.getOrElse(2) { "" })} · ${Texts.APP_NAME}" else Texts.APP_NAME
        next.attach()
    }
}

fun main() {
    App.start()
}
