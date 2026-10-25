package com.typepilot.android.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.typepilot.android.TypePilotApp

/** Quick Settings tile: tap to pause TypePilot for an hour (or resume). */
class AssistantTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val app = TypePilotApp.instance
        val before = app.settings.value
        com.typepilot.android.telemetry.Usage.count("tile_used", mapOf("action" to when { !before.enabled -> "turn_on"; before.isPaused -> "resume"; else -> "pause" }))
        app.settings.update {
            when {
                !it.enabled -> it.copy(enabled = true, pausedUntil = 0)
                it.isPaused -> it.copy(pausedUntil = 0)
                else -> it.copy(pausedUntil = System.currentTimeMillis() + 3_600_000)
            }
        }
        render()
    }

    private fun render() {
        val s = TypePilotApp.instance.settings.value
        qsTile?.apply {
            state = if (s.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "TypePilot"
            subtitle = when { !s.enabled -> "Off"; s.isPaused -> "Paused 1 h"; else -> "Active" }
            updateTile()
        }
    }
}
