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
        when {
            Blackout.isArmed(this) && BlackoutService.instance != null -> launch(Intent(this, AuthActivity::class.java))
            !Blackout.requestArm(this, dismissShade = true) -> launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = Blackout.isArmed(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (on) "On · tap to unlock" else "Off"
        t.updateTile()
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
