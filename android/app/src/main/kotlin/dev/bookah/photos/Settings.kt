package dev.bookah.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.data.Formats
import dev.bookah.photos.data.SyncResult
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.RoundButton

/**
 * Settings: the account, the index, the picture cache, updates and About. The updater is not switched on while the repository is private
 * (the app asks GitHub without a password and a private repository would never answer).
 */
@Composable
fun SettingsScreen(
    state: UiState, loader: ImageLoader, onBack: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit, onAllowLocal: () -> Unit,
    onCheckUpdates: () -> Unit, onOpenUrl: (String) -> Unit, onMeasureScrolling: () -> Unit, bench: BenchResult?, modifier: Modifier = Modifier,
    searchUi: dev.bookah.photos.search.SearchUi = dev.bookah.photos.search.SearchUi(), modelBytes: Long = 0L,
    onDownloadModel: () -> Unit = {}, onDeleteModel: () -> Unit = {}, people: PeopleState = PeopleState(),
    onAllowMobileData: (Boolean) -> Unit = {}, onDownloadNow: () -> Unit = {},
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var cacheBytes by remember { mutableLongStateOf(loader.diskCache?.size ?: 0L) }
    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_unsent_title)) },
            text = { Text(stringResource(R.string.sign_out_unsent_text, people.notSent)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.sign_out)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Column(
        modifier.fillMaxSize().background(PhotoTheme.colors.background).verticalScroll(rememberScrollState())
            .padding(top = top + 8.dp, bottom = bottom + 24.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
            Text(stringResource(R.string.settings), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineLarge, color = PhotoTheme.colors.textPrimary)
        }
        Group(stringResource(R.string.settings_account)) {
            Line(stringResource(R.string.settings_server), state.session?.server.orEmpty())
            Line(stringResource(R.string.settings_user), state.session?.user.orEmpty())
            OutlinedButton(onClick = { if (people.notSent > 0) confirmSignOut = true else onSignOut() }) { Text(stringResource(R.string.sign_out)) }
        }
        if (people.waiting > 0 || people.error != null) {
            Group(stringResource(R.string.settings_people)) {
                Line(stringResource(R.string.people_waiting), people.waiting.toString())
                if (people.notSent > 0) Line(stringResource(R.string.people_not_sent), people.notSent.toString())
                Text(stringResource(R.string.people_waiting_note), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                people.error?.let { Text(stringResource(R.string.people_error, it), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Group(stringResource(R.string.settings_index)) {
            val info = state.info
            if (info != null) {
                Line(stringResource(R.string.settings_generation), info.generation.toString())
                Line(stringResource(R.string.settings_items), info.assets.toString())
                Line(stringResource(R.string.settings_videos), info.videos.toString())
                Line(stringResource(R.string.settings_undated), info.undated.toString())
            } else {
                Text(stringResource(R.string.no_snapshot_yet), color = PhotoTheme.colors.textSecondary)
            }
            val sync = state.sync
            Text(
                when {
                    sync is SyncUi.Done && sync.result is SyncResult.Offline -> stringResource(R.string.offline)
                    sync is SyncUi.Done && sync.result is SyncResult.WaitingForWifi -> stringResource(R.string.waiting_for_wifi, ((sync.result as SyncResult.WaitingForWifi).sizeBytes / 1_000_000).toInt())
                    sync is SyncUi.Done && sync.result is SyncResult.UpToDate -> stringResource(R.string.up_to_date)
                    sync is SyncUi.Done && sync.result is SyncResult.Updated -> stringResource(R.string.updated)
                    sync is SyncUi.Done && sync.result is SyncResult.AppTooOld -> stringResource(R.string.app_too_old)
                    sync is SyncUi.Checking || sync is SyncUi.Downloading -> stringResource(R.string.syncing)
                    else -> ""
                },
                color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            if ((state.sync as? SyncUi.Done)?.result is SyncResult.WaitingForWifi) OutlinedButton(onClick = onDownloadNow, modifier = Modifier.testTag("download-now")) { Text(stringResource(R.string.download_now_mobile)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mobile_data_title), color = PhotoTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.mobile_data_text), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                androidx.compose.material3.Switch(checked = state.allowMobileData, onCheckedChange = onAllowMobileData, modifier = Modifier.testTag("mobile-data-switch"))
            }
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        val crashLog = remember { dev.bookah.photos.data.CrashLog(java.io.File(context.filesDir, "crash.log")) }
        var crashText by remember { mutableStateOf(crashLog.read()) }
        if (crashText != null) {
            Group(stringResource(R.string.settings_problems)) {
                Text(stringResource(R.string.crash_text, crashLog.lastTime()?.let { java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withZone(java.time.ZoneId.systemDefault()).format(it) }.orEmpty()), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, "BookahPhotos crash log").putExtra(android.content.Intent.EXTRA_TEXT, crashText!!.takeLast(60_000))
                    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.testTag("share-crash")) { Text(stringResource(R.string.crash_share)) }
                OutlinedButton(onClick = { crashLog.clear(); crashText = null }) { Text(stringResource(R.string.crash_clear)) }
            }
        }
        Group(stringResource(R.string.settings_phone)) {
            Line(
                stringResource(R.string.settings_phone_access),
                stringResource(when (state.localAccess) { LocalAccess.Full -> R.string.access_full; LocalAccess.Partial -> R.string.access_partial; LocalAccess.None -> R.string.access_none }),
            )
            val st = state.localStats
            if (st != null && state.localAccess != LocalAccess.None) {
                Line(stringResource(R.string.settings_phone_total), st.local.toString())
                Line(stringResource(R.string.settings_phone_matched), st.matched.toString())
                Line(stringResource(R.string.settings_phone_only), st.phoneOnly.toString())
                st.perFolder.entries.sortedByDescending { it.value }.forEach { (folder, n) -> Line("  $folder", n.toString()) }
            }
            if (state.localAccess != LocalAccess.Full) OutlinedButton(onClick = onAllowLocal) { Text(stringResource(R.string.local_access_allow)) }
        }
        Group(stringResource(R.string.settings_search)) {
            val m = searchUi.model
            Line(
                stringResource(R.string.settings_search_model),
                when (m) {
                    is dev.bookah.photos.search.ModelUi.Ready, is dev.bookah.photos.search.ModelUi.Loading -> stringResource(R.string.search_model_on_phone, Formats.size(modelBytes))
                    is dev.bookah.photos.search.ModelUi.Available -> stringResource(R.string.search_model_not_downloaded, Formats.size(m.sizeBytes))
                    is dev.bookah.photos.search.ModelUi.NotPublished -> stringResource(R.string.search_model_missing)
                    else -> stringResource(R.string.search_model_working)
                },
            )
            if (m is dev.bookah.photos.search.ModelUi.Available) OutlinedButton(onClick = onDownloadModel) { Text(stringResource(R.string.search_model_download)) }
            if (modelBytes > 0L && (m is dev.bookah.photos.search.ModelUi.Ready || m is dev.bookah.photos.search.ModelUi.Failed)) {
                OutlinedButton(onClick = onDeleteModel) { Text(stringResource(R.string.search_model_delete)) }
            }
        }
        Group(stringResource(R.string.settings_cache)) {
            Line(stringResource(R.string.settings_cache_used), Formats.size(cacheBytes))
            Line(stringResource(R.string.settings_cache_limit), "2 GB")
            OutlinedButton(onClick = {
                loader.diskCache?.clear(); loader.memoryCache?.clear(); cacheBytes = loader.diskCache?.size ?: 0L
            }) { Text(stringResource(R.string.settings_cache_clear)) }
        }
        Group(stringResource(R.string.settings_updates)) {
            if (BuildConfig.RELEASES_REPO.isBlank()) {
                Text(stringResource(R.string.settings_updates_off), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                val u = state.update
                Text(
                    when (u) {
                        UpdateUi.Idle -> ""
                        UpdateUi.Checking -> stringResource(R.string.update_checking)
                        UpdateUi.UpToDate -> stringResource(R.string.update_none)
                        UpdateUi.Failed -> stringResource(R.string.update_failed)
                        is UpdateUi.Available -> stringResource(R.string.update_available, u.info.version)
                    },
                    color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
                if (u is UpdateUi.Available) OutlinedButton(onClick = { onOpenUrl(u.info.apkUrl ?: u.info.pageUrl) }) { Text(stringResource(R.string.update_download)) }
                OutlinedButton(onClick = onCheckUpdates) { Text(stringResource(R.string.update_check)) }
            }
        }
        Group(stringResource(R.string.settings_speed)) {
            val t = state.timings
            if (t != null) {
                t.startToLibraryMs?.let { Line(stringResource(R.string.speed_start), "$it ms") }
                Line(stringResource(R.string.speed_merge), "${t.mergeMs} ms (${t.items})")
            }
            if (bench != null) {
                Line(stringResource(R.string.speed_frames), "${bench.frames} (${bench.refreshHz.toInt()} Hz)")
                Line(stringResource(R.string.speed_slow), "%.1f %%".format(bench.slowPercent))
                Line(stringResource(R.string.speed_worst), "%.0f ms".format(bench.worstMs))
                Line(stringResource(R.string.speed_median), "%.1f ms".format(bench.medianMs))
            }
            OutlinedButton(onClick = onMeasureScrolling) { Text(stringResource(R.string.speed_measure)) }
        }
        Group(stringResource(R.string.settings_about)) {
            Line(stringResource(R.string.app_name), BuildConfig.VERSION_NAME)
            Text(stringResource(R.string.settings_geonames), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_privacy), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PhotoTheme.colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = PhotoTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}
