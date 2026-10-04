package com.dhruv.blackout

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** No-UI entry point for the widget and shortcut: arm, or ask for auth if already armed. */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = BlackoutService.instance
        when {
            s == null -> {
                Toast.makeText(this, "Turn on the Blackout accessibility service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Blackout.isArmed(this) -> startActivity(Intent(this, AuthActivity::class.java))
            else -> s.arm()
        }
        finish()
    }
}
