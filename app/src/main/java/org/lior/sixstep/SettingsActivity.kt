package org.lior.sixstep

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings (the draft leaves the page empty): what used to be switches on the old main screen and chips in the old
 *  player's bar. The dance floor's mute button silences everything at once; these choose what is heard when it does not. */
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
        bind(R.id.setOverlay, "metronome_over_music", false)
        bind(R.id.setSnare, "snare_on", true)
        bind(R.id.setHihat, "hihat_on", true)
        bind(R.id.setVoice, "speak_annotations", true)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.about).text = getString(R.string.about, version)
    }
}
