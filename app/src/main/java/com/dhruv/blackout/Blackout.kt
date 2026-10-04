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

    fun logFailure(c: Context) {
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
