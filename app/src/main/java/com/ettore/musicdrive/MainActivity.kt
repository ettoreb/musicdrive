package com.ettore.musicdrive

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ettore.musicdrive.auth.ContextDriveAuthorizer
import com.ettore.musicdrive.auth.DriveAuthorizationManager
import com.ettore.musicdrive.auth.GoogleSignInManager
import com.ettore.musicdrive.auth.SignInResult
import com.ettore.musicdrive.data.drive.AlbumArtRepository
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveRepository
import com.ettore.musicdrive.data.local.SettingsRepository
import com.ettore.musicdrive.playback.MusicPlaybackService
import com.ettore.musicdrive.ui.AlbumDetailScreen
import com.ettore.musicdrive.ui.FolderPickerScreen
import com.ettore.musicdrive.ui.FullPlayerScreen
import com.ettore.musicdrive.ui.LibraryScreen
import com.ettore.musicdrive.ui.MiniPlayerBar
import com.ettore.musicdrive.ui.rememberPlayerUiState
import com.ettore.musicdrive.ui.theme.MusicDriveTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var signInManager: GoogleSignInManager
    private lateinit var driveAuthorizationManager: DriveAuthorizationManager
    private lateinit var driveRepository: DriveRepository
    private lateinit var albumArtRepository: AlbumArtRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    // Compose-observable so the UI recomposes once the controller finishes connecting.
    private var mediaController by mutableStateOf<MediaController?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        signInManager = GoogleSignInManager(this)
        driveAuthorizationManager = DriveAuthorizationManager(this)
        val app = application as MusicDriveApplication
        // Shared with MusicPlaybackService; upgrade it to show consent UI while we're alive.
        val tokenProvider = app.driveTokenProvider
        tokenProvider.setAuthorizer(driveAuthorizationManager)
        driveRepository = DriveRepository(tokenProvider)
        albumArtRepository = AlbumArtRepository(this, tokenProvider)
        settingsRepository = app.settingsRepository

        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            { mediaController = controllerFuture.get() },
            MoreExecutors.directExecutor(),
        )

        enableEdgeToEdge()
        setContent {
            MusicDriveTheme {
                MusicDriveApp(
                    signInManager = signInManager,
                    driveRepository = driveRepository,
                    albumArtRepository = albumArtRepository,
                    settingsRepository = settingsRepository,
                    mediaController = mediaController,
                    onPlayAlbum = ::playAlbum,
                )
            }
        }
    }

    private fun playAlbum(album: DriveAlbum, startIndex: Int) {
        val controller = mediaController ?: return
        val mediaItems = album.tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri("https://www.googleapis.com/drive/v3/files/${track.id}?alt=media")
                .build()
        }
        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        // Drop the activity-bound authorizer so a later token refresh (e.g. from the
        // playback service after this activity is gone) doesn't hold a dead Activity.
        (application as MusicDriveApplication).driveTokenProvider.setAuthorizer(ContextDriveAuthorizer(applicationContext))
        super.onDestroy()
    }
}

private sealed class AppState {
    data object SignedOut : AppState()
    data object Loading : AppState()
    data object PickingFolder : AppState()
    data class LibraryLoaded(val libraryFolderName: String?, val albums: List<DriveAlbum>) : AppState()
    data class Error(val message: String) : AppState()
}

private sealed class LibraryRoute {
    data object Albums : LibraryRoute()
    data class AlbumDetail(val album: DriveAlbum) : LibraryRoute()
}

@Composable
private fun MusicDriveApp(
    signInManager: GoogleSignInManager,
    driveRepository: DriveRepository,
    albumArtRepository: AlbumArtRepository,
    settingsRepository: SettingsRepository,
    mediaController: MediaController?,
    onPlayAlbum: (DriveAlbum, startIndex: Int) -> Unit,
) {
    var state by remember { mutableStateOf<AppState>(AppState.Loading) }
    var libraryRoute by remember { mutableStateOf<LibraryRoute>(LibraryRoute.Albums) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadLibrary(rootFolderId: String, rootFolderName: String?) {
        state = AppState.Loading
        driveRepository.listLibraryAlbums(rootFolderId).fold(
            onSuccess = { albums ->
                state = AppState.LibraryLoaded(rootFolderName, albums)
                libraryRoute = LibraryRoute.Albums
            },
            onFailure = { e -> state = AppState.Error(e.message ?: "Failed to list Drive files") },
        )
    }

    fun onFolderSelected(folderId: String, folderName: String) {
        scope.launch {
            settingsRepository.setLibraryRootFolderId(folderId)
            loadLibrary(folderId, folderName)
        }
    }

    fun signInAndProceed(interactive: Boolean) {
        state = AppState.Loading
        scope.launch {
            val signIn = if (interactive) signInManager.signInInteractive() else signInManager.signInSilently()
            when (signIn) {
                is SignInResult.Success -> {
                    val rootFolderId = settingsRepository.libraryRootFolderId.first()
                    if (rootFolderId == null) {
                        state = AppState.PickingFolder
                    } else {
                        loadLibrary(rootFolderId, rootFolderName = null)
                    }
                }
                is SignInResult.NoCredential -> {
                    state = AppState.SignedOut
                }
                is SignInResult.Failure -> {
                    state = AppState.Error(signIn.message)
                }
            }
        }
    }

    // Try to resume a previously authorized account silently before ever showing the sign-in button.
    LaunchedEffect(Unit) {
        signInAndProceed(interactive = false)
    }

    val playerUiState = rememberPlayerUiState(mediaController)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    when (val current = state) {
                        is AppState.SignedOut -> Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("MusicDrive")
                            Button(
                                modifier = Modifier.padding(top = 16.dp),
                                onClick = { signInAndProceed(interactive = true) },
                            ) {
                                Text("Sign in with Google")
                            }
                        }

                        is AppState.Loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        is AppState.Error -> Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Error: ${current.message}", color = MaterialTheme.colorScheme.error)
                            Button(
                                modifier = Modifier.padding(top = 16.dp),
                                onClick = { signInAndProceed(interactive = true) },
                            ) {
                                Text("Retry")
                            }
                        }

                        is AppState.PickingFolder -> FolderPickerScreen(
                            driveRepository = driveRepository,
                            onFolderSelected = ::onFolderSelected,
                        )

                        is AppState.LibraryLoaded -> when (val route = libraryRoute) {
                            is LibraryRoute.Albums -> Column(modifier = Modifier.fillMaxSize()) {
                                TextButton(onClick = { state = AppState.PickingFolder }) {
                                    Text(
                                        "Change library folder" +
                                            (current.libraryFolderName?.let { " (currently \"$it\")" } ?: ""),
                                    )
                                }
                                LibraryScreen(
                                    albums = current.albums,
                                    onAlbumClick = { libraryRoute = LibraryRoute.AlbumDetail(it) },
                                    resolveArt = albumArtRepository::resolveArt,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            is LibraryRoute.AlbumDetail -> AlbumDetailScreen(
                                album = route.album,
                                onBack = { libraryRoute = LibraryRoute.Albums },
                                onTrackClick = { index ->
                                    onPlayAlbum(route.album, index)
                                    isPlayerExpanded = true
                                },
                            )
                        }
                    }
                }

                if (state is AppState.LibraryLoaded && playerUiState.hasTrack && !isPlayerExpanded) {
                    MiniPlayerBar(
                        state = playerUiState,
                        onPlayPauseClick = {
                            if (playerUiState.isPlaying) mediaController?.pause() else mediaController?.play()
                        },
                        onClick = { isPlayerExpanded = true },
                    )
                }
            }
        }

        if (isPlayerExpanded) {
            FullPlayerScreen(
                state = playerUiState,
                onPlayPauseClick = {
                    if (playerUiState.isPlaying) mediaController?.pause() else mediaController?.play()
                },
                onNextClick = { mediaController?.seekToNextMediaItem() },
                onPreviousClick = { mediaController?.seekToPreviousMediaItem() },
                onSeek = { positionMs -> mediaController?.seekTo(positionMs) },
                onCollapse = { isPlayerExpanded = false },
            )
        }
    }
}
