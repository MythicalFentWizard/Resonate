package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.AppVersion
import com.exo.musicplayer.desktop.data.AppDirs
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.ProxyMode
import com.exo.musicplayer.desktop.system.DuckKey
import kotlin.math.roundToInt
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Settings, in one place.
 *
 * Sound lives in the Effects panel and output in the Output panel, both a click
 * from the transport bar — this covers the things you set once: where the music
 * is, where downloads go, and where the weather comes from.
 */
@Composable
fun SettingsScreen(controller: DesktopController, onChooseFolder: () -> File?) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Music folders") {
                AccentButton("Add folder", icon = Icons.Default.FolderOpen) {
                    onChooseFolder()?.let { controller.addFolder(it) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Hint("Resonate reads these folders. Your files are never copied or moved.")
            Spacer(Modifier.height(12.dp))

            if (controller.folders.isEmpty()) {
                Hint("None yet.")
            } else {
                controller.folders.forEach { path ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.Content)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.FolderOpen, null,
                            Modifier.size(14.dp), tint = Palette.TextFaint
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .clickable { controller.removeFolder(path) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close, "Remove",
                                Modifier.size(13.dp), tint = Palette.TextDim
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Downloads")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    controller.downloadDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                GhostButton("Open") { controller.openMusicFolder() }
                Spacer(Modifier.width(6.dp))
                GhostButton("Change") {
                    onChooseFolder()?.let { controller.chooseDownloadDir(it) }
                }
            }
            Spacer(Modifier.height(12.dp))
            CheckRow(
                label = "Write tags and cover art into files",
                checked = controller.writeTags,
                note = "Off means identified names and covers stay inside Resonate " +
                    "and your files aren't modified"
            ) { controller.writeTags = it }
        }

        Spacer(Modifier.height(14.dp))

        ProxySettings(controller)

        Spacer(Modifier.height(14.dp))

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Appearance")
            Spacer(Modifier.height(4.dp))
            Hint(
                "Recolours the whole window, not just the buttons. Each one is a published " +
                    "scheme - the surfaces and the text tones come from it together, which is " +
                    "what keeps every label readable on every layer."
            )
            Spacer(Modifier.height(14.dp))
            var editingColours by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Fixed-width cells, and the short last row padded out with empty
                // ones: a swatch used to be as wide as its own label, so "Amethyst"
                // and "Rose" made different columns and the bottom row sat offset.
                AccentChoice.entries.chunked(SWATCHES_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.forEach { option ->
                            AccentSwatch(option, option == controller.accent) {
                                controller.accent = option
                                if (option == AccentChoice.CUSTOM) editingColours = true
                            }
                        }
                        repeat(SWATCHES_PER_ROW - row.size) { Spacer(Modifier.width(SWATCH_CELL)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Hint(controller.accent.credit)
            Spacer(Modifier.height(12.dp))
            GhostButton("Edit colours", icon = Icons.Default.ColorLens) { editingColours = true }
            Spacer(Modifier.height(6.dp))
            Hint("Theme colours, the background effect's colour, and the lyrics' current and other lines.")
            if (editingColours) ThemeEditorWindow(controller) { editingColours = false }

            Spacer(Modifier.height(18.dp))
            Text("Background", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Spacer(Modifier.height(8.dp))
            SegmentedRow(
                options = BackdropStyle.entries,
                selected = controller.backdrop,
                label = { it.label },
                onSelect = { controller.backdrop = it }
            )
            if (controller.backdrop == BackdropStyle.REACTIVE) {
                Spacer(Modifier.height(8.dp))
                SegmentedRow(
                    options = ReactiveMode.entries,
                    selected = controller.reactiveMode,
                    label = { it.label },
                    onSelect = { controller.reactiveMode = it }
                )
            }
            Spacer(Modifier.height(6.dp))
            Hint(
                if (controller.backdrop == BackdropStyle.NONE) {
                    controller.backdrop.note
                } else {
                    controller.backdrop.note +
                        (if (controller.backdrop == BackdropStyle.REACTIVE) " " + controller.reactiveMode.note else "") +
                        " Shown behind the sidebar, the library and the " +
                        "lyrics, and paused while the window isn't focused."
                }
            )

            Spacer(Modifier.height(18.dp))
            Text("Wallpaper", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Spacer(Modifier.height(4.dp))
            Hint("A picture of your own behind everything, with the background effect drawn over it.")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton(
                    if (controller.wallpaper == null) "Choose picture" else "Change picture",
                    icon = Icons.Default.Image
                ) { chooseImage()?.let { controller.setWallpaper(it) } }
                if (controller.wallpaper != null) {
                    Spacer(Modifier.width(8.dp))
                    GhostButton("Remove") { controller.clearWallpaper() }
                }
            }
            controller.wallpaperNote?.let { note ->
                Spacer(Modifier.height(6.dp))
                Hint(note)
            }
            if (controller.wallpaper != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Dim ${(controller.wallpaperDim * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim
                )
                Slider(
                    value = controller.wallpaperDim,
                    onValueChange = { controller.wallpaperDim = it },
                    valueRange = 0f..0.9f,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.Accent,
                        activeTrackColor = Palette.Accent,
                        inactiveTrackColor = Palette.Line
                    )
                )
                Hint("Darkens the picture so text stays easy to read.")
            }
        }

        Spacer(Modifier.height(14.dp))

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Game ducking")
            Spacer(Modifier.height(4.dp))
            Hint(
                "Turns the music down system-wide at the press of a key, so you can hear " +
                    "what the game is doing without alt-tabbing. Works while the game has " +
                    "focus."
            )
            Spacer(Modifier.height(12.dp))

            CheckRow(
                label = "Duck the music with a hotkey",
                checked = controller.duckEnabled,
                note = "Press once to turn down, again to restore"
            ) { controller.duckEnabled = it }

            if (controller.duckEnabled) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        modifier = Modifier.width(64.dp)
                    )
                    SegmentedRow(
                        options = DuckKey.entries,
                        selected = controller.duckKey,
                        label = { it.label },
                        onSelect = { controller.duckKey = it }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Only these keys are offered: a global hotkey is taken from every " +
                        "other program too, so binding a letter would break typing " +
                        "everywhere.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Turn down by",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        modifier = Modifier.width(94.dp)
                    )
                    Text(
                        "${controller.duckPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Accent,
                        modifier = Modifier.width(46.dp)
                    )
                    Slider(
                        value = controller.duckPercent.toFloat(),
                        onValueChange = { controller.duckPercent = it.toInt() },
                        valueRange = 10f..100f,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = Palette.Accent,
                            activeTrackColor = Palette.Accent,
                            inactiveTrackColor = Palette.Line
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                controller.duckError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        WeatherSettings(controller)

        Spacer(Modifier.height(14.dp))

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("About")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Resonate for Windows",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Text
                    )
                    Hint("version ${AppVersion.name} · made by lucent")
                }
                GhostButton("Contact") { openLink("https://t.me/Eth4wn") }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Data folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim
                    )
                    Text(
                        AppDirs.root.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                GhostButton("Open") { openFolder(AppDirs.root) }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun AccentSwatch(choice: AccentChoice, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(SWATCH_CELL),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (choice == AccentChoice.CUSTOM) Palette.custom.accent else choice.accent)
                .border(
                    2.dp,
                    if (selected) Palette.Text else Color.Transparent,
                    RoundedCornerShape(9.dp)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Default.Check, null, Modifier.size(17.dp), tint = if (choice == AccentChoice.CUSTOM) Palette.custom.onAccent else choice.onAccent)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            choice.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Palette.Text else Palette.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Every swatch takes the same width, whatever its name is, so the rows line up. */
private val SWATCH_CELL = 78.dp
private const val SWATCHES_PER_ROW = 6

@Composable
private fun WeatherSettings(controller: DesktopController) {
    var search by remember { mutableStateOf("") }

    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Weather")
        Spacer(Modifier.height(4.dp))
        Hint(
            "Moods needs to know what the sky is doing. Windows has no GPS, so " +
                "Resonate works your city out from your IP address — or you can name it. " +
                "Conditions come from Open-Meteo, which needs no account."
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Palette.Content)
                    .border(1.dp, Palette.Line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    controller.weatherPlace.ifBlank { "Located from your IP address" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Text
                )
            }
            Spacer(Modifier.width(10.dp))
            GhostButton("Use my IP", icon = Icons.Default.MyLocation) {
                controller.useIpLocation()
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(
                value = search,
                onValueChange = { search = it },
                placeholder = "Or type a city",
                leading = Icons.Default.Search,
                modifier = Modifier.weight(1f),
                onSubmit = { controller.searchPlaces(search) }
            )
            Spacer(Modifier.width(10.dp))
            GhostButton("Find", enabled = search.isNotBlank()) {
                controller.searchPlaces(search)
            }
        }

        if (controller.placeResults.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                controller.placeResults.forEach { place ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.Content)
                            .clickable {
                                controller.usePlace(place)
                                search = ""
                            }
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                    ) {
                        Text(
                            place.display,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim
                        )
                    }
                }
            }
        }
    }
}

private fun openLink(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private fun openFolder(dir: File) {
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
            Desktop.getDesktop().open(dir)
        }
    }
}

/**
 * The proxy for everything Resonate does online.
 *
 * One address box and one port box rather than a URL field: the kind of proxy
 * is already chosen above it, and a pasted "socks5://" would only be something
 * to strip back off.
 */
@Composable
private fun ProxySettings(controller: DesktopController) {
    val proxy = controller.proxy
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Proxy")
        Spacer(Modifier.height(4.dp))
        Hint(
            "Used for everything that goes online: yt-dlp and spotdl downloads, YouTube " +
                "search and previews, lyrics, cover art, identification and weather."
        )
        Spacer(Modifier.height(12.dp))
        SegmentedRow(
            options = ProxyMode.entries,
            selected = proxy.mode,
            label = { it.label },
            onSelect = { controller.proxy = controller.proxy.copy(mode = it) }
        )
        Spacer(Modifier.height(10.dp))
        when (proxy.mode) {
            ProxyMode.SYSTEM ->
                Hint("Follows Windows: Settings, Network & internet, Proxy.")
            ProxyMode.DIRECT ->
                Hint("Connects straight to the internet, even if Windows has a proxy set.")
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(
                        value = proxy.host,
                        onValueChange = { controller.proxy = controller.proxy.copy(host = it.trim()) },
                        placeholder = "Address, like 127.0.0.1",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextInput(
                        value = if (proxy.port == 0) "" else proxy.port.toString(),
                        onValueChange = { typed ->
                            val digits = typed.filter { it.isDigit() }.take(5)
                            controller.proxy = controller.proxy.copy(port = digits.toIntOrNull() ?: 0)
                        },
                        placeholder = "Port",
                        modifier = Modifier.width(110.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Hint(
                    when {
                        proxy.port > 65535 ->
                            "Ports go up to 65535. Until then Resonate connects directly."
                        proxy.incomplete ->
                            "Fill in both the address and the port. Until then Resonate connects directly."
                        proxy.mode == ProxyMode.HTTPS ->
                            "Reaches the proxy the same way as HTTP and tunnels secure sites through it."
                        proxy.mode == ProxyMode.SOCKS5 ->
                            "Recommended: carries every kind of connection Resonate makes."
                        else ->
                            "Applied straight away to every new connection."
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Test connection") { controller.testProxy() }
            controller.proxyTestNote?.let { note ->
                Spacer(Modifier.width(10.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}

/** Windows' own open dialog, filtered to pictures. */
private fun chooseImage(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a wallpaper", java.awt.FileDialog.LOAD)
    dialog.file = "*.jpg;*.jpeg;*.png;*.webp;*.bmp"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
