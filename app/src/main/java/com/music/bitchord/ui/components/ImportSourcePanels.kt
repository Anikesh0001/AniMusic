package com.music.bitchord.ui.components

import android.text.format.DateUtils
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.importer.ImportException
import com.music.bitchord.data.importer.ImportHistoryStore
import com.music.bitchord.data.importer.ImportRecord
import com.music.bitchord.data.importer.LastFmImporter
import com.music.bitchord.data.importer.ListenBrainzImporter
import com.music.bitchord.data.importer.SongListParser
import kotlinx.coroutines.launch

/** A song list typed or pasted in: one song per line. */
@Composable
internal fun TextSourcePanel(status: String?, failed: Boolean, onImport: (name: String, text: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val count = remember(text) { SongListParser.parseText(text).size }
    StatusText(status ?: stringResource(R.string.import_text_description), failed, neutral = status == null)
    PillTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.import_text_name))
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
            .padding(12.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                text = stringResource(R.string.import_text_hint),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
    }
    PanelButton(
        label = stringResource(R.string.import_text_button, count),
        enabled = count > 0,
        onClick = { onImport(name.trim(), text) },
    )
}

/** A ListenBrainz user's playlists, found by name, one tap to import. */
@Composable
internal fun ListenBrainzPanel(status: String?, failed: Boolean, onPick: (mbid: String, title: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<ListenBrainzImporter.PlaylistSummary>?>(null) }
    fun find() {
        if (user.isBlank()) return
        loading = true
        error = null
        scope.launch {
            try {
                found = ListenBrainzImporter.playlistsOf(user)
                if (found.isNullOrEmpty()) error = context.getString(R.string.import_lb_none)
            } catch (e: ImportException) {
                error = if (e.reason == ImportException.Reason.NOT_FOUND) context.getString(R.string.import_lb_none)
                else e.reason.message(context)
            } catch (e: Exception) {
                error = ImportException.Reason.NETWORK.message(context)
            }
            loading = false
        }
    }
    StatusText(error ?: status ?: stringResource(R.string.import_lb_description), failed || error != null, neutral = error == null && status == null)
    PillTextField(
        value = user,
        onValueChange = { user = it },
        placeholder = stringResource(R.string.import_lb_user_hint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { find() }),
    )
    if (loading) {
        Box(Modifier.padding(12.dp)) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
    } else {
        PanelButton(stringResource(R.string.import_lb_find), enabled = user.isNotBlank(), onClick = ::find)
    }
    found?.takeIf { it.isNotEmpty() }?.let { list ->
        PanelList {
            list.forEach { p ->
                PanelRow(
                    title = p.title,
                    meta = if (p.createdFor) stringResource(R.string.import_lb_made_for) else null,
                    onClick = { onPick(p.mbid, p.title) },
                )
            }
        }
    }
}

/** A Last.fm user's loved or top tracks. Only offered when the build has an API key. */
@Composable
internal fun LastFmPanel(status: String?, failed: Boolean, onImport: (user: String, kind: LastFmImporter.Kind) -> Unit) {
    var user by remember { mutableStateOf("") }
    StatusText(status ?: stringResource(R.string.import_lastfm_description), failed, neutral = status == null)
    PillTextField(value = user, onValueChange = { user = it }, placeholder = stringResource(R.string.import_lastfm_user_hint))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            PanelButton(stringResource(R.string.import_lastfm_loved), enabled = user.isNotBlank()) {
                onImport(user, LastFmImporter.Kind.LOVED)
            }
        }
        Box(Modifier.weight(1f)) {
            PanelButton(stringResource(R.string.import_lastfm_top), enabled = user.isNotBlank()) {
                onImport(user, LastFmImporter.Kind.TOP)
            }
        }
    }
}

/** Past imports, newest first: open the playlist, sync it, or import the link again. */
@Composable
internal fun HistoryPanel(
    onOpen: ((ImportRecord) -> Unit)?,
    onSync: (ImportRecord) -> Unit,
    onReimport: (ImportRecord) -> Unit,
    status: String?,
    failed: Boolean,
) {
    val records by ImportHistoryStore.records.collectAsStateWithLifecycle()
    StatusText(status, failed)
    if (records.isEmpty()) {
        Text(
            text = stringResource(R.string.import_history_empty),
            modifier = Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    PanelList(maxHeight = 320) {
        records.forEach { record ->
            val linked = record.sourceUrl.startsWith("http")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.import_history_meta,
                        record.serviceLabel,
                        DateUtils.getRelativeTimeSpanString(record.syncedAt ?: record.importedAt).toString(),
                        record.matched,
                        record.total,
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onOpen != null && record.browseId != null) {
                        Chip(stringResource(R.string.import_history_open), false) { onOpen(record) }
                    }
                    if (linked && record.browseId != null) {
                        Chip(stringResource(R.string.import_history_sync), false) { onSync(record) }
                    }
                    if (linked) {
                        Chip(stringResource(R.string.import_history_reimport), false) { onReimport(record) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelList(maxHeight: Int = 240, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun PanelRow(title: String, meta: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        meta?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PanelButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
