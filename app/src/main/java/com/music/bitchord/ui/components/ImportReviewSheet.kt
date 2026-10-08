package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.importer.ResolvedTrack
import com.music.bitchord.data.importer.TrackResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.launch

/** A row on the review screen: what was matched, or that the listener took it out. */
data class ReviewRow(val resolved: ResolvedTrack, val removed: Boolean = false)

/** The rows of an import worth a second look: a guess, or nothing found. */
fun ResolvedTrack.needsReview(): Boolean = song == null || !confident

/**
 * The rows an import was unsure of, with what else YouTube Music offered.
 *
 * Every row the matcher didn't vouch for — the search's first hit standing in,
 * or nothing at all — is listed with its source title and artist. Opening one
 * shows the matcher's own ranking of the top five candidates first, then a
 * search box for when none of them is it, and a way to drop the row. "Retry
 * unmatched" searches again for the rows that found nothing, which is the
 * answer to a dropped connection mid-import.
 *
 * [rows] is the whole import, in its order; only the rows needing review are
 * drawn, but edits come back through [onChange] against the full list so the
 * saved playlist keeps the source's order.
 */
@Composable
fun ImportReviewSheet(
    rows: List<ReviewRow>,
    onChange: (List<ReviewRow>) -> Unit,
    onDone: () -> Unit,
    resolver: TrackResolver = TrackResolver.Default,
) {
    BackHandler(onBack = onDone)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<Int?>(null) }
    var retrying by remember { mutableStateOf(false) }
    val candidates = remember { mutableStateMapOf<Int, List<Song>>() }
    // Indices into [rows], fixed when the screen opens: a row the listener
    // fixes stays on screen (now marked as theirs) rather than vanishing.
    val reviewIndices = remember { rows.indices.filter { rows[it].resolved.needsReview() } }
    val unmatched = rows.count { !it.removed && it.resolved.song == null }

    fun update(index: Int, row: ReviewRow) = onChange(rows.toMutableList().also { it[index] = row })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.import_review_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.import_review_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillButton(stringResource(R.string.done), emphasised = true, onClick = onDone)
        }
        if (unmatched > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.spotify_import_unmatched, unmatched),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (retrying) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    PillButton(stringResource(R.string.import_review_retry), emphasised = false) {
                        retrying = true
                        scope.launch {
                            var current = rows
                            for (i in current.indices) {
                                val row = current[i]
                                if (row.removed || row.resolved.song != null) continue
                                val again = resolver.match(row.resolved.track)
                                current = current.toMutableList().also { it[i] = row.copy(resolved = again) }
                                onChange(current)
                            }
                            retrying = false
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(reviewIndices, key = { _, index -> index }) { _, index ->
                val row = rows[index]
                ReviewItem(
                    row = row,
                    open = expanded == index,
                    candidates = candidates[index],
                    onToggle = {
                        expanded = if (expanded == index) null else index
                        if (expanded == index && candidates[index] == null) {
                            scope.launch { candidates[index] = resolver.candidates(row.resolved.track) }
                        }
                    },
                    onPick = { song ->
                        update(index, ReviewRow(row.resolved.copy(song = song, confident = true)))
                        expanded = null
                    },
                    onSearch = { query ->
                        scope.launch { candidates[index] = resolver.searchSongs(query).take(5) }
                    },
                    onRemove = {
                        update(index, row.copy(removed = !row.removed))
                        expanded = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ReviewItem(
    row: ReviewRow,
    open: Boolean,
    candidates: List<Song>?,
    onToggle: () -> Unit,
    onPick: (Song) -> Unit,
    onSearch: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val track = row.resolved.track
    val song = row.resolved.song
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (open) 0.9f else 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (row.removed) 0.4f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        row.removed -> stringResource(R.string.import_review_removed)
                        song == null -> stringResource(R.string.import_review_not_found)
                        row.resolved.confident -> stringResource(R.string.import_review_chosen, song.title, song.artist)
                        else -> stringResource(R.string.import_review_guess, song.title, song.artist)
                    },
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        row.removed -> MaterialTheme.colorScheme.onSurfaceVariant
                        song == null -> MaterialTheme.colorScheme.error
                        row.resolved.confident -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                when {
                    candidates == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    candidates.isEmpty() -> Text(
                        text = stringResource(R.string.import_review_no_candidates),
                        modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> candidates.forEach { candidate ->
                        CandidateRow(candidate, selected = candidate.videoId == song?.videoId) { onPick(candidate) }
                    }
                }
                var query by remember(track) { mutableStateOf("${track.title} ${track.artist}".trim()) }
                PillTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.import_review_search),
                    modifier = Modifier.padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.import_review_search_button), emphasised = false) {
                        if (query.isNotBlank()) onSearch(query)
                    }
                    Spacer(Modifier.weight(1f))
                    PillButton(
                        stringResource(if (row.removed) R.string.import_review_keep else R.string.import_review_remove),
                        emphasised = false,
                        onClick = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(song: Song, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(120),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(song.artist, song.albumName, song.durationText).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PillButton(label: String, emphasised: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (emphasised) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
