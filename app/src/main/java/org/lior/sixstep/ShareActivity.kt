package org.lior.sixstep

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** The share page (user 2026-11-25: "I want this app to be easy for people to share between one another ... a QR code,
 *  and ... below the QR code there's the link to the web app ... if you click the link then it copies this link to
 *  your clipboard"): the web page's address (Texts.WEB_APP_URL) as a QR code (drawable/share_qr, made by
 *  tools/make_qr.py), as large as the screen allows, with a line above it (the same day: 'Above the QR code, write
 *  "Share this app with this QR code!"') and under it the address as a link that copies itself. Opened from the
 *  launcher's QR button; sideways, as the launcher. The web page's Share is the same. */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        Ui.bars(this, Ui.LAUNCHER_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.LAUNCHER_BG)
        val box = findViewById<View>(R.id.codeBox)
        box.background = Ui.box(this, Color.WHITE, ripple = false)
        val title = findViewById<View>(R.id.title)
        val link = findViewById<TextView>(R.id.link)
        link.text = Texts.WEB_APP_URL
        link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        link.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(Texts.APP_NAME, Texts.WEB_APP_URL))
            // Android 13 and later show their own note of what was copied; before them, a toast says it
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
        // the code as large as the screen allows, from 160 to 300 dp a side, the line above it and the link under it
        // (as the web page's Share.fit); set again after every layout, and only when it changes
        val root = findViewById<View>(R.id.root)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val dp = resources.displayMetrics.density
            val room = minOf(root.height - root.paddingTop - root.paddingBottom - title.height - link.height - (24 * dp).toInt(),
                root.width - root.paddingLeft - root.paddingRight)
            val side = room.coerceIn((160 * dp).toInt(), (300 * dp).toInt())
            val lp = box.layoutParams
            if (lp.width != side || lp.height != side) { lp.width = side; lp.height = side; box.layoutParams = lp }
        }
    }
}
