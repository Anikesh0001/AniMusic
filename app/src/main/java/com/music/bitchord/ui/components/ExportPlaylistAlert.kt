package com.music.bitchord.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.music.bitchord.R
import com.music.bitchord.data.importer.PlaylistExporter
import com.music.bitchord.data.importer.PlaylistExporter.Format
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Export a playlist as M3U, CSV or text: saved where the listener picks
 * (Storage Access Framework, no permission), or handed to another app
 * through the share sheet from the cache folder the FileProvider exposes.
 *
 * [loadSongs] reads the playlist's full track list when the dialog opens —
 * the page on screen may still be filling.
 */
@Composable
fun ExportPlaylistAlert(
    hazeState: HazeState,
    title: String,
    loadSongs: suspend () -> List<Song>?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(Format.M3U) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var status by remember { mutableStateOf<String?>(context.getString(R.string.export_loading)) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = runCatching { loadSongs() }.getOrNull()?.filter { it.videoId.isNotBlank() }
        songs = loaded
        if (loaded.isNullOrEmpty()) {
            status = context.getString(R.string.export_empty)
            failed = true
        } else {
            status = context.getString(R.string.export_ready, loaded.size)
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val list = songs ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(PlaylistExporter.export(format, title, list).toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }.isSuccess
            }
            if (ok) onDismiss() else {
                status = context.getString(R.string.export_playlist_failed)
                failed = true
            }
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.export_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            StatusText(status, failed, neutral = !failed)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                Chip("M3U", format == Format.M3U) { format = Format.M3U }
                Chip("CSV", format == Format.CSV) { format = Format.CSV }
                Chip(stringResource(R.string.export_format_text), format == Format.TEXT) { format = Format.TEXT }
            }
            Text(
                text = stringResource(
                    when (format) {
                        Format.M3U -> R.string.export_format_m3u_hint
                        Format.CSV -> R.string.export_format_csv_hint
                        Format.TEXT -> R.string.export_format_text_hint
                    },
                ),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val ready = !songs.isNullOrEmpty()
        AlertRule()
        AlertAction(
            label = stringResource(R.string.export_share),
            emphasised = true,
            enabled = ready,
            onClick = {
                val list = songs ?: return@AlertAction
                scope.launch {
                    val shared = shareExport(context, title, format, list)
                    if (shared) onDismiss() else {
                        status = context.getString(R.string.export_playlist_failed)
                        failed = true
                    }
                }
            },
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.export_save),
            emphasised = false,
            enabled = ready,
            onClick = { saver.launch(PlaylistExporter.fileName(title, format)) },
        )
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/** Writes the export to cache/shared and opens the share sheet on it. */
private suspend fun shareExport(context: Context, title: String, format: Format, songs: List<Song>): Boolean {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(folder, PlaylistExporter.fileName(title, format))
            file.writeText(PlaylistExporter.export(format, title, songs), Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    } ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        // Text-only targets (a chat, a note) get the list itself rather than nothing.
        if (format == Format.TEXT) putExtra(Intent.EXTRA_TEXT, PlaylistExporter.export(format, title, songs))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
