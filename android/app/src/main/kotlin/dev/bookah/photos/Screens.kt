package dev.bookah.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.bookah.photos.data.SyncResult
import dev.bookah.photos.design.Dims
import dev.bookah.photos.design.PhotoTheme

@Composable
fun SignInScreen(signIn: SignInUi, onSignIn: (String) -> Unit, onCancel: () -> Unit, sync: SyncUi) {
    var address by rememberSaveable { mutableStateOf("") }
    val busy = signIn is SignInUi.Starting || signIn is SignInUi.Waiting
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dims.sideMargin),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sign_in_intro), style = MaterialTheme.typography.bodyLarge, color = PhotoTheme.colors.textSecondary)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.server_address)) },
            placeholder = { Text(stringResource(R.string.server_hint)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        when (signIn) {
            SignInUi.Starting, is SignInUi.Waiting -> {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.waiting_for_approval), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            else -> {
                if (signIn is SignInUi.Error) {
                    val text = when (signIn.message) {
                        SignInUi.ErrorKind.BadAddress -> stringResource(R.string.error_address)
                        SignInUi.ErrorKind.Failed -> stringResource(R.string.error_sign_in_failed, signIn.detail.orEmpty())
                        SignInUi.ErrorKind.TimedOut -> stringResource(R.string.error_sign_in_timeout)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                if (sync is SyncUi.Done && sync.result is SyncResult.SignedOut) {
                    Text(stringResource(R.string.signed_out), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = { onSignIn(address) }, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth().height(Dims.hit + 4.dp)) {
                    Text(stringResource(R.string.sign_in))
                }
            }
        }
    }
}

/** A first Library screen: what the snapshot holds and how the sync went. The grid comes in the next step. */
@Composable
fun HomeScreen(state: UiState, onRefresh: () -> Unit, onSignOut: () -> Unit) {
    val info = state.info
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dims.sideMargin).padding(top = 24.dp),
    ) {
        Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        if (info != null) {
            Text(stringResource(R.string.snapshot_line, info.generation, info.assets, info.videos), style = MaterialTheme.typography.bodyLarge)
            if (info.undated > 0) Text(stringResource(R.string.undated_line, info.undated), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
        } else {
            Text(stringResource(R.string.no_snapshot_yet), style = MaterialTheme.typography.bodyLarge, color = PhotoTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(16.dp))
        when (val s = state.sync) {
            SyncUi.Idle -> Unit
            SyncUi.Checking -> Text(stringResource(R.string.syncing), style = MaterialTheme.typography.bodyMedium)
            is SyncUi.Downloading -> {
                Text(stringResource(R.string.downloading, s.percent), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is SyncUi.Done -> {
                val text = when (val r = s.result) {
                    is SyncResult.UpToDate -> stringResource(R.string.up_to_date)
                    is SyncResult.Updated -> stringResource(R.string.updated)
                    is SyncResult.AppTooOld -> stringResource(R.string.app_too_old)
                    is SyncResult.Offline -> stringResource(R.string.offline)
                    SyncResult.SignedOut -> stringResource(R.string.signed_out)
                    SyncResult.NoIndexYet -> stringResource(R.string.no_snapshot_yet)
                    is SyncResult.WaitingForWifi -> stringResource(R.string.waiting_for_wifi, (r.sizeBytes / 1_000_000).toInt())
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
            }
        }
        Spacer(Modifier.weight(1f))
        state.session?.let { Text(stringResource(R.string.signed_in_as, it.user), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
    }
}
