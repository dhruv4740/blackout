package com.dhruv.blackout

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tap starts the black screen directly; when already armed, tap asks for auth instead. */
class BlackoutTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val s = BlackoutService.instance
        when {
            s == null -> launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Blackout.isArmed(this) -> launch(Intent(this, AuthActivity::class.java))
            else -> s.arm(dismissShade = true)
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Blackout.isArmed(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
