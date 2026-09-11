package com.exo.musicplayer.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.ui.DesktopApp
import com.exo.musicplayer.desktop.ui.LyricsWindow
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.ResonateDesktopTheme
import com.exo.musicplayer.desktop.ui.ScaledToWindow
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Windows entry point.
 *
 * Native decorations are kept deliberately: on Windows 11 the system title bar,
 * snap layouts and window animations are what "native" actually looks like, and
 * a hand-drawn title bar loses all three.
 */
fun main() = application {
    // The folder picker is Swing; matching the OS look keeps it from standing out.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    // Held here rather than inside AppHost so the controller outlives
    // recomposition and the window's key handler can reach it.
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopController(scope) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Resonate",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        // Window level, not view level: Ctrl+A has to work whether or not the
        // track table happens to hold focus, and a text field that owns the
        // keystroke still gets first refusal because it is a preview handler
        // only for keys we claim.
        onPreviewKeyEvent = { event ->
            when {
                event.type == KeyEventType.KeyDown &&
                    event.isCtrlPressed && event.key == Key.A -> {
                    controller.selectAll(controller.visibleTracks)
                    true
                }
                event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                    controller.hasSelection -> {
                    controller.clearSelection()
                    true
                }
                else -> false
            }
        }
    ) {
        // The smallest window the scaled layout still fits in.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 500) }
        ResonateDesktopTheme {
            ScaledToWindow(designWidth = 1180.dp, designHeight = 640.dp) {
                AppHost(controller)
            }
        }
    }

    if (controller.lyricsDetached) {
        Window(
            onCloseRequest = { controller.lyricsDetached = false },
            title = "Lyrics · Resonate",
            state = rememberWindowState(width = 460.dp, height = 700.dp)
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(300, 360) }
            ResonateDesktopTheme {
                ScaledToWindow(designWidth = 420.dp, designHeight = 560.dp) { LyricsWindow(controller) }
            }
        }
    }
}

@Composable
private fun AppHost(controller: DesktopController) {
    val status by controller.engine.status.collectAsState()

    // Shown once per launch, not on every navigation — a splash that reappears
    // when you switch tabs is an interruption, not an introduction.
    var booting by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.release() }
    }

    LaunchedEffect(Unit) {
        delay(1400)
        booting = false
    }

    // Roll on to the next track when one runs out.
    LaunchedEffect(status.playing, status.track) {
        val finished = status.track != null && !status.playing &&
            status.durationMs > 0 && status.positionMs >= status.durationMs - 1200
        if (finished) controller.advance()
    }

    Box(Modifier.fillMaxSize()) {
        DesktopApp(
            controller,
            onChooseFolder = ::chooseFolder,
            onChooseMedia = ::chooseMediaFile,
            onPickPlaylistFile = ::choosePlaylistFile
        )
        if (booting) BootScreen()
    }
}

@Composable
private fun BootScreen() {
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(500))
    LaunchedEffect(Unit) { visible = true }

    Box(
        Modifier.fillMaxSize().background(Palette.Base),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha)
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Palette.Accent))
            Spacer(Modifier.height(20.dp))
            Text(
                "Resonate",
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.Text
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "made by lucent",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim
            )
        }
    }
}

/**
 * File picker for identification.
 *
 * Deliberately unfiltered beyond a convenience filter: the fingerprinter takes
 * whatever ffmpeg can open, and a hard whitelist would refuse files that would
 * in fact have worked.
 */
private fun chooseMediaFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Choose audio or video to identify"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = true
        fileFilter = FileNameExtensionFilter(
            "Audio and video",
            "mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma", "aiff", "alac",
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "wmv", "mpg", "mpeg"
        )
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

/**
 * Open/save picker for shared playlists.
 *
 * Defaults to .txt because the whole point is a file that can be emailed or
 * pasted into a chat without anyone wondering what it is.
 */
private fun choosePlaylistFile(save: Boolean, suggested: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = if (save) "Export playlist" else "Import a playlist"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("Playlist text file", "txt", "m3u", "csv")
        if (save && suggested.isNotBlank()) {
            selectedFile = File(suggested.replace(Regex("""[\/:*?"<>|]"""), "_") + ".txt")
        }
    }
    val approved = if (save) {
        chooser.showSaveDialog(null)
    } else {
        chooser.showOpenDialog(null)
    } == JFileChooser.APPROVE_OPTION
    if (!approved) return null

    val chosen = chooser.selectedFile ?: return null
    return if (save && chosen.extension.isBlank()) File(chosen.absolutePath + ".txt") else chosen
}

private fun chooseFolder(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose a folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}
