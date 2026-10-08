package com.music.bitchord.ui.components

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportSettings
import com.music.bitchord.data.importer.ImportUrls
import com.music.bitchord.data.importer.ImporterRegistry
import kotlinx.coroutines.delay

/** Clips already offered or dismissed in this process, so one copy is offered once. */
private object ClipboardSeen {
    val keys = mutableSetOf<String>()
}

/**
 * "Import from Deezer?" when the app comes back with a music link copied.
 *
 * Read only while the window has focus, which Android 10+ requires for a
 * clipboard read to return anything, and only when [ImportSettings.clipboardDetection]
 * is on. The clip's description is looked at before its text: on Android 12+
 * every text read shows the "pasted from your clipboard" toast, so a clip the
 * system has already classified as not containing a link, or one already
 * offered (same timestamp), is never read at all.
 */
@Composable
fun ClipboardImportPrompt(
    /** The listener tapped Import on [url]. */
    onImport: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled by ImportSettings.clipboardDetection.collectAsStateWithLifecycle()
    val focused = LocalWindowInfo.current.isWindowFocused
    var offer by remember { mutableStateOf<Pair<String, ImportService>?>(null) }

    LaunchedEffect(focused, enabled) {
        if (!focused || !enabled) return@LaunchedEffect
        // Let the window settle: a read in the same frame focus arrives is refused on some OEM builds.
        delay(400)
        offer = readMusicLink(context)?.also { ClipboardSeen.keys += it.first }
    }
    LaunchedEffect(offer) {
        if (offer == null) return@LaunchedEffect
        delay(8_000)
        offer = null
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = offer != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val current = offer
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 150.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.clipboard_import_prompt, current?.second?.label.orEmpty()),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.later),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { offer = null }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                )
                Text(
                    text = stringResource(R.string.spotify_import_button),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            current?.let { onImport(it.first) }
                            offer = null
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}

/** The copied link and its service, if the clipboard holds an importable music link not yet offered. */
private fun readMusicLink(context: Context): Pair<String, ImportService>? = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    if (!clipboard.hasPrimaryClip()) return null
    val description = clipboard.primaryClipDescription ?: return null
    if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
        !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    ) return null
    val stamp = "t:" + description.timestamp
    if (stamp in ClipboardSeen.keys) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
        description.getConfidenceScore(TextClassifier.TYPE_URL) < 0.1f
    ) {
        ClipboardSeen.keys += stamp
        return null
    }
    ClipboardSeen.keys += stamp
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
    val url = ImportUrls.firstUrl(text) ?: return null
    if (url in ClipboardSeen.keys) return null
    val service = ImportService.detect(url) ?: return null
    // YouTube links are the app's own; nothing to import. Only services with
    // something that reads them (or a shortener that may lead to one) are offered.
    if (service == ImportService.YOUTUBE_MUSIC) return null
    if (ImporterRegistry.findDedicated(url) == null && !ImporterRegistry.isShortLink(url) &&
        service !in GENERIC_SERVICES
    ) return null
    url to service
}.getOrNull()

/** Services the generic page reader imports, so a copied link to one is worth offering. */
private val GENERIC_SERVICES = setOf(
    ImportService.GAANA, ImportService.AUDIOMACK, ImportService.TIDAL,
)
