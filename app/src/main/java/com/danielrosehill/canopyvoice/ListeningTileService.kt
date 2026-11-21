package com.danielrosehill.canopyvoice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile for the listening on/off ([Settings.listeningPaused]). Active means hands-free listening
 * is on. Pausing works from the tile alone; resuming with the wake word switched on opens the app, because
 * Android only lets a microphone foreground service start while the app is in the foreground.
 */
class ListeningTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val settings = Settings(this)
        val pause = !settings.listeningPaused
        Assistant.setPaused(this, pause)
        refresh()
        if (!pause && settings.wakeWordEnabled && !Assistant.wakeRunning.value) {
            if (isLocked) unlockAndRun { openApp() } else openApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_NO_LISTEN, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val paused = Settings(this).listeningPaused
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_yossi)
        tile.label = "Yossi"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (paused) "Paused" else "Listening"
        tile.contentDescription = if (paused) "Yossi listening paused" else "Yossi listening on"
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
