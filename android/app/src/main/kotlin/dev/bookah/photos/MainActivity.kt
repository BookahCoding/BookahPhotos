package dev.bookah.photos

import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.bookah.photos.data.SyncResult
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.viewer.ViewerEnv
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.setLocalAccess(LocalAccess.current(this))
    }

    // Android's own consent dialog for moving photos of this phone to its trash
    private val trashDialog = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.phoneTrashResult(result.resultCode == RESULT_OK)
    }

    // new photos and screenshots show up without restarting the app: MediaStore tells us when something changed
    private val handler = Handler(Looper.getMainLooper())
    private val reloadLocals = Runnable { vm.reloadLocals() }
    private val mediaObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadLocals)
            handler.postDelayed(reloadLocals, 1500)       // a burst of changes (a download, a burst of shots) is read once
        }
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        vm.setLocalAccess(LocalAccess.current(this))
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(mediaObserver)
        handler.removeCallbacks(reloadLocals)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the originals are ready: hand them to the share sheet
        lifecycleScope.launch { vm.shareReady.collect { shareFiles(it) } }
        lifecycleScope.launch {
            vm.trashRequests.collect { uris ->
                val request = MediaStore.createTrashRequest(contentResolver, uris, true)
                trashDialog.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
        }
        lifecycleScope.launch {
            vm.deleteResults.collect { r ->
                val text = when {
                    r.phoneCancelled -> getString(R.string.delete_cancelled)
                    r.nextcloudFailed > 0 -> getString(R.string.delete_partly, r.nextcloudMoved, r.nextcloudFailed)
                    else -> getString(R.string.delete_done)
                }
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
            }
        }
        setContent {
            BookahPhotosTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                // open the Nextcloud approval page once
                val url = (state.signIn as? SignInUi.Waiting)?.loginUrl
                LaunchedEffect(url) {
                    if (url != null) {
                        CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(url))
                        vm.loginPageOpened()
                    }
                }
                val shareFailed = state.share is ShareUi.Failed
                LaunchedEffect(shareFailed) {
                    if (shareFailed) {
                        Toast.makeText(this@MainActivity, getString(R.string.share_failed), Toast.LENGTH_LONG).show()
                        vm.shareMessageShown()
                    }
                }
                // Surface sets the default text colour; without it text without an explicit colour is black, unreadable in the dark theme
                Surface(Modifier.fillMaxSize(), color = PhotoTheme.colors.background, contentColor = PhotoTheme.colors.textPrimary) {
                    val session = state.session
                    val lib = state.library
                    when {
                        MapSmoke.wanted(intent.getBooleanExtra("map_smoke", false)) -> MapSmoke.Show(this@MainActivity) { finish() }
                        HiddenSmoke.wanted(intent.getBooleanExtra("hidden_smoke", false)) -> HiddenSmoke.Show(this@MainActivity)
                        !state.loaded -> Unit
                        session == null -> SignInScreen(state.signIn, vm::startSignIn, vm::cancelSignIn, state.sync)
                        lib != null -> {
                            val client = remember(session) { authedClient(vm.http, session) }
                            val loader = remember(session) { buildImageLoader(applicationContext, client) }
                            val fallbackThumbs = remember(session) { FallbackThumbs(applicationContext, client, vm::originalUrlOf) }
                            val previews = remember(session) { previewsFor(session, fallbackThumbs) }
                            val env = remember(session) { ViewerEnv(previews, loader, client, session::originalUrl, vm::details, onBackUp = { vm.share(longArrayOf(it)) }, onDelete = { vm.askDelete(longArrayOf(it)) }) }
                            var menu by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxSize()) {
                                SignedInHome(
                                    state, lib, previews, loader, env, vm, onTitleLongPress = { menu = true },
                                    onAllowLocal = { mediaPermission.launch(LocalAccess.required()) },
                                    onOpenUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                    unlock = PlatformUnlocker(this@MainActivity),
                                    onOpenAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) },
                                )
                                (state.share as? ShareUi.Preparing)?.let { p ->
                                    Box(Modifier.align(Alignment.Center)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator()
                                            Text(stringResource(R.string.preparing_share, p.done, p.total), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                            if (menu) StatusDialog(state, onDismiss = { menu = false }, onRefresh = { menu = false; vm.sync() }, onSignOut = { menu = false; vm.signOut() })
                        }
                        else -> HomeScreen(state, vm::sync, vm::signOut)
                    }
                }
            }
        }
    }

    private fun shareFiles(files: List<AppViewModel.ShareFile>) {
        if (files.isEmpty()) return
        // a photo that is on this phone is shared as it is; a downloaded original goes through the app's own file provider
        val uris = ArrayList(files.map { f -> if (f.uri != null) Uri.parse(f.uri) else FileProvider.getUriForFile(this, "$packageName.share", f.file!!) })
        val types = files.mapIndexed { n, f -> f.mime ?: contentResolver.getType(uris[n]) ?: "*/*" }.toSet()
        val type = if (types.size == 1) types.first() else if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }
}

/** Opened by a long press on the "Library" title until Settings exists (a later build): index status, check again, sign out. */
@Composable
private fun StatusDialog(state: UiState, onDismiss: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit) {
    val info = state.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                if (info != null) Text(stringResource(R.string.snapshot_line, info.generation, info.assets, info.videos))
                state.session?.let { Text(stringResource(R.string.signed_in_as, it.user), color = PhotoTheme.colors.textSecondary) }
                (state.sync as? SyncUi.Done)?.let { d ->
                    Text(
                        when (d.result) {
                            is SyncResult.Offline -> stringResource(R.string.offline)
                            is SyncResult.Updated -> stringResource(R.string.updated)
                            is SyncResult.UpToDate -> stringResource(R.string.up_to_date)
                            else -> ""
                        },
                        color = PhotoTheme.colors.textSecondary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) } },
        dismissButton = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) } },
    )
}
