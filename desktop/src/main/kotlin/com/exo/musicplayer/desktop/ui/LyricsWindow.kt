package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.DesktopController

/**
 * Lyrics in a window of their own, to move beside anything and size freely.
 *
 * The background effect keeps running while this window isn't focused, since
 * it mostly sits next to whatever has focus. Dock puts the lyrics back in the
 * side panel.
 */
@Composable
fun LyricsWindow(controller: DesktopController) {
    val status by controller.engine.status.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.Sidebar)) {
        Wallpaper(controller.wallpaper, controller.wallpaperDim, Modifier.matchParentSize())
        Backdrop(
            style = controller.backdrop,
            color = Palette.Stars,
            spectrum = controller.engine.spectrum,
            graph = controller.songGraph,
            beat = controller.engine.beat,
            reactiveMode = controller.reactiveMode,
            modifier = Modifier.matchParentSize(),
            count = 60,
            pauseWhenUnfocused = false
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.track?.title ?: "Lyrics",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Dock") { controller.togglePanel(SidePanelKind.LYRICS) }
            }
            Spacer(Modifier.height(10.dp))
            LyricsPanel(controller, status.track, status.positionMs)
        }
    }
}
