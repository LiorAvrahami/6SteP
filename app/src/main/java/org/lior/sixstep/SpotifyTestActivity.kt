package org.lior.sixstep

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** A test page (opened from the launcher's "6SteP!" title, 2026-10-29): can the app follow Spotify's playback closely
 *  enough to dance on it? Play opens "Like It Is" in the user's own Spotify the first time, then plays and pauses
 *  Spotify directly. The big area records a row for every finger tap (the user taps along the beat), with Spotify's
 *  reported position, the clocks and everything else that may help; Spotify's own state changes and the play and pause
 *  presses are rows too. Every row is saved at once (filesDir); a long press offers the export to Downloads.
 *  Reading another app's playback needs notification access (MediaListener below). */
class SpotifyTestActivity : AppCompatActivity() {
    companion object {
        const val SPOTIFY = "com.spotify.music"
        const val TRACK_ID = "2ZhjHQR01kjPvHsK3nf4bk"
        const val TRACK_URL = "https://open.spotify.com/track/$TRACK_ID"
        const val TRACK_TITLE = "Like It Is"
        const val EXPORT_NAME = "like it is taps.csv"
        private const val WRITE_REQUEST = 7
    }

    private lateinit var log: TapLog
    private lateinit var pad: TapPad
    private lateinit var playBtn: ImageButton
    private lateinit var status: TextView
    private lateinit var accessPanel: View
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var listening = false
    private var spotify: MediaController? = null
    private var linkUsed = false
    private var access = false
    private var running = false
    private var outputs = ""
    private var route = ""
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList(), "sessions changed") }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { record("state"); showPlay() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { record("metadata") }
        override fun onSessionDestroyed() { record("session_destroyed"); drop(); showPlay() }
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { readOutputs(); record("output_changed") }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { readOutputs(); record("output_changed") }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            showStatus()
            main.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spotify_test)
        Ui.bars(this, Ui.LAUNCHER_BG)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.LAUNCHER_BG)
        linkUsed = savedInstanceState?.getBoolean("link_used") ?: false
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        readOutputs()

        log = TapLog(File(filesDir, "like_it_is_taps.csv"))
        audio.registerAudioDeviceCallback(devices, main)
        pad = findViewById(R.id.tapPad)
        pad.background = Ui.box(this, Color.WHITE, ripple = false)
        pad.hint = getString(R.string.tap_hint)
        pad.count = log.taps()
        pad.onTap = { e, i -> tap(e, i) }
        pad.onLongPress = { longPress() }

        playBtn = findViewById(R.id.play)
        status = findViewById(R.id.status)
        accessPanel = findViewById(R.id.accessPanel)
        val back = findViewById<ImageButton>(R.id.back)
        for (v in listOf<View>(playBtn, back)) v.background = Ui.box(this, Color.TRANSPARENT, 2.5f, 11f)
        back.setOnClickListener { finish() }
        playBtn.setOnClickListener { playOrPause() }
        findViewById<Button>(R.id.grantAccess).setOnClickListener { openAccessSettings() }
        findViewById<Button>(R.id.appInfo).setOnClickListener { openAppInfo() }

        record("page_opened", note = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); app ${version()}")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("link_used", linkUsed)
    }

    override fun onResume() {
        super.onResume()
        connect()
        showPlay()
        running = true
        main.post(tick)
    }

    // the callbacks stay on while Spotify is in front, so its state changes are recorded then too
    override fun onPause() { super.onPause(); running = false }

    override fun onDestroy() {
        super.onDestroy()
        audio.unregisterAudioDeviceCallback(devices)
        if (listening) try { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (e: Exception) {}
        spotify?.unregisterCallback(callback)
        log.close()
    }

    // ---------------------------------------------------------------- Spotify

    private fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /** find Spotify's media session, and follow it from now on */
    private fun connect() {
        val ok = hasAccess()
        access = ok
        accessPanel.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) return
        val m = sessions ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).also { sessions = it }
        val me = ComponentName(this, MediaListener::class.java)
        try {
            if (!listening) { m.addOnActiveSessionsChangedListener(sessionsChanged, me, main); listening = true }
            pick(m.getActiveSessions(me), "page resumed")
        } catch (e: SecurityException) {
            access = false
            accessPanel.visibility = View.VISIBLE
        }
    }

    private fun pick(list: List<MediaController>, why: String) {
        val found = list.firstOrNull { it.packageName == SPOTIFY }
        if (found?.sessionToken == spotify?.sessionToken) return
        drop()
        spotify = found
        found?.registerCallback(callback, main)
        record("session", note = if (found == null) "$why: no Spotify session" else "$why: Spotify session found")
        showPlay()
    }

    private fun drop() { spotify?.unregisterCallback(callback); spotify = null }

    private fun isPlaying(): Boolean {
        val s = spotify?.playbackState?.state ?: return false
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING
    }

    private fun isOurTrack(c: MediaController): Boolean {
        val m = c.metadata ?: return false
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        return title.equals(TRACK_TITLE, ignoreCase = true) || id.contains(TRACK_ID)
    }

    /** the first press opens the link; after that play and pause go straight to Spotify (it carries on where it was) */
    private fun playOrPause() {
        val c = spotify
        if (c != null && isPlaying()) {
            record("pause_press")
            c.transportControls.pause()
            playBtn.setImageResource(R.drawable.ic_play)
            return
        }
        if (c != null && (linkUsed || isOurTrack(c))) {
            record("play_press", note = "Spotify told to play")
            c.transportControls.play()
            playBtn.setImageResource(R.drawable.ic_pause)
            return
        }
        record("play_press", note = "opening the link")
        linkUsed = true
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(TRACK_URL))
            .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$packageName"))
        try {
            startActivity(Intent(i).setPackage(SPOTIFY))
        } catch (e: ActivityNotFoundException) {
            try { startActivity(i) } catch (e2: ActivityNotFoundException) { Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show() }
        }
        if (!hasAccess()) Toast.makeText(this, R.string.need_access, Toast.LENGTH_LONG).show()
    }

    private fun showPlay() {
        val p = isPlaying()
        playBtn.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (p) R.string.pause else R.string.play)
    }

    private fun showStatus() {
        val c = spotify
        if (!access) { status.text = getString(R.string.status_no_access); return }
        if (c == null) { status.text = getString(R.string.status_no_spotify, log.taps()); return }
        val m = c.metadata
        val s = c.playbackState
        val now = SystemClock.elapsedRealtime()
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "?"
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "?"
        val est = s?.let { estimate(it, now) } ?: -1L
        val reported = s?.position ?: -1L
        val age = s?.let { now - it.lastPositionUpdateTime } ?: 0L
        status.text = getString(R.string.status_line, title, artist, stateName(s?.state ?: -1), time(est), time(reported),
            age / 1000.0, s?.playbackSpeed ?: 0f, route, log.taps())
    }

    private fun estimate(s: PlaybackState, elapsed: Long): Long {
        if (s.position < 0) return s.position
        return if (s.state == PlaybackState.STATE_PLAYING) s.position + ((elapsed - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else s.position
    }

    private fun time(ms: Long): String = if (ms < 0) "?" else String.format(Locale.US, "%d:%06.3f", ms / 60000, (ms % 60000) / 1000.0)

    // ---------------------------------------------------------------- taps and rows

    private fun tap(e: MotionEvent, index: Int) {
        pad.count = log.taps() + 1
        record("tap", eventUptime = e.eventTime, x = e.getX(index), y = e.getY(index), pointers = e.pointerCount, tapNumber = pad.count)
    }

    private fun longPress() {
        pad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        log.relabelLastTap("long_press")
        pad.count = log.taps(); pad.invalidate()
        val items = arrayOf(getString(R.string.menu_export), getString(R.string.menu_clear), getString(R.string.menu_access), getString(R.string.menu_app_info))
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.menu_title, log.size(), log.taps())).setItems(items) { _, which ->
            when (which) {
                0 -> export()
                1 -> confirmClear()
                2 -> openAccessSettings()
                3 -> openAppInfo()
                else -> {}
            }
        }.show()
    }

    /** one row of the table; for a tap, eventUptime is the touch's own time (MotionEvent.eventTime, uptimeMillis) */
    private fun record(kind: String, eventUptime: Long? = null, x: Float? = null, y: Float? = null, pointers: Int? = null,
                       tapNumber: Int? = null, note: String = "") {
        val hUptime = SystemClock.uptimeMillis()
        val hElapsed = SystemClock.elapsedRealtime()
        val hWall = System.currentTimeMillis()
        val evUptime = eventUptime ?: hUptime
        val evElapsed = evUptime + (hElapsed - hUptime)
        val evWall = hWall - (hUptime - evUptime)
        val c = spotify
        val s = c?.playbackState
        val m = c?.metadata
        fun meta(key: String) = m?.getString(key) ?: ""
        val row = linkedMapOf(
            "kind" to kind,
            "tap_number" to (tapNumber?.toString() ?: ""),
            "wall_clock" to clock.format(Date(evWall)),
            "wall_ms" to evWall.toString(),
            "event_uptime_ms" to evUptime.toString(),
            "event_elapsed_ms" to evElapsed.toString(),
            "handler_uptime_ms" to hUptime.toString(),
            "handler_elapsed_ms" to hElapsed.toString(),
            "handler_wall_ms" to hWall.toString(),
            "spotify_position_at_event_ms" to (s?.let { estimate(it, evElapsed).toString() } ?: ""),
            "reported_position_ms" to (s?.position?.toString() ?: ""),
            "position_update_elapsed_ms" to (s?.lastPositionUpdateTime?.toString() ?: ""),
            "ms_since_position_update" to (s?.let { (evElapsed - it.lastPositionUpdateTime).toString() } ?: ""),
            "state" to (s?.let { stateName(it.state) } ?: ""),
            "speed" to (s?.playbackSpeed?.toString() ?: ""),
            "buffered_ms" to (s?.bufferedPosition?.toString() ?: ""),
            "actions" to (s?.actions?.toString() ?: ""),
            "title" to meta(MediaMetadata.METADATA_KEY_TITLE),
            "artist" to meta(MediaMetadata.METADATA_KEY_ARTIST),
            "album" to meta(MediaMetadata.METADATA_KEY_ALBUM),
            "duration_ms" to (m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.toString() ?: ""),
            "media_id" to meta(MediaMetadata.METADATA_KEY_MEDIA_ID),
            "player" to (c?.packageName ?: ""),
            "output_route" to route,
            "output_devices" to outputs,
            "music_volume" to "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",
            "x" to (x?.let { String.format(Locale.US, "%.0f", it) } ?: ""),
            "y" to (y?.let { String.format(Locale.US, "%.0f", it) } ?: ""),
            "pointers" to (pointers?.toString() ?: ""),
            "note" to note)
        log.add(row)
    }

    private fun stateName(s: Int): String = when (s) {
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_queue"
        else -> "unknown_$s"
    }

    /** the connected outputs, and a guess at where the music goes (Bluetooth over wired over the speaker) */
    private fun readOutputs() {
        val list = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs = list.joinToString(" | ") { "${typeName(it.type)}:${it.productName}" }
        val types = list.map { it.type }.toSet()
        route = when {
            types.any { it in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE headset */, 27 /* BLE speaker */) } -> "bluetooth"
            types.any { it in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET) } -> "wired"
            else -> "speaker"
        }
    }

    private fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        26 -> "ble_headset"
        27 -> "ble_speaker"
        else -> "type$t"
    }

    // ---------------------------------------------------------------- export, clear, settings

    private fun export() {
        if (Build.VERSION.SDK_INT < 29 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_REQUEST)
            return
        }
        val bytes = log.text().toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= 29) exportQ(bytes)
            else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, EXPORT_NAME).writeBytes(bytes)
            }
            Toast.makeText(this, getString(R.string.exported, EXPORT_NAME, log.size()), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    /** Android 10 and later: the Downloads collection; the file this app saved before is overwritten, not duplicated */
    @RequiresApi(29)
    private fun exportQ(bytes: ByteArray) {
        val r = contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        r.query(coll, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(EXPORT_NAME), null)?.use { cur ->
            if (cur.moveToFirst()) uri = android.content.ContentUris.withAppendedId(coll, cur.getLong(0))
        }
        val old = uri
        if (old != null) {
            try {
                r.openOutputStream(old, "wt")!!.use { it.write(bytes) }
                return
            } catch (e: Exception) {
                try { r.delete(old, null, null) } catch (e2: Exception) {}
            }
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val fresh = r.insert(coll, v) ?: throw IllegalStateException("no Downloads entry")
        r.openOutputStream(fresh, "w")!!.use { it.write(bytes) }
        v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        r.update(fresh, v, null, null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WRITE_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) export()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this).setMessage(getString(R.string.clear_confirm, log.size()))
            .setPositiveButton(R.string.clear_yes) { _, _ ->
                log.clear()
                pad.count = 0; pad.invalidate()
                record("page_opened", note = "table cleared; ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; app ${version()}")
            }
            .setNegativeButton(R.string.clear_no, null).show()
    }

    private fun openAccessSettings() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: ActivityNotFoundException) { openAppInfo() }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
}

/** The table of the test page: every row is appended to the file the moment it is made (on a background thread, in
 *  order), so nothing is lost if Android closes the app while Spotify is in front. The file is read back on start. */
class TapLog(private val file: File) {
    private val io = Executors.newSingleThreadExecutor()
    private var header: List<String> = emptyList()
    private val rows = mutableListOf<MutableList<String>>()

    init {
        if (file.exists()) {
            val all = parse(file.readText(Charsets.UTF_8))
            if (all.isNotEmpty()) { header = all[0]; all.drop(1).forEach { rows.add(it.toMutableList()) } }
        }
    }

    fun size() = rows.size
    fun taps(): Int { val k = header.indexOf("kind"); return if (k < 0) 0 else rows.count { it.getOrNull(k) == "tap" } }

    fun add(row: Map<String, String>) {
        val cols = listOf("row") + row.keys
        if (header.isEmpty()) header = cols
        val values = header.map { if (it == "row") (rows.size + 1).toString() else row[it] ?: "" }.toMutableList()
        rows.add(values)
        val line = values.joinToString(",") { esc(it) } + "\n"
        val head = header.joinToString(",") { esc(it) } + "\n"
        io.execute {
            if (!file.exists() || file.length() == 0L) file.writeText(head, Charsets.UTF_8)
            file.appendText(line, Charsets.UTF_8)
        }
    }

    /** the last tap was the start of a long press: keep the row, under another kind */
    fun relabelLastTap(kind: String) {
        val k = header.indexOf("kind"); if (k < 0) return
        val last = rows.lastOrNull { it.getOrNull(k) == "tap" } ?: return
        last[k] = kind
        val t = header.indexOf("tap_number"); if (t >= 0) last[t] = ""
        rewrite()
    }

    /** an empty table; the old one is kept beside it as a backup */
    fun clear() {
        io.execute { if (file.exists()) file.renameTo(File(file.parentFile, "like_it_is_taps_backup_${System.currentTimeMillis()}.csv")) }
        rows.clear()
    }

    fun text(): String = buildString {
        append(header.joinToString(",") { esc(it) }).append('\n')
        for (r in rows) append(r.joinToString(",") { esc(it) }).append('\n')
    }

    fun close() { io.shutdown() }

    private fun rewrite() { val t = text(); io.execute { file.writeText(t, Charsets.UTF_8) } }

    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun parse(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false }
                else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.setLength(0) }
                '\n' -> { row.add(cell.toString()); cell.setLength(0); out.add(row); row = mutableListOf() }
                '\r' -> {}
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); out.add(row) }
        return out
    }
}

/** The big tap area: a tap is taken the moment the finger touches (ACTION_DOWN, or another finger's ACTION_POINTER_DOWN),
 *  with the touch's own timestamp; a single finger held still is a long press. It flashes on every tap. */
class TapPad @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onTap: ((MotionEvent, Int) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var hint = ""
    var count = 0
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var flashAt = 0L
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TITLE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x55, 0x55, 0x55); textAlign = Paint.Align.CENTER; textSize = 18 * d }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = 56 * d; isFakeBoldText = true }
    private val rect = RectF()
    private val longPress = Runnable { onLongPress?.invoke() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                hit(e, 0)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> { removeCallbacks(longPress); hit(e, e.actionIndex) }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> { removeCallbacks(longPress); performClick() }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun hit(e: MotionEvent, index: Int) {
        onTap?.invoke(e, index)
        flashAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val a = 1f - (SystemClock.uptimeMillis() - flashAt) / 160f
        if (a > 0f) {
            val inset = 4 * d
            rect.set(inset, inset, width - inset, height - inset)
            flash.alpha = (max(0f, a) * 255).toInt()
            c.drawRoundRect(rect, 6 * d, 6 * d, flash)
            postInvalidateOnAnimation()
        }
        c.drawText(count.toString(), width / 2f, height / 2f, big)
        c.drawText(hint, width / 2f, height / 2f + 36 * d, text)
    }
}

/** Empty on purpose: Android shows other apps' media sessions (Spotify's position) only to an app whose notification
 *  listener the user has switched on. Nothing here reads notifications. */
class MediaListener : NotificationListenerService()
