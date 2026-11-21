package org.lior.sixstep

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings (the draft leaves the page empty): what used to be switches on the old main screen and chips in the old
 *  player's bar. The dance floor's mute button silences everything at once; these choose what is heard when it does not.
 *  Above them, one switch that is not about sound: whether a swipe while a composition plays leads in with rock triple
 *  triples (user 2026-11-21: "make this feature toggleable in the settings so that it's possible to disable it"). */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Ui.bars(this, Ui.LAUNCHER_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.LAUNCHER_BG)
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        fun bind(id: Int, key: String, fallback: Boolean) {
            val s = findViewById<MaterialSwitch>(id)
            s.background = Ui.box(this, Color.WHITE, ripple = false)
            s.isChecked = prefs.getBoolean(key, fallback)
            s.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        bind(R.id.setLeadIn, "lead_in", true)   // the rock triple triples after a swipe while a composition plays (DanceFloorActivity.leadIn)
        bind(R.id.setOverlay, "metronome_over_music", false)
        bind(R.id.setSnare, "snare_on", true)
        bind(R.id.setHihat, "hihat_on", true)
        bind(R.id.setVoice, "speak_annotations", true)
        // the dance floor's hints from the beginning: every flag down again (user 2026-11-21: "add a button in the
        // settings to reset the hints")
        findViewById<TextView>(R.id.hintsAgain).let { again ->
            again.background = Ui.box(this, Color.WHITE)
            again.setOnClickListener {
                val e = prefs.edit()
                for (h in Hint.values()) e.remove(h.flag)
                e.apply()
                Toast.makeText(this, R.string.hints_again_done, Toast.LENGTH_SHORT).show()
            }
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.about).text = getString(R.string.about, version)
    }
}
