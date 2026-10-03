package com.dhruv.blackout

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Phase 0 spike launcher: setup shortcuts plus one button per test. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 15f }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(button("1. App info (Allow restricted settings)") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            })
            addView(button("2. Accessibility settings (turn Blackout on)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(button("Test: cover now (auto-removes after 90 s)") {
                BlackoutService.instance?.showCover()
            })
            addView(button("Test: Locked mode (lock + black screen)") {
                BlackoutService.instance?.lockAndBlack()
            })
        }
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        val connected = BlackoutService.instance != null
        status.text = "Accessibility service connected: $connected\n" +
            "Watch logcat: adb logcat -s BlackoutSpike"
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
}
