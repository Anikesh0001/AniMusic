package com.music.bitchord.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.ImportUrls
import com.music.bitchord.data.importer.ImportedCollection
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.ResolveResult
import com.music.bitchord.data.importer.TrackResolver
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.MusicLink
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    ImportService.YOUTUBE_MUSIC,
)

/**
 * Paste a playlist, album or song link from any music app, match its songs on
 * YouTube Music, keep the result.
 *
 * Grown out of the Spotify-only dialog and drawn the same way: the frosted
 * card of the addon editor, a "who can see it" row only for [signedIn], and
 * the songs that matched nothing listed afterwards rather than dropped.
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink) }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<ImportTrack>?>(null) }
    val detected = remember(link) { ImportService.detect(link) }
    // Read and matched, not yet saved: the review step's working copy.
    var pending by remember { mutableStateOf<ImportedCollection?>(null) }
    var rows by remember { mutableStateOf<List<ReviewRow>>(emptyList()) }
    var reviewing by remember { mutableStateOf(false) }

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

    fun start() {
        // YouTube and YouTube Music links need no importing: they already
        // are what the app plays, so they open the way a shared one does.
        val youTube = ImportUrls.firstUrl(link)
            ?.takeIf { onlyServices.isEmpty() }
            ?.let { MusicLink.parse(Uri.parse(it)) }
        if (youTube != null) {
            MusicLink.offer(youTube)
            onDismiss()
            return
        }
        working = true
        failed = false
        status = context.getString(R.string.import_link_fetching_generic)
        scope.launch {
            try {
                val (importer, url) = ImporterRegistry.resolve(link)
                if (onlyServices.isNotEmpty() && importer.service !in onlyServices) {
                    throw ImportException(ImportException.Reason.UNSUPPORTED)
                }
                status = context.getString(R.string.import_link_fetching, importer.service.label)
                val collection = ImporterRegistry.fetchWith(importer, url)
                val result = TrackResolver.Default.resolve(collection.tracks) { done, total ->
                    status = context.getString(R.string.spotify_import_matching, done, total)
                }
                if (result.songs.isEmpty()) error(context.getString(R.string.spotify_import_none))
                pending = collection
                rows = result.rows.map { ReviewRow(it) }
                // Nothing to second-guess, or a single song to play: no stop on the way.
                // A partial read stops here too, so the listener sees what is missing.
                val clean = result.rows.none { it.needsReview() } && collection.missingCount == 0
                if (collection.tracks.size == 1 || clean) {
                    save()
                } else {
                    status = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                status = e.reason.message(context)
                failed = true
            } catch (e: Exception) {
                status = context.getString(R.string.spotify_import_failed, e.message.orEmpty())
                failed = true
            }
            working = false
        }
    }

    LaunchedEffect(Unit) {
        if (autoStart && link.isNotBlank()) start()
    }

    if (reviewing) {
        ImportReviewSheet(rows = rows, onChange = { rows = it }, onDone = { reviewing = false })
        return
    }

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
            val missed = unmatched
            val matched = pending
            if (missed != null) {
                UnmatchedList(missed)
            } else if (matched != null && !working) {
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
            } else {
                Text(
                    text = status ?: stringResource(
                        if (signedIn) R.string.import_link_description
                        else R.string.import_link_description_local,
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = when {
                        status == null -> MaterialTheme.colorScheme.onSurface
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
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
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank() && !working) start() }),
                )
                Text(
                    text = if (detected != null) {
                        stringResource(R.string.import_link_detected, detected.label)
                    } else {
                        stringResource(
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
                if (signedIn) {
                    PrivacyPicker(privacy, enabled = !working) { privacy = it }
                }
            }
        }
        AlertRule()
        if (unmatched != null) {
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        } else if (pending != null && !working) {
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
        } else {
            AlertAction(
                label = stringResource(R.string.spotify_import_button),
                emphasised = true,
                onClick = ::start,
                enabled = link.isNotBlank() && !working,
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.cancel),
                emphasised = false,
                onClick = onDismiss,
                enabled = !working,
            )
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

@Composable
internal fun UnmatchedList(missed: List<ImportTrack>) {
    Text(
        text = stringResource(R.string.spotify_import_unmatched, missed.size),
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        missed.forEach { track ->
            Text(
                text = if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
