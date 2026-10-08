package com.dhruv.blackout

import android.content.Context

/** Persisted state shared by the service, tile, widget and activities. */
object Blackout {
    private fun p(c: Context) = c.getSharedPreferences("blackout", Context.MODE_PRIVATE)

    fun isArmed(c: Context) = p(c).getBoolean("armed", false)
    fun setArmed(c: Context, v: Boolean) = p(c).edit().putBoolean("armed", v).apply()

    fun alarmFloor(c: Context) = p(c).getBoolean("alarmFloor", true)
    fun setAlarmFloor(c: Context, v: Boolean) = p(c).edit().putBoolean("alarmFloor", v).apply()

    /** Alarm volume to restore on disarm, or -1 when we did not change it. */
    fun savedAlarmVolume(c: Context) = p(c).getInt("savedAlarmVol", -1)
    fun setSavedAlarmVolume(c: Context, v: Int) = p(c).edit().putInt("savedAlarmVol", v).apply()

    /**
     * With WRITE_SECURE_SETTINGS (granted once over adb) the accessibility service is switched on
     * only while armed, so banking apps that refuse to run beside an enabled service work otherwise.
     */
    fun canManageService(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun setPending(c: Context, shade: Boolean?) =
        p(c).edit().apply { if (shade == null) remove("pendingShade") else putBoolean("pendingShade", shade) }.apply()

    /** Consumes a queued arm request: null if none, else whether to dismiss the shade. */
    fun takePendingArm(c: Context): Boolean? {
        if (!p(c).contains("pendingShade")) return null
        val v = p(c).getBoolean("pendingShade", false)
        setPending(c, null)
        return v
    }

    /** Arms now if the service is up; otherwise queues the arm and switches the service on. */
    fun requestArm(c: Context, dismissShade: Boolean = false): Boolean {
        BlackoutService.instance?.let { it.arm(dismissShade); return true }
        if (!canManageService(c)) return false
        val cn = android.content.ComponentName(c, BlackoutService::class.java).flattenToString()
        val cr = c.contentResolver
        val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        setPending(c, dismissShade)
        if (cur.split(":").none { it == cn }) {
            android.provider.Settings.Secure.putString(
                cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (cur.isEmpty()) cn else "$cur:$cn"
            )
        }
        android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        return true
    }

    fun unplugAlert(c: Context) = p(c).getBoolean("unplugAlert", true)
    fun setUnplugAlert(c: Context, v: Boolean) = p(c).edit().putBoolean("unplugAlert", v).apply()

    /** Lifetime count of failed unlock attempts (never cleared). */
    fun failTotal(c: Context) = p(c).getInt("failTotal", 0)

    fun logFailure(c: Context) {
        p(c).edit().putInt("failTotal", failTotal(c) + 1).apply()
        val list = p(c).getString("failures", "").orEmpty()
        val trimmed = (list.split(",").filter { it.isNotEmpty() } + System.currentTimeMillis()).takeLast(50)
        p(c).edit().putString("failures", trimmed.joinToString(",")).apply()
    }

    /** Number of failed unlock attempts since the last successful unlock; clears the log. */
    fun takeFailures(c: Context): Int {
        val n = p(c).getString("failures", "").orEmpty().split(",").count { it.isNotEmpty() }
        p(c).edit().remove("failures").apply()
        return n
    }
}
