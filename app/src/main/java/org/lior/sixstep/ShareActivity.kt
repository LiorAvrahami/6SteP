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
 *  tools/make_qr.py), and under it as a link that copies itself. Opened from the launcher's QR button; sideways, as
 *  the launcher. The web page's Share is the same. */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        Ui.bars(this, Ui.LAUNCHER_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.LAUNCHER_BG)
        findViewById<View>(R.id.codeBox).background = Ui.box(this, Color.WHITE, ripple = false)
        val link = findViewById<TextView>(R.id.link)
        link.text = Texts.WEB_APP_URL
        link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        link.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(Texts.APP_NAME, Texts.WEB_APP_URL))
            // Android 13 and later show their own note of what was copied; before them, a toast says it
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
