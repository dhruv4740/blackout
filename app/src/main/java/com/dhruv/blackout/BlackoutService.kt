package com.dhruv.blackout

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Phase 0 spike. Owns the black cover window (TYPE_ACCESSIBILITY_OVERLAY, same flags as the
 * open-source ar11-g/BlackScreenApp reference) and logs facts the design depends on.
 * Everything interesting goes to logcat under [TAG].
 */
class BlackoutService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null

    // Spike-only safety net so a failed unlock test can never lock the phone owner out.
    private val safetyTimeout = Runnable {
        Log.w(TAG, "safety timeout: removing cover")
        hideCover()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            Log.i(
                TAG,
                "${intent.action} isDeviceLocked=${km.isDeviceLocked} " +
                    "isKeyguardLocked=${km.isKeyguardLocked} cover=${cover != null}"
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Spike test: learn which packages/classes an alarm or incoming call shows.
            Log.i(TAG, "window pkg=${event.packageName} cls=${event.className}")
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hideCover()
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
        return super.onUnbind(intent)
    }

    fun showCover() {
        if (cover != null) return
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            // Spike test 3: tapping asks for biometric auth while the cover STAYS UP.
            setOnClickListener {
                Log.i(TAG, "cover tapped -> launching AuthActivity")
                startActivity(
                    Intent(this@BlackoutService, AuthActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            screenBrightness = 0f
        }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
        cover = view
        view.post { view.windowInsetsController?.let(::hideBars) }
        handler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
        Log.i(TAG, "cover shown")
    }

    fun hideCover() {
        handler.removeCallbacks(safetyTimeout)
        cover?.let {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
            cover = null
            Log.i(TAG, "cover hidden")
        }
    }

    /** Spike test 2 (Locked mode): real lock, then a black showWhenLocked activity. */
    fun lockAndBlack() {
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        Log.i(TAG, "GLOBAL_ACTION_LOCK_SCREEN accepted=$ok")
        handler.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, LockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Log.i(TAG, "LockedActivity start requested")
            }.onFailure { Log.e(TAG, "LockedActivity start failed", it) }
        }, 800)
    }

    private fun hideBars(c: WindowInsetsController) {
        c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val TAG = "BlackoutSpike"
        private const val SAFETY_TIMEOUT_MS = 90_000L

        @Volatile
        var instance: BlackoutService? = null
            private set
    }
}
