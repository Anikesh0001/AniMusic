package com.music.bitchord.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.account.AccountSync
import com.music.bitchord.data.account.AniAccount
import kotlinx.coroutines.launch

/** The words for an [AniAccount.Failure]. */
@Composable
private fun failureText(failure: AniAccount.Failure): String = stringResource(
    when (failure) {
        AniAccount.Failure.EMAIL_TAKEN -> R.string.account_error_email_taken
        AniAccount.Failure.INVALID_EMAIL -> R.string.account_error_invalid_email
        AniAccount.Failure.PASSWORD_TOO_SHORT -> R.string.account_error_password_short
        AniAccount.Failure.WRONG_EMAIL_OR_PASSWORD -> R.string.account_error_wrong
        AniAccount.Failure.TOO_MANY_ATTEMPTS -> R.string.account_error_too_many
        AniAccount.Failure.OFFLINE -> R.string.account_error_offline
        AniAccount.Failure.SERVER -> R.string.account_error_server
    },
)

private fun Throwable.failure(): AniAccount.Failure =
    (this as? AniAccount.AccountException)?.failure ?: AniAccount.Failure.SERVER

/**
 * Sign in to, create, or manage an [AniAccount]: email and password, no
 * verification. Signed in, it shows the sync state and the way out.
 */
@Composable
fun AniAccountDialog(onDismiss: () -> Unit) {
    val account by AniAccount.current.collectAsStateWithLifecycle()
    val current = account
    if (current == null) SignInDialog(onDismiss) else ManageDialog(current, onDismiss)
}

@Composable
private fun SignInDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AniAccount.Failure?>(null) }

    fun submit() {
        if (busy) return
        // Checked here too so the common mistakes don't need the network.
        failure = when {
            !email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}")) -> AniAccount.Failure.INVALID_EMAIL
            creating && password.length < 6 -> AniAccount.Failure.PASSWORD_TOO_SHORT
            password.isEmpty() -> AniAccount.Failure.WRONG_EMAIL_OR_PASSWORD
            else -> null
        }
        if (failure != null) return
        busy = true
        scope.launch {
            runCatching {
                if (creating) AniAccount.register(email, password, name) else AniAccount.login(email, password)
            }.onFailure { failure = it.failure() }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (creating) R.string.account_create else R.string.account_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.account_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; failure = null },
                    label = { Text(stringResource(R.string.account_email)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; failure = null },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(R.string.account_name)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                failure?.let {
                    Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { creating = !creating; failure = null }, enabled = !busy) {
                    Text(stringResource(if (creating) R.string.account_switch_to_sign_in else R.string.account_switch_to_create))
                }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator()
            } else {
                TextButton(onClick = ::submit) {
                    Text(stringResource(if (creating) R.string.account_create else R.string.sign_in))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ManageDialog(account: AniAccount.Account, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by AccountSync.status.collectAsStateWithLifecycle()
    val lastSynced by AccountSync.lastSynced.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<AniAccount.Failure?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (deleting) R.string.account_delete else R.string.account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (deleting) {
                    Text(stringResource(R.string.account_delete_warning))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; failure = null },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        stringResource(R.string.account_subtitle_in, account.name.ifBlank { account.email }),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(account.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            status == AccountSync.Status.SYNCING -> stringResource(R.string.account_syncing)
                            status == AccountSync.Status.FAILED -> stringResource(R.string.account_sync_failed)
                            lastSynced > 0 -> stringResource(
                                R.string.account_last_synced,
                                DateUtils.getRelativeTimeSpanString(lastSynced).toString(),
                            )
                            else -> stringResource(R.string.account_never_synced)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = { scope.launch { AccountSync.syncNow() } },
                        enabled = status != AccountSync.Status.SYNCING,
                    ) { Text(stringResource(R.string.account_sync_now)) }
                    TextButton(onClick = { deleting = true }) {
                        Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                busy -> CircularProgressIndicator()
                deleting -> TextButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { AniAccount.deleteAccount(password) }
                            .onSuccess { AccountSync.reset() }
                            .onFailure { failure = it.failure() }
                        busy = false
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                else -> TextButton(onClick = {
                    busy = true
                    scope.launch {
                        AniAccount.logout()
                        AccountSync.reset()
                        busy = false
                    }
                }) { Text(stringResource(R.string.sign_out)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (deleting) { deleting = false; password = ""; failure = null } else onDismiss() },
                enabled = !busy,
            ) { Text(stringResource(if (deleting) R.string.cancel else R.string.close)) }
        },
    )
}
