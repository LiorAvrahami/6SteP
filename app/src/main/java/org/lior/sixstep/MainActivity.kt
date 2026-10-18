package org.lior.sixstep

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {
    private lateinit var store: CompositionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = CompositionStore(this)
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)

        findViewById<MaterialButton>(R.id.startDaily).setOnClickListener {
            val daily = store.daily() ?: return@setOnClickListener
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("composition", daily.name).putExtra("daily", true))
        }
        findViewById<MaterialButton>(R.id.explore).setOnClickListener { startActivity(Intent(this, ExploreActivity::class.java)) }
        findViewById<MaterialButton>(R.id.syncMusic).setOnClickListener { startActivity(Intent(this, SyncActivity::class.java)) }

        val metro = findViewById<MaterialSwitch>(R.id.metronomeOverMusic)
        metro.isChecked = prefs.getBoolean("metronome_over_music", false)
        metro.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("metronome_over_music", on).apply() }
        val speak = findViewById<MaterialSwitch>(R.id.speakAnnotations)
        speak.isChecked = prefs.getBoolean("speak_annotations", true)
        speak.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak_annotations", on).apply() }
    }

    override fun onResume() {
        super.onResume()
        store.load()
        val box = findViewById<LinearLayout>(R.id.compositionList)
        box.removeAllViews()
        val daily = store.daily()
        for (c in store.compositions) {
            if (daily != null && c.name == daily.name) continue
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.text = c.name
            b.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java).putExtra("composition", c.name).putExtra("daily", false)) }
            box.addView(b)
        }
        findViewById<MaterialButton>(R.id.startDaily).text = if (daily != null) getString(R.string.start_daily) + "  (" + daily.name + ")" else getString(R.string.start_daily)
    }
}
