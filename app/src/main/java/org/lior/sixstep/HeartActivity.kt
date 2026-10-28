package org.lior.sixstep

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The heart page (the user's layout draft, 2026-10-28): a note from the user, and three links: an email to them, their
 *  website, and a beer (5, 15 or 30 dollars). */
class HeartActivity : AppCompatActivity() {
    companion object {
        const val EMAIL = "lior.2pi@gmail.com"
        /** the user's own pages, still to be given: until then a tap says the link is not set up yet */
        const val WEBSITE = ""
        const val PAY_URL = ""          // with %d for the amount in dollars
        val AMOUNTS = intArrayOf(5, 15, 30)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_heart)
        Ui.bars(this, Ui.PAGE_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.PAGE_BG)
        findViewById<View>(R.id.note).background = Ui.box(this, Ui.HEART_BOX, ripple = false)
        findViewById<View>(R.id.beer).background = Ui.box(this, Ui.HEART_BOX, ripple = false)
        val mail = findViewById<ImageButton>(R.id.mail)
        val web = findViewById<ImageButton>(R.id.web)
        val money = findViewById<ImageButton>(R.id.money)
        for (b in listOf(mail, web, money)) { b.background = Ui.box(this, Color.TRANSPARENT); TooltipCompat.setTooltipText(b, b.contentDescription) }
        mail.setOnClickListener {
            open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")).putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name)))
        }
        web.setOnClickListener { if (WEBSITE.isEmpty()) notYet() else open(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE))) }
        money.setOnClickListener {
            val labels = AMOUNTS.map { "\$$it" }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle(R.string.buy_beer).setItems(labels) { _, i ->
                if (PAY_URL.isEmpty()) notYet() else open(Intent(Intent.ACTION_VIEW, Uri.parse(PAY_URL.format(AMOUNTS[i]))))
            }.show()
        }
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show() }
    }

    private fun notYet() { Toast.makeText(this, R.string.link_not_set, Toast.LENGTH_SHORT).show() }
}
