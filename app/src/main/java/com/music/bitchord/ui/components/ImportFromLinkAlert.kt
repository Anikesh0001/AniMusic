package com.music.bitchord.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.importer.ImportException
import com.music.bitchord.data.importer.ImportRecord
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportSync
import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.ImportUrls
import com.music.bitchord.data.importer.ImportedCollection
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.LastFmImporter
import com.music.bitchord.data.importer.ResolveResult
import com.music.bitchord.data.importer.SongListParser
import com.music.bitchord.data.importer.TrackResolver
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.MusicLink
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The services named under the link box, in the order a listener is likely to look for theirs. */
internal val SUPPORTED_IMPORT_SERVICES = listOf(
    ImportService.SPOTIFY,
    ImportService.APPLE_MUSIC,
    ImportService.DEEZER,
    ImportService.AUDIOMACK,
    ImportService.QOBUZ,
    ImportService.JIOSAAVN,
    ImportService.SOUNDCLOUD,
    ImportService.GAANA,
    ImportService.TIDAL,
    ImportService.YOUTUBE_MUSIC,
)

/** Where the songs come from: what the row of options under the title switches between. */
internal enum class ImportMode { LINK, TEXT, LISTENBRAINZ, LASTFM, HISTORY }

/** The largest file read for import. A 10,000-row CSV is well under this. */
private const val MAX_FILE_BYTES = 5L * 1024 * 1024

/**
 * Import songs from another music app and match them on YouTube Music.
 *
 * Grown out of the Spotify-only dialog and drawn the same way: the frosted
 * card of the addon editor, a "who can see it" row only for [signedIn], and
 * the songs that matched nothing listed afterwards rather than dropped. A
 * row of options under the title switches the source: a link (several links,
 * one per line, import one after another), a pasted song list, a file
 * (CSV, M3U, TXT), a ListenBrainz or Last.fm user, or the history of past
 * imports. Every source ends in the same matching, review and save.
 * [initialLink] with [autoStart] is how a link shared into the app arrives.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ImportFromLinkAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    /** Called once the songs are matched; [privacy] is meaningful only when signed in. */
    onImported: (collection: ImportedCollection, result: ResolveResult, privacy: PlaylistPrivacy) -> Unit,
    onDismiss: () -> Unit,
    initialLink: String = "",
    autoStart: Boolean = false,
    /** Accept only links [ImporterRegistry] resolves to one of these; empty is every service. */
    onlyServices: Set<ImportService> = emptySet(),
    title: String = stringResource(R.string.import_link_title),
    /** Opens a past import's playlist from the history; null hides that action. */
    onOpenPlaylist: ((browseId: String, title: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ImportMode.LINK) }
    var link by remember { mutableStateOf(initialLink) }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<ImportTrack>?>(null) }
    var batchLines by remember { mutableStateOf<List<String>?>(null) }
    val detected = remember(link) { ImportService.detect(link) }
    // Read and matched, not yet saved: the review step's working copy.
    var pending by remember { mutableStateOf<ImportedCollection?>(null) }
    var rows by remember { mutableStateOf<List<ReviewRow>>(emptyList()) }
    var reviewing by remember { mutableStateOf(false) }
    val fullAccess = onlyServices.isEmpty()

    fun fail(e: Throwable) {
        status = when (e) {
            is ImportException -> e.reason.message(context)
            else -> context.getString(R.string.spotify_import_failed, e.message.orEmpty())
        }
        failed = true
    }

    fun save() {
        val collection = pending ?: return
        val kept = rows.filterNot { it.removed }.map { it.resolved }
        val result = ResolveResult(kept)
        if (result.songs.isEmpty()) {
            status = context.getString(R.string.spotify_import_none)
            failed = true
            pending = null
            return
        }
        onImported(collection, result, privacy)
        pending = null
        val missed = result.unmatched
        if (missed.isEmpty()) onDismiss() else unmatched = missed
    }

    /** Matches [collection] and either saves it or stops for review. */
    suspend fun match(collection: ImportedCollection) {
        if (collection.tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        val result = TrackResolver.Default.resolve(collection.tracks) { done, total ->
            status = context.getString(R.string.spotify_import_matching, done, total)
        }
        if (result.songs.isEmpty()) error(context.getString(R.string.spotify_import_none))
        pending = collection
        rows = result.rows.map { ReviewRow(it) }
        // Nothing to second-guess, or a single song to play: no stop on the way.
        // A partial read stops here too, so the listener sees what is missing.
        val clean = result.rows.none { it.needsReview() } && collection.missingCount == 0
        if (collection.tracks.size == 1 || clean) save() else status = null
    }

    /** Runs one source through matching, with the dialog's progress and errors. */
    fun run(reading: String?, load: suspend () -> ImportedCollection) {
        working = true
        failed = false
        status = reading ?: context.getString(R.string.import_link_fetching_generic)
        scope.launch {
            try {
                match(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
            working = false
        }
    }

    /** Several links at once: each read, matched and saved in turn, with a line each at the end. */
    fun runBatch(urls: List<String>) {
        working = true
        failed = false
        scope.launch {
            val lines = mutableListOf<String>()
            var done = 0
            urls.forEachIndexed { i, url ->
                val prefix = context.getString(R.string.import_batch_progress, i + 1, urls.size)
                status = prefix
                if (MusicLink.parse(Uri.parse(url)) != null) {
                    lines += context.getString(R.string.import_batch_line_failed, url, context.getString(R.string.import_batch_youtube))
                    return@forEachIndexed
                }
                try {
                    val (importer, target) = ImporterRegistry.resolve(url)
                    status = "$prefix · " + context.getString(R.string.import_link_fetching, importer.service.label)
                    val collection = ImporterRegistry.fetchWith(importer, target)
                    val result = TrackResolver.Default.resolve(collection.tracks) { n, total ->
                        status = "$prefix · " + context.getString(R.string.spotify_import_matching, n, total)
                    }
                    if (result.songs.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
                    onImported(collection, result, privacy)
                    done++
                    lines += context.getString(R.string.import_batch_line_ok, collection.title, result.songs.size, collection.tracks.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val why = (e as? ImportException)?.reason?.message(context) ?: e.message.orEmpty()
                    lines += context.getString(R.string.import_batch_line_failed, url, why)
                }
            }
            status = context.getString(R.string.import_batch_title, done, urls.size)
            batchLines = lines
            working = false
        }
    }

    fun startLink() {
        val urls = ImportUrls.allUrls(link).distinct()
        if (fullAccess && urls.size > 1) {
            runBatch(urls)
            return
        }
        // YouTube and YouTube Music links need no importing: they already
        // are what the app plays, so they open the way a shared one does.
        val youTube = ImportUrls.firstUrl(link)?.takeIf { fullAccess }?.let { MusicLink.parse(Uri.parse(it)) }
        if (youTube != null) {
            MusicLink.offer(youTube)
            onDismiss()
            return
        }
        run(null) {
            val (importer, url) = ImporterRegistry.resolve(link)
            if (!fullAccess && importer.service !in onlyServices) {
                throw ImportException(ImportException.Reason.UNSUPPORTED)
            }
            status = context.getString(R.string.import_link_fetching, importer.service.label)
            ImporterRegistry.fetchWith(importer, url)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.import_file_reading)) { readSongFile(context, uri) }
    }

    LaunchedEffect(Unit) {
        if (autoStart && link.isNotBlank()) startLink()
    }

    if (reviewing) {
        ImportReviewSheet(rows = rows, onChange = { rows = it }, onDone = { reviewing = false })
        return
    }

    val idle = !working && pending == null && unmatched == null && batchLines == null

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (idle && fullAccess) {
                ModeChips(
                    selected = mode,
                    onSelect = {
                        mode = it
                        status = null
                        failed = false
                    },
                    onFile = {
                        filePicker.launch(
                            arrayOf(
                                "text/*", "application/csv", "text/csv", "text/comma-separated-values",
                                "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl",
                                "application/x-mpegurl", "application/octet-stream",
                            ),
                        )
                    },
                )
            }
            val missed = unmatched
            val matched = pending
            val batch = batchLines
            when {
                batch != null -> {
                    StatusText(status, failed = false)
                    ResultLines(batch)
                }
                missed != null -> UnmatchedList(missed)
                matched != null && !working -> {
                    val kept = rows.filterNot { it.removed }
                    Text(
                        text = stringResource(
                            R.string.import_review_summary,
                            kept.count { it.resolved.song != null && it.resolved.confident },
                            kept.count { it.resolved.song != null && !it.resolved.confident },
                            kept.count { it.resolved.song == null },
                        ),
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (matched.missingCount > 0) {
                        Text(
                            text = stringResource(R.string.import_partial, matched.missingCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                working -> StatusText(status, failed = false)
                else -> when (mode) {
                    ImportMode.LINK -> {
                        StatusText(
                            status ?: stringResource(
                                if (signedIn) R.string.import_link_description else R.string.import_link_description_local,
                            ),
                            failed = failed,
                            neutral = status == null,
                        )
                        PillTextField(
                            value = link,
                            onValueChange = {
                                link = it
                                if (failed) {
                                    status = null
                                    failed = false
                                }
                            },
                            placeholder = stringResource(R.string.import_link_hint),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank()) startLink() }),
                        )
                        Text(
                            text = when {
                                fullAccess && ImportUrls.allUrls(link).distinct().size > 1 ->
                                    stringResource(R.string.import_batch_detected, ImportUrls.allUrls(link).distinct().size)
                                detected != null -> stringResource(R.string.import_link_detected, detected.label)
                                else -> stringResource(
                                    R.string.import_link_supported,
                                    onlyServices.ifEmpty { SUPPORTED_IMPORT_SERVICES }.joinToString(", ") { it.label },
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            color = if (detected != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ImportMode.TEXT -> TextSourcePanel(status, failed) { name, text ->
                        run(null) {
                            ImportedCollection(
                                service = ImportService.TEXT,
                                sourceUrl = "text:",
                                title = name.ifBlank { context.getString(R.string.import_text_default_name) },
                                tracks = SongListParser.parseText(text),
                            )
                        }
                    }
                    ImportMode.LISTENBRAINZ -> ListenBrainzPanel(status, failed) { mbid, name ->
                        run(context.getString(R.string.import_link_fetching, ImportService.LISTENBRAINZ.label)) {
                            com.music.bitchord.data.importer.ListenBrainzImporter.playlist(mbid).let {
                                if (it.title.isBlank()) it.copy(title = name) else it
                            }
                        }
                    }
                    ImportMode.LASTFM -> LastFmPanel(status, failed) { user, kind ->
                        run(context.getString(R.string.import_link_fetching, ImportService.LASTFM.label)) {
                            LastFmImporter.fetch(user, kind).let {
                                it.copy(
                                    title = context.getString(
                                        if (kind == LastFmImporter.Kind.LOVED) R.string.import_lastfm_title_loved
                                        else R.string.import_lastfm_title_top,
                                        user.trim(),
                                    ),
                                )
                            }
                        }
                    }
                    ImportMode.HISTORY -> HistoryPanel(
                        onOpen = onOpenPlaylist?.let { open ->
                            { record: ImportRecord ->
                                record.browseId?.let { open(it, record.title) }
                                onDismiss()
                            }
                        },
                        onSync = { record ->
                            working = true
                            status = context.getString(R.string.import_sync_started, record.serviceLabel)
                            scope.launch {
                                val outcome = ImportSync.sync(record)
                                status = outcome.message(context, record)
                                failed = outcome is ImportSync.Outcome.Failed
                                working = false
                            }
                        },
                        onReimport = { record ->
                            mode = ImportMode.LINK
                            link = record.sourceUrl
                            startLink()
                        },
                        status = status,
                        failed = failed,
                    )
                }
            }
            if (signedIn && idle && mode != ImportMode.HISTORY) {
                PrivacyPicker(privacy, enabled = true) { privacy = it }
            }
        }
        AlertRule()
        when {
            batchLines != null || unmatched != null ->
                AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
            pending != null && !working -> {
                val anyToReview = rows.any { it.resolved.needsReview() }
                if (anyToReview) {
                    AlertAction(
                        label = stringResource(R.string.import_review_open),
                        emphasised = true,
                        onClick = { reviewing = true },
                    )
                    AlertRule()
                }
                AlertAction(label = stringResource(R.string.import_review_save), emphasised = !anyToReview, onClick = ::save)
                AlertRule()
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
            }
            else -> {
                if (mode == ImportMode.LINK) {
                    AlertAction(
                        label = stringResource(R.string.spotify_import_button),
                        emphasised = true,
                        onClick = ::startLink,
                        enabled = link.isNotBlank() && !working,
                    )
                    AlertRule()
                }
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = onDismiss,
                    enabled = !working,
                )
            }
        }
    }
}

/** Reads a chosen file as a song list: its name decides CSV / M3U / text. */
private suspend fun readSongFile(context: Context, uri: Uri): ImportedCollection = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name: String? = null
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) null else c.getLong(1)
            }
        }
    }
    if ((size ?: 0) > MAX_FILE_BYTES) throw IllegalStateException(context.getString(R.string.import_file_too_big))
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val buffer = input.readNBytesCompat(MAX_FILE_BYTES + 1)
        if (buffer.size > MAX_FILE_BYTES) throw IllegalStateException(context.getString(R.string.import_file_too_big))
        buffer
    } ?: throw IllegalStateException(context.getString(R.string.import_file_unreadable))
    SongListParser.parseFile(name, bytes.toString(Charsets.UTF_8))
}

/** `readNBytes` is API 33; this is the same, for API 26. */
private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (total < limit) {
        val n = read(chunk, 0, minOf(chunk.size.toLong(), limit - total).toInt())
        if (n < 0) break
        out.write(chunk, 0, n)
        total += n
    }
    return out.toByteArray()
}

@Composable
private fun ModeChips(selected: ImportMode, onSelect: (ImportMode) -> Unit, onFile: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(stringResource(R.string.import_mode_link), selected == ImportMode.LINK) { onSelect(ImportMode.LINK) }
        Chip(stringResource(R.string.import_mode_text), selected == ImportMode.TEXT) { onSelect(ImportMode.TEXT) }
        Chip(stringResource(R.string.import_mode_file), false, onClick = onFile)
        Chip(ImportService.LISTENBRAINZ.label, selected == ImportMode.LISTENBRAINZ) { onSelect(ImportMode.LISTENBRAINZ) }
        if (LastFmImporter.available) {
            Chip(ImportService.LASTFM.label, selected == ImportMode.LASTFM) { onSelect(ImportMode.LASTFM) }
        }
        Chip(stringResource(R.string.import_mode_history), selected == ImportMode.HISTORY) { onSelect(ImportMode.HISTORY) }
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StatusText(text: String?, failed: Boolean, neutral: Boolean = false) {
    text ?: return
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
        color = when {
            neutral -> MaterialTheme.colorScheme.onSurface
            failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ResultLines(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The pre-generalisation entry point: Spotify links only, songs handed back as before. */
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    ImportFromLinkAlert(
        hazeState = hazeState,
        signedIn = signedIn,
        onImported = { collection, result, privacy -> onImported(collection.title, privacy, result.songs) },
        onDismiss = onDismiss,
        onlyServices = setOf(ImportService.SPOTIFY),
        title = stringResource(R.string.spotify_import_title),
    )
}

/** The listener's-language text for an import failure. */
fun ImportException.Reason.message(context: Context): String = context.getString(
    when (this) {
        ImportException.Reason.INVALID_LINK -> R.string.import_error_invalid
        ImportException.Reason.UNSUPPORTED -> R.string.import_error_unsupported
        ImportException.Reason.NOT_FOUND -> R.string.import_error_not_found
        ImportException.Reason.NETWORK -> R.string.import_error_network
        ImportException.Reason.RATE_LIMITED -> R.string.import_error_rate_limited
        ImportException.Reason.PARSE -> R.string.import_error_parse
    },
)

/** The notice a finished "Sync from source" leaves. */
fun ImportSync.Outcome.message(context: Context, record: ImportRecord): String = when (this) {
    is ImportSync.Outcome.Added -> context.getString(R.string.import_sync_added, added, record.title)
    ImportSync.Outcome.UpToDate -> context.getString(R.string.import_sync_up_to_date, record.title)
    is ImportSync.Outcome.Failed -> context.getString(
        R.string.import_sync_failed,
        reason?.message(context) ?: context.getString(R.string.failed),
    )
}

@Composable
internal fun UnmatchedList(missed: List<ImportTrack>) {
    Text(
        text = stringResource(R.string.spotify_import_unmatched, missed.size),
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    ResultLines(missed.map { if (it.artist.isBlank()) it.title else "${it.title} · ${it.artist}" })
}

@Composable
internal fun PrivacyPicker(privacy: PlaylistPrivacy, enabled: Boolean, onChange: (PlaylistPrivacy) -> Unit) {
    Text(
        text = stringResource(R.string.who_can_see_it),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlaylistPrivacy.entries.forEach { option ->
            val selected = option == privacy
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable(enabled = enabled) { onChange(option) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
