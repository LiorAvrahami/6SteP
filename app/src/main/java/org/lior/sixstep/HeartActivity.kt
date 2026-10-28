package org.lior.sixstep

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat

/** The heart page (the user's layout draft, revised 2026-10-28): a note from the user, and three links: an email to
 *  them, their website, and a beer (5, 15 or 30 dollars). Upright, as the user drew it (the rest of the app is sideways). */
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
        // square boxes and black outlines, as the user drew this page
        findViewById<View>(R.id.note).background = Ui.box(this, Ui.HEART_BOX, 2.5f, 0f, ripple = false)
        val mail = findViewById<ImageButton>(R.id.mail)
        val web = findViewById<ImageButton>(R.id.web)
        val money = findViewById<ImageButton>(R.id.money)
        for (b in listOf(mail, web, money)) { b.background = Ui.box(this, Color.TRANSPARENT, 3f, 0f); TooltipCompat.setTooltipText(b, b.contentDescription) }
        mail.setOnClickListener {
            open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")).putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name)))
        }
        web.setOnClickListener { if (WEBSITE.isEmpty()) notYet() else open(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE))) }
        money.setOnClickListener { beer() }
    }

    /** "Buy me a Beer" (user 2026-10-29): the title, the beer's words, the amounts, in the heart page's look */
    private fun beer() {
        val v = layoutInflater.inflate(R.layout.dialog_beer, null)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(v)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        v.background = Ui.box(this, Ui.HEART_BOX, 2.5f, 0f, ripple = false)
        v.findViewById<View>(R.id.beerClose).setOnClickListener { dialog.dismiss() }
        for ((id, amount) in listOf(R.id.beer5 to AMOUNTS[0], R.id.beer15 to AMOUNTS[1], R.id.beer30 to AMOUNTS[2])) {
            val b = v.findViewById<TextView>(id)
            b.text = "\$$amount"
            b.background = Ui.raised(this, Ui.PAGE_BG, 0f)
            b.setOnClickListener { if (PAY_URL.isEmpty()) notYet() else open(Intent(Intent.ACTION_VIEW, Uri.parse(PAY_URL.format(amount)))) }
        }
        dialog.show()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show() }
    }

    private fun notYet() { Toast.makeText(this, R.string.link_not_set, Toast.LENGTH_SHORT).show() }
}
