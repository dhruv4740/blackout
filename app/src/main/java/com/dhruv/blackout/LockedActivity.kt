package com.dhruv.blackout

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Phase 0 spike test 2: black screen shown over the REAL keyguard, kept awake.
 * Tap -> the system's own fingerprint/PIN unlock via requestDismissKeyguard.
 */
class LockedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        window.setDecorFitsSystemWindows(false)

        val black = View(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { requestUnlock() }
        }
        setContentView(black)
        Log.i(BlackoutService.TAG, "LockedActivity created")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun requestUnlock() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        Log.i(BlackoutService.TAG, "requestDismissKeyguard (locked=${km.isDeviceLocked})")
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.i(BlackoutService.TAG, "keyguard dismissed -> finishing")
                finishAndRemoveTask()
            }

            override fun onDismissCancelled() {
                Log.i(BlackoutService.TAG, "keyguard dismiss cancelled (stays black)")
            }

            override fun onDismissError() {
                Log.i(BlackoutService.TAG, "keyguard dismiss error")
            }
        })
    }
}
