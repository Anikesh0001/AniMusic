package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.Blocklist

/** Every blocked song and artist, each with the way to allow it again. */
@Composable
fun BlocklistDialog(onDismiss: () -> Unit) {
    val entries by Blocklist.entries.collectAsStateWithLifecycle()
    val rows = entries.artists.map { (key, name) -> Triple(name, true, key) } +
        entries.songs.map { (id, title) -> Triple(title, false, id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blocked_title)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.blocked_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(rows, key = { (if (it.second) "a:" else "s:") + it.third }) { (name, isArtist, key) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = if (isArtist) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = {
                                if (isArtist) Blocklist.unblockArtist(key) else Blocklist.unblockSong(key)
                            }) { Text(stringResource(R.string.unblock)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
