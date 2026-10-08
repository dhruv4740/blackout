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
        when {
            Blackout.isArmed(this) && BlackoutService.instance != null ->
                startActivity(Intent(this, AuthActivity::class.java))
            !Blackout.requestArm(this) -> {
                Toast.makeText(this, "Turn on the Blackout accessibility service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        finish()
    }
}
