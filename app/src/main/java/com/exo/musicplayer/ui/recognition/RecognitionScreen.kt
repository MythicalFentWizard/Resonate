package com.exo.musicplayer.ui.recognition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.exo.musicplayer.data.recognition.MusicMatch
import com.exo.musicplayer.data.recognition.RecognitionResult
import com.exo.musicplayer.data.youtube.PreviewState
import com.exo.musicplayer.data.youtube.YouTubeVideo

/** The header, the mode chips and the search inputs: the items before any result. */
private const val HEADER_ITEMS = 3

/**
 * Identify.
 *
 * The whole screen is one scrolling list, header included. It used to be a
 * fixed header over a list of its own, and on a phone that header (title, a
 * paragraph of explanation, two file buttons, two search boxes, a hint and the
 * mode chips) took about four fifths of the screen, leaving room for one
 * result, sometimes two. Now the header scrolls away with the results, and when
 * a fresh set arrives the list moves to it, so the screen goes to results and
 * the search boxes are one short scroll back up.
 */
@Composable
fun RecognitionScreen(
    stage: RecognitionStage,
    result: RecognitionResult?,
    query: String,
    sourceLabel: String?,
    onQueryChange: (String) -> Unit,
    artist: String,
    onArtistChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    onSearch: () -> Unit,
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onFindInLibrary: (MusicMatch) -> Unit,
    onCopy: (MusicMatch) -> Unit,
    onDownload: (MusicMatch) -> Unit,
    youtubeResults: List<YouTubeVideo>,
    youtubeStatus: String?,
    preview: PreviewState,
    onPreviewYouTube: (YouTubeVideo) -> Unit,
    onDownloadYouTube: (YouTubeVideo) -> Unit,
    /** Several picked results at once: catalogue matches or YouTube videos. */
    onDownloadMany: (matches: List<MusicMatch>, videos: List<YouTubeVideo>) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Identifies a set of results, so that arriving at a new one can be told
    // apart from recomposing the same one.
    val resultsKey: Any? = when {
        mode == SearchMode.YOUTUBE ->
            youtubeResults.takeIf { it.isNotEmpty() }?.let { it.first().id to it.size }
        result is RecognitionResult.Found ->
            result.matches.takeIf { it.isNotEmpty() }?.let { it.first().display to it.size }
        else -> null
    }
    LaunchedEffect(resultsKey) {
        if (resultsKey != null) listState.animateScrollToItem(HEADER_ITEMS)
    }

    // Results picked for downloading together, as positions in the current
    // result list, so a new search starts with nothing picked.
    var selectedMatches by remember(resultsKey) { mutableStateOf(emptySet<Int>()) }
    var selectedVideos by remember(resultsKey) { mutableStateOf(emptySet<Int>()) }
    val selectionCount = if (mode == SearchMode.YOUTUBE) selectedVideos.size else selectedMatches.size
    val clearSelection = {
        selectedMatches = emptySet()
        selectedVideos = emptySet()
    }
    BackHandler(enabled = selectionCount > 0) { clearSelection() }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = if (selectionCount > 0) 96.dp else 16.dp)
        ) {
            item(key = "header") {
                Header(
                    showBlurb = resultsKey == null,
                    onPickVideo = onPickVideo,
                    onPickAudio = onPickAudio
                )
            }
            item(key = "modes") { ModeChips(mode = mode, onModeChange = onModeChange) }
            item(key = "inputs") {
                SearchInputs(
                    mode = mode,
                    query = query,
                    onQueryChange = onQueryChange,
                    artist = artist,
                    onArtistChange = onArtistChange,
                    title = title,
                    onTitleChange = onTitleChange,
                    onSearch = onSearch
                )
            }

            when {
                // Checked before the busy state so the list stays on screen while a
                // second search runs: a phone search takes seconds, and blanking the
                // results people are reading is worse than a stale list.
                mode == SearchMode.YOUTUBE && youtubeResults.isNotEmpty() -> {
                    youtubeStatus?.let { status ->
                        item(key = "youtube-status") {
                            Text(
                                "$status · long-press to pick several",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }
                    youTubeResults(
                        videos = youtubeResults,
                        preview = preview,
                        onPreview = onPreviewYouTube,
                        onDownload = onDownloadYouTube,
                        selected = selectedVideos,
                        selecting = selectionCount > 0,
                        onToggle = { selectedVideos = selectedVideos.toggled(it) }
                    )
                }

                stage != RecognitionStage.IDLE -> item(key = "busy") {
                    MessageBlock {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stage.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        sourceLabel?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                result is RecognitionResult.NoMatch -> item(key = "no-match") {
                    MessageBlock {
                        Text("No match", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Shazam heard the audio but didn't recognise it. Clips with " +
                                "talking over the music, very obscure tracks, and live or " +
                                "pitch-shifted versions often fail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                result is RecognitionResult.Error -> item(key = "error") {
                    MessageBlock {
                        Text("Couldn't identify", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                result is RecognitionResult.Found -> {
                    // Grouped so the rows you can act on are the ones you land on.
                    // Streaming-only results are still worth keeping for their names,
                    // years and cover art, but they cannot be downloaded, and burying
                    // the downloadable ones under them just makes for scrolling.
                    matchSection(
                        label = "Ready to download",
                        matches = result.matches.withIndex().filterNot { it.value.drmProtected },
                        selected = selectedMatches,
                        selecting = selectionCount > 0,
                        onToggle = { selectedMatches = selectedMatches.toggled(it) },
                        onFindInLibrary = onFindInLibrary,
                        onCopy = onCopy,
                        onDownload = onDownload
                    )
                    matchSection(
                        label = "Details only — streaming services",
                        matches = result.matches.withIndex().filter { it.value.drmProtected },
                        // Streaming-only rows have no file to fetch, so none can be picked.
                        selected = emptySet(),
                        selecting = false,
                        onToggle = null,
                        onFindInLibrary = onFindInLibrary,
                        onCopy = onCopy,
                        onDownload = onDownload
                    )
                }

                else -> item(key = "empty") {
                    MessageBlock {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (mode == SearchMode.YOUTUBE) {
                                "Type anything and press Go. Every result can be previewed " +
                                    "before you take it, and downloads land as tagged mp3 files."
                            } else {
                                "Search above, or pick a video or audio file and Resonate " +
                                    "will listen to it."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (selectionCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$selectionCount selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = clearSelection) { Text("Clear") }
                    Button(onClick = {
                        val matches = (result as? RecognitionResult.Found)?.matches.orEmpty()
                        if (mode == SearchMode.YOUTUBE) {
                            onDownloadMany(
                                emptyList(),
                                selectedVideos.sorted().mapNotNull { youtubeResults.getOrNull(it) }
                            )
                        } else {
                            onDownloadMany(
                                selectedMatches.sorted().mapNotNull { matches.getOrNull(it) },
                                emptyList()
                            )
                        }
                        clearSelection()
                    }) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download $selectionCount")
                    }
                }
            }
        }
    }
}

/** A heading and its cards, skipped entirely when there are none. */
private fun LazyListScope.matchSection(
    label: String,
    matches: List<IndexedValue<MusicMatch>>,
    selected: Set<Int>,
    selecting: Boolean,
    /** Null where a row cannot be picked for downloading. */
    onToggle: ((Int) -> Unit)?,
    onFindInLibrary: (MusicMatch) -> Unit,
    onCopy: (MusicMatch) -> Unit,
    onDownload: (MusicMatch) -> Unit
) {
    if (matches.isEmpty()) return
    item(key = "heading-$label") {
        ResultHeading(label, matches.size, hint = if (onToggle != null) "long-press to pick several" else null)
    }
    // Keyed by position in the full result list: two services can return the
    // same artist, title and album, and a duplicate key crashes a lazy list.
    items(matches, key = { "$label-${it.index}" }) { (index, match) ->
        MatchCard(
            match = match,
            selected = index in selected,
            selecting = selecting,
            onToggle = onToggle?.let { toggle -> { toggle(index) } },
            onFindInLibrary = { onFindInLibrary(match) },
            onCopy = { onCopy(match) },
            onDownload = { onDownload(match) }
        )
    }
}

@Composable
private fun Header(showBlurb: Boolean, onPickVideo: () -> Unit, onPickAudio: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Identify",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // Listening to a file sits beside the title instead of taking a
            // full-width row of its own.
            CompactTonalButton("Video", Icons.Default.Videocam, onPickVideo)
            Spacer(Modifier.width(8.dp))
            CompactTonalButton("Audio", Icons.Default.MusicNote, onPickAudio)
        }
        // Useful the first time, dead weight once there are results to look at.
        if (showBlurb) {
            Text(
                text = "Search by name or lyrics, paste a link, search YouTube, or let " +
                    "Resonate listen to a video or audio file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
            )
        }
    }
}

@Composable
private fun CompactTonalButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ModeChips(mode: SearchMode, onModeChange: (SearchMode) -> Unit) {
    // Searching by name and searching by lyrics hit different indexes, so which
    // one is meant has to be said rather than guessed. Scrolls sideways on a
    // narrow phone rather than wrapping onto a second line.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchMode.entries.forEach { option ->
            FilterChip(
                selected = option == mode,
                onClick = { onModeChange(option) },
                label = { Text(option.label) }
            )
        }
    }
}

@Composable
private fun SearchInputs(
    mode: SearchMode,
    query: String,
    onQueryChange: (String) -> Unit,
    artist: String,
    onArtistChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
    ) {
        if (mode == SearchMode.NAME) {
            // Two boxes. Telling the ranker which half is the performer is what
            // lets it reject a cover titled "Bohemian Rhapsody - Queen" by
            // somebody who is not Queen. Either box works on its own, which Go
            // lighting up for either one already says without a line of text.
            OutlinedTextField(
                value = artist,
                onValueChange = onArtistChange,
                placeholder = { Text("Artist name") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = { Text("Song name") },
                    leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSearch,
                    enabled = artist.isNotBlank() || title.isNotBlank() || query.isNotBlank()
                ) { Text("Go") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(mode.hint) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSearch, enabled = query.isNotBlank()) { Text("Go") }
            }
        }
    }
}

/**
 * A result card, one row tall.
 *
 * It used to be artwork and text over a separate row of three buttons, about
 * 140dp a card. Download stays one tap away as an icon beside the text, Copy
 * and Find in library move into the overflow menu, and the card is about half
 * the height, so a screen shows twice as many.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchCard(
    match: MusicMatch,
    selected: Boolean,
    selecting: Boolean,
    onToggle: (() -> Unit)?,
    onFindInLibrary: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            // Long-press starts picking several; once picking, a tap adds or removes.
            .combinedClickable(
                enabled = onToggle != null,
                onClick = { if (selecting) onToggle?.invoke() },
                onLongClick = onToggle
            )
    ) {
        Row(
            Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (match.artworkUrl != null) {
                    AsyncImage(
                        model = match.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = match.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = match.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = listOfNotNull(
                    match.provider.takeIf { it.isNotBlank() },
                    match.album,
                    match.releaseYear?.toString(),
                    match.confidence?.let { "$it% match" }
                ).joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            if (match.drmProtected) {
                // Encrypted audio, no fetchable file. A download button here
                // could only fail, or quietly substitute another recording.
                Text(
                    "Streaming\nonly",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            } else {
                FilledTonalIconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Download, contentDescription = "Download")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy name") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menuOpen = false; onCopy() }
                    )
                    DropdownMenuItem(
                        text = { Text("Find in library") },
                        leadingIcon = { Icon(Icons.Default.LibraryMusic, null) },
                        onClick = { menuOpen = false; onFindInLibrary() }
                    )
                }
            }
        }
    }
}

/**
 * A message in place of results.
 *
 * Inside a lazy list there is no screen height to fill, so it is spaced away
 * from the header rather than centred on the page.
 */
@Composable
private fun MessageBlock(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) { content() }
}

/** Section divider above a group of results. */
@Composable
private fun ResultHeading(label: String, count: Int, hint: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        hint?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                "· $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun Set<Int>.toggled(index: Int): Set<Int> = if (index in this) this - index else this + index
