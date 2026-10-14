package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.stats.SmartPlaylists
import com.music.bitchord.data.stats.SmartRules
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MIN_PLAYS = listOf(1, 2, 3, 5, 10, 20, 50)
private val DAYS = listOf(0, 7, 14, 30, 60, 90, 180, 365)
private val SKIP_PERCENT = listOf(0, 10, 25, 50)
private val MONTHS = listOf(0, 1, 2, 3, 6, 12)
private val SIZES = listOf(25, 50, 100, 200)

/**
 * Makes a smart playlist from a few rules.
 *
 * Every value is picked from a short list on a slider, so there is nothing to type but
 * the name and nothing that can be out of range. The playlist is made on the spot, and
 * if no song matches the rules nothing is made and the form says so.
 */
@Composable
fun SmartPlaylistDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var minPlays by remember { mutableIntStateOf(2) }
    var idle by remember { mutableIntStateOf(0) }
    var recent by remember { mutableIntStateOf(0) }
    var skip by remember { mutableIntStateOf(0) }
    var newer by remember { mutableIntStateOf(0) }
    var size by remember { mutableIntStateOf(1) }
    var order by remember { mutableStateOf(SmartRules.Order.MOST_PLAYED) }
    var noMatch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val off = stringResource(R.string.off)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.smart_new_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60); noMatch = false },
                    label = { Text(stringResource(R.string.smart_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Choice(stringResource(R.string.smart_f_min_plays, MIN_PLAYS[minPlays]), minPlays, MIN_PLAYS.size) {
                    minPlays = it; noMatch = false
                }
                Choice(
                    if (DAYS[idle] == 0) stringResource(R.string.smart_f_idle_off, off)
                    else stringResource(R.string.smart_f_idle, DAYS[idle]),
                    idle, DAYS.size,
                ) { idle = it; noMatch = false }
                Choice(
                    if (DAYS[recent] == 0) stringResource(R.string.smart_f_recent_off, off)
                    else stringResource(R.string.smart_f_recent, DAYS[recent]),
                    recent, DAYS.size,
                ) { recent = it; noMatch = false }
                Choice(
                    if (SKIP_PERCENT[skip] == 0) stringResource(R.string.smart_f_skip_off, off)
                    else stringResource(R.string.smart_f_skip, SKIP_PERCENT[skip]),
                    skip, SKIP_PERCENT.size,
                ) { skip = it; noMatch = false }
                Choice(
                    if (MONTHS[newer] == 0) stringResource(R.string.smart_f_new_off, off)
                    else stringResource(R.string.smart_f_new, MONTHS[newer]),
                    newer, MONTHS.size,
                ) { newer = it; noMatch = false }
                Choice(stringResource(R.string.smart_f_size, SIZES[size]), size, SIZES.size) { size = it }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        SmartRules.Order.MOST_PLAYED to R.string.smart_order_most_played,
                        SmartRules.Order.RECENT_PLAYS to R.string.smart_order_recent,
                        SmartRules.Order.LONGEST_SINCE to R.string.smart_order_longest,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = order == value,
                            onClick = { order = value },
                            label = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
                }
                if (noMatch) {
                    Text(
                        stringResource(R.string.smart_no_match),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val rule = SmartRules.customRule(
                            key = "new",
                            minPlays = MIN_PLAYS[minPlays],
                            idleDays = DAYS[idle],
                            recentDays = DAYS[recent],
                            maxSkipPercent = SKIP_PERCENT[skip],
                            newMonths = MONTHS[newer],
                            order = order,
                            limit = SIZES[size],
                        )
                        val made = SmartPlaylists.create(context, name, rule)
                        busy = false
                        if (made == 0) {
                            noMatch = true
                        } else {
                            Toast.makeText(context, context.getString(R.string.smart_created, name.trim()), Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    }
                },
            ) { Text(stringResource(R.string.smart_create)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** A label and a slider that steps through the positions of a short list. */
@Composable
private fun Choice(label: String, index: Int, count: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, count - 1)) },
            valueRange = 0f..(count - 1).toFloat(),
            steps = (count - 2).coerceAtLeast(0),
        )
    }
}
