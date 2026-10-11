package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.importer.ImportException
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportedCollection
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.ResolveResult
import com.music.bitchord.data.importer.SongListParser
import com.music.bitchord.data.importer.TrackResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Playlist import on the desktop: the phone's importers and matcher (in `:shared`), behind a
 * dialog of the desktop's own. A link, a pasted list of songs, or a CSV/M3U/TXT file becomes a
 * playlist kept on this computer, on the Library's "On device" shelf.
 */
internal object DesktopImport {

    /** The phone's own limit for an imported file. */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /** The services a link can come from, for the dialog's "Supported services" line. */
    val supportedServices: String = ImportService.entries
        // Last.fm is a username import the phone has, not a link.
        .filter { it !in setOf(ImportService.FILE, ImportService.TEXT, ImportService.WEB, ImportService.LASTFM) }
        .joinToString(", ") { it.label }

    /** Why an import failed, in the listener's language. */
    fun errorText(error: Throwable): String = when ((error as? ImportException)?.reason) {
        ImportException.Reason.INVALID_LINK -> DesktopStrings["import_error_invalid", "That doesn't look like a link."]
        ImportException.Reason.UNSUPPORTED -> DesktopStrings["import_error_unsupported", "This link isn't supported yet."]
        ImportException.Reason.NOT_FOUND ->
            DesktopStrings["import_error_not_found", "Nothing to import there. Make sure it's public."]
        ImportException.Reason.RATE_LIMITED ->
            DesktopStrings["import_error_rate_limited", "The service is busy. Try again in a minute."]
        ImportException.Reason.PARSE ->
            DesktopStrings["import_error_parse", "Couldn't read that page. The service may have changed it."]
        ImportException.Reason.NETWORK, null ->
            DesktopStrings["import_error_network", "Couldn't reach the service. Check your connection."]
    }

    /** A pasted list of songs, one per line, as a collection named [name]. */
    fun fromText(name: String, text: String): ImportedCollection = ImportedCollection(
        service = ImportService.TEXT,
        sourceUrl = "",
        title = name.trim().ifBlank { DesktopStrings["import_text_default_name", "Pasted songs"] },
        tracks = SongListParser.parseText(text),
    )

    /** A CSV, M3U or TXT file's songs. Throws [FileProblem] for a file that can't be used. */
    fun fromFile(file: File): ImportedCollection {
        if (file.length() > MAX_FILE_BYTES) {
            throw FileProblem(DesktopStrings["import_file_too_big", "That file is too large to import."])
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            throw FileProblem(DesktopStrings["import_file_unreadable", "Couldn't read that file."])
        }
        val collection = SongListParser.parseFile(file.name, text)
        if (collection.tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        return collection
    }

    /** The playlist an import saves: every matched song once, in the source's order. */
    fun toPlaylist(title: String, result: ResolveResult): DesktopPlaylist =
        DesktopPlaylist(title = title.trim(), songs = result.songs.distinctBy { it.videoId })

    /** The native file picker, filtered to the formats the parser reads. */
    fun chooseFile(): File? = runCatching {
        val dialog = FileDialog(null as Frame?, DesktopStrings["import_mode_file", "File"], FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            name.substringAfterLast('.', "").lowercase() in setOf("csv", "m3u", "m3u8", "txt")
        }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        File(dialog.directory, name)
    }.getOrNull()

    class FileProblem(message: String) : Exception(message)
}

private enum class ImportMode { LINK, TEXT, FILE }

/** Where the dialog is: asking, reading or matching, or showing what it found. */
private sealed interface ImportStep {
    data object Input : ImportStep
    data class Working(val label: String, val done: Int = 0, val total: Int = 0) : ImportStep
    data class Done(val collection: ImportedCollection, val result: ResolveResult) : ImportStep
}

/** Import a playlist from a link, a list of songs or a file, and save it on this computer. */
@Composable
internal fun DesktopImportDialog(
    onSave: (DesktopPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ImportMode.LINK) }
    var step by remember { mutableStateOf<ImportStep>(ImportStep.Input) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    var link by remember { mutableStateOf("") }
    var listName by remember { mutableStateOf("") }
    var listText by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }

    /** Reads the collection with [read], then matches it on YouTube Music. */
    fun start(readingLabel: String, read: suspend () -> ImportedCollection) {
        error = null
        step = ImportStep.Working(readingLabel)
        job = scope.launch {
            try {
                val collection = withContext(Dispatchers.IO) { read() }
                if (collection.tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
                val total = collection.tracks.size
                step = ImportStep.Working(matchingLabel(0, total), 0, total)
                val result = TrackResolver.Default.resolve(collection.tracks) { done, all ->
                    step = ImportStep.Working(matchingLabel(done, all), done, all)
                }
                playlistName = collection.title
                step = ImportStep.Done(collection, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DesktopImport.FileProblem) {
                error = e.message
                step = ImportStep.Input
            } catch (e: Throwable) {
                DesktopTrackLog.log("import: ${e::class.simpleName}: ${e.message}")
                error = DesktopImport.errorText(e)
                step = ImportStep.Input
            }
        }
    }

    val close = {
        job?.cancel()
        onDismiss()
    }

    DesktopDialogFrame(onDismiss = close) {
        when (val current = step) {
            ImportStep.Input -> {
                DialogHeading(
                    DesktopStrings["import_any", "Import playlist"],
                    when (mode) {
                        ImportMode.LINK -> DesktopStrings[
                            "import_link_description_local",
                            "Paste a playlist, album or song link from any music app. Songs are matched on YouTube Music and saved on this device.",
                        ]
                        ImportMode.TEXT ->
                            DesktopStrings["import_text_description", "Paste a list of songs and get a playlist back."]
                        ImportMode.FILE -> "CSV (Exportify, TuneMyMusic, Google Takeout), M3U/M3U8 or TXT"
                    },
                )
                ModeTabs(mode) {
                    mode = it
                    error = null
                }
                error?.let { DialogError(it) }
                when (mode) {
                    ImportMode.LINK -> LinkInput(
                        link = link,
                        onLink = { link = it },
                        onImport = {
                            val service = ImportService.detect(link)
                            start(
                                service?.let { DesktopStrings.format("import_link_fetching", it.label, fallback = "Reading from %1\$s…") }
                                    ?: DesktopStrings["import_link_fetching_generic", "Reading the link…"],
                            ) { ImporterRegistry.fetch(link) }
                        },
                        onDismiss = close,
                    )
                    ImportMode.TEXT -> TextInput(
                        name = listName,
                        text = listText,
                        onName = { listName = it },
                        onText = { listText = it },
                        onImport = {
                            val name = listName
                            val text = listText
                            start(DesktopStrings["import_file_reading", "Reading the file…"]) {
                                DesktopImport.fromText(name, text)
                            }
                        },
                        onDismiss = close,
                    )
                    ImportMode.FILE -> FileInput(
                        onChoose = {
                            DesktopImport.chooseFile()?.let { file ->
                                start(DesktopStrings["import_file_reading", "Reading the file…"]) {
                                    DesktopImport.fromFile(file)
                                }
                            }
                        },
                        onDismiss = close,
                    )
                }
            }

            is ImportStep.Working -> {
                DialogHeading(DesktopStrings["import_any", "Import playlist"], current.label)
                Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                    if (current.total > 0) {
                        LinearProgressIndicator(
                            progress = { current.done.toFloat() / current.total },
                            color = DesktopAccent,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            color = DesktopAccent,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = close) { Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary) }
                }
            }

            is ImportStep.Done -> ImportSummary(
                collection = current.collection,
                result = current.result,
                name = playlistName,
                onName = { playlistName = it },
                onSave = {
                    onSave(DesktopImport.toPlaylist(playlistName.ifBlank { current.collection.title }, current.result))
                },
                onBack = { step = ImportStep.Input },
            )
        }
    }
}

private fun matchingLabel(done: Int, total: Int) =
    DesktopStrings.format("spotify_import_matching", done, total, fallback = "Matching songs %1\$d of %2\$d")

@Composable
private fun ModeTabs(selected: ImportMode, onSelect: (ImportMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            ImportMode.LINK to DesktopStrings["import_mode_link", "Link"],
            ImportMode.TEXT to DesktopStrings["import_mode_text", "Song list"],
            ImportMode.FILE to DesktopStrings["import_mode_file", "File"],
        ).forEach { (mode, label) ->
            val active = mode == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Color.White else DesktopSecondary,
                )
            }
        }
    }
}

@Composable
private fun InputBox(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = DesktopSecondary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(DesktopAccent),
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LinkInput(link: String, onLink: (String) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val ready = link.isNotBlank()
    InputBox(
        value = link,
        onValue = onLink,
        hint = DesktopStrings["import_link_hint", "Paste link from any music app"],
        singleLine = true,
        modifier = Modifier.padding(top = 6.dp).focusRequester(focusRequester),
        onDone = { if (ready) onImport() },
    )
    val detected = remember(link) { ImportService.detect(link) }
    Text(
        detected?.let { DesktopStrings.format("import_link_detected", it.label, fallback = "Detected: %1\$s") }
            ?: DesktopStrings.format("import_link_supported", DesktopImport.supportedServices, fallback = "Supported services: %1\$s"),
        style = MaterialTheme.typography.bodySmall,
        color = DesktopSecondary,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 22.dp),
    )
    DialogButtons(
        busy = false,
        confirmLabel = DesktopStrings["import_any", "Import playlist"],
        confirmEnabled = ready,
        onConfirm = onImport,
        onDismiss = onDismiss,
    )
}

@Composable
private fun TextInput(
    name: String,
    text: String,
    onName: (String) -> Unit,
    onText: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = remember(text) { SongListParser.parseText(text).size }
    InputBox(
        value = name,
        onValue = onName,
        hint = DesktopStrings["import_text_name", "Playlist name"],
        singleLine = true,
        modifier = Modifier.padding(top = 6.dp),
    )
    InputBox(
        value = text,
        onValue = onText,
        hint = DesktopStrings["import_text_hint", "One song per line, like “Artist - Title” or “Title by Artist”"],
        singleLine = false,
        modifier = Modifier.heightIn(min = 140.dp, max = 260.dp),
    )
    DialogButtons(
        busy = false,
        confirmLabel = DesktopStrings.format("import_text_button", count, fallback = "Import %1\$d songs"),
        confirmEnabled = count > 0,
        onConfirm = onImport,
        onDismiss = onDismiss,
    )
}

@Composable
private fun FileInput(onChoose: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onChoose)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Description, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("CSV · M3U · TXT…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) { Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary) }
    }
}

/** What the import found: how many matched, which weren't found, and the name to save it as. */
@Composable
private fun ImportSummary(
    collection: ImportedCollection,
    result: ResolveResult,
    name: String,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val unsure = result.lowConfidence.size
    val notFound = result.unmatched
    val matched = result.rows.size - unsure - notFound.size
    DialogHeading(
        collection.title,
        DesktopStrings.format(
            "import_review_summary",
            matched,
            unsure,
            notFound.size,
            fallback = "%1\$d matched · %2\$d unsure · %3\$d not found",
        ),
    )
    if (collection.missingCount > 0) {
        Text(
            DesktopStrings.format(
                "import_partial",
                collection.missingCount,
                fallback = "%1\$d more tracks couldn't be read from the source",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = DesktopSecondary,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
    }
    InputBox(
        value = name,
        onValue = onName,
        hint = DesktopStrings["import_text_name", "Playlist name"],
        singleLine = true,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (notFound.isNotEmpty()) {
        Text(
            DesktopStrings["import_review_not_found", "Not found on YouTube Music"].uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = DesktopSecondary,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            items(notFound) { track ->
                Text(
                    listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 3.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(DesktopStrings["back", "Back"], color = DesktopSecondary) }
        val canSave = result.songs.isNotEmpty()
        TextButton(onClick = onSave, enabled = canSave) {
            Text(
                DesktopStrings["import_review_save", "Save playlist"],
                color = if (canSave) DesktopAccent else DesktopSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
