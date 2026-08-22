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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ettore.musicdrive.auth.ContextDriveAuthorizer
import com.ettore.musicdrive.auth.DriveAuthorizationManager
import com.ettore.musicdrive.auth.GoogleSignInManager
import com.ettore.musicdrive.auth.SignInResult
import com.ettore.musicdrive.data.LibraryRepository
import com.ettore.musicdrive.data.LyricsRepository
import com.ettore.musicdrive.data.drive.AlbumArtRepository
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveRepository
import com.ettore.musicdrive.data.local.AlbumSortMode
import com.ettore.musicdrive.data.local.LibraryViewMode
import com.ettore.musicdrive.data.local.SettingsRepository
import com.ettore.musicdrive.data.local.ThemeMode
import com.ettore.musicdrive.download.DownloadTracker
import com.ettore.musicdrive.playback.MusicPlaybackService
import com.ettore.musicdrive.playback.driveMediaUri
import com.ettore.musicdrive.ui.AlbumDetailScreen
import com.ettore.musicdrive.ui.ArtistListScreen
import com.ettore.musicdrive.ui.ArtistSummary
import com.ettore.musicdrive.ui.FolderPickerScreen
import com.ettore.musicdrive.ui.FullPlayerScreen
import com.ettore.musicdrive.ui.LibraryScreen
import com.ettore.musicdrive.ui.LyricsScreen
import com.ettore.musicdrive.ui.MiniPlayerBar
import com.ettore.musicdrive.ui.QueueScreen
import com.ettore.musicdrive.ui.ScreenHeader
import com.ettore.musicdrive.ui.SettingsScreen
import com.ettore.musicdrive.ui.groupByArtist
import com.ettore.musicdrive.ui.rememberPlayerUiState
import com.ettore.musicdrive.ui.rememberQueueState
import com.ettore.musicdrive.ui.sortedByMode
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
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var albumArtRepository: AlbumArtRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lyricsRepository: LyricsRepository
    private lateinit var downloadTracker: DownloadTracker
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
        libraryRepository = LibraryRepository(driveRepository, app.database.libraryDao())
        albumArtRepository = AlbumArtRepository(this, tokenProvider)
        settingsRepository = app.settingsRepository
        lyricsRepository = LyricsRepository(app.database.lyricsDao())
        downloadTracker = app.downloadTracker

        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            { mediaController = controllerFuture.get() },
            MoreExecutors.directExecutor(),
        )

        enableEdgeToEdge()
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            MusicDriveTheme(themeMode = themeMode) {
                MusicDriveApp(
                    signInManager = signInManager,
                    driveRepository = driveRepository,
                    libraryRepository = libraryRepository,
                    albumArtRepository = albumArtRepository,
                    settingsRepository = settingsRepository,
                    lyricsRepository = lyricsRepository,
                    downloadTracker = downloadTracker,
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
                .setUri(driveMediaUri(track.id))
                // Best-guess metadata (the Drive filename) so the queue has something to
                // show for tracks Media3 hasn't decoded yet; real ID3 metadata overrides
                // this automatically once a track actually starts playing.
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).setAlbumTitle(album.name).build())
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
    data object Artists : LibraryRoute()
    data object Albums : LibraryRoute()
    data class ArtistAlbums(val artist: ArtistSummary) : LibraryRoute()
    data class AlbumDetail(val album: DriveAlbum, val backTo: LibraryRoute) : LibraryRoute()
}

@Composable
private fun MusicDriveApp(
    signInManager: GoogleSignInManager,
    driveRepository: DriveRepository,
    libraryRepository: LibraryRepository,
    albumArtRepository: AlbumArtRepository,
    settingsRepository: SettingsRepository,
    lyricsRepository: LyricsRepository,
    downloadTracker: DownloadTracker,
    mediaController: MediaController?,
    onPlayAlbum: (DriveAlbum, startIndex: Int) -> Unit,
) {
    var state by remember { mutableStateOf<AppState>(AppState.Loading) }
    var libraryRoute by remember { mutableStateOf<LibraryRoute>(LibraryRoute.Artists) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isQueueVisible by remember { mutableStateOf(false) }
    var isLyricsVisible by remember { mutableStateOf(false) }
    var isSettingsVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun selectTopLevelView(mode: LibraryViewMode) {
        libraryRoute = if (mode == LibraryViewMode.ALBUMS) LibraryRoute.Albums else LibraryRoute.Artists
        scope.launch { settingsRepository.setLibraryViewMode(mode) }
    }

    suspend fun loadLibrary(rootFolderId: String, rootFolderName: String?) {
        state = AppState.Loading
        // Reopen on whichever top-level tab (Artists/Albums) the user had open last.
        libraryRoute = when (settingsRepository.libraryViewMode.first()) {
            LibraryViewMode.ALBUMS -> LibraryRoute.Albums
            LibraryViewMode.ARTISTS -> LibraryRoute.Artists
        }
        // Emits cached data first (if any) for an instant browse, then again once the
        // live Drive fetch lands - the route reset above only happens once, up front,
        // so a background refresh mid-browse doesn't kick the user out of an album.
        libraryRepository.loadLibrary(rootFolderId).collect { result ->
            result.fold(
                onSuccess = { albums -> state = AppState.LibraryLoaded(rootFolderName, albums) },
                onFailure = { e -> state = AppState.Error(e.message ?: "Failed to list Drive files") },
            )
        }
    }

    fun onFolderSelected(folderId: String, folderName: String) {
        scope.launch {
            settingsRepository.setLibraryRootFolderId(folderId)
            libraryRepository.clearCache()
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
    val queueState = rememberQueueState(mediaController)
    val downloads by downloadTracker.downloads.collectAsState()
    val cacheLimitBytes by settingsRepository.cacheLimitBytes.collectAsState(initial = SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
    val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val albumSortMode by settingsRepository.albumSortMode.collectAsState(initial = AlbumSortMode.NAME)

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
                            is LibraryRoute.Artists -> Column(modifier = Modifier.fillMaxSize()) {
                                LibraryTopBar(
                                    folderLabel = "Change library folder" +
                                        (current.libraryFolderName?.let { " (currently \"$it\")" } ?: ""),
                                    onChangeFolder = { state = AppState.PickingFolder },
                                    onOpenSettings = { isSettingsVisible = true },
                                    selectedView = LibraryViewMode.ARTISTS,
                                    onSelectView = ::selectTopLevelView,
                                )
                                ArtistListScreen(
                                    artists = current.albums.groupByArtist(),
                                    onArtistClick = { libraryRoute = LibraryRoute.ArtistAlbums(it) },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            is LibraryRoute.Albums -> Column(modifier = Modifier.fillMaxSize()) {
                                LibraryTopBar(
                                    folderLabel = "Change library folder" +
                                        (current.libraryFolderName?.let { " (currently \"$it\")" } ?: ""),
                                    onChangeFolder = { state = AppState.PickingFolder },
                                    onOpenSettings = { isSettingsVisible = true },
                                    selectedView = LibraryViewMode.ALBUMS,
                                    onSelectView = ::selectTopLevelView,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val albumWord = if (current.albums.size == 1) "album" else "albums"
                                    Text(
                                        "${current.albums.size} $albumWord",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    AlbumSortMenuButton(current = albumSortMode, onChange = { mode ->
                                        scope.launch { settingsRepository.setAlbumSortMode(mode) }
                                    })
                                }
                                LibraryScreen(
                                    albums = current.albums.sortedByMode(albumSortMode),
                                    onAlbumClick = { libraryRoute = LibraryRoute.AlbumDetail(it, backTo = LibraryRoute.Albums) },
                                    resolveArt = albumArtRepository::resolveArt,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            is LibraryRoute.ArtistAlbums -> Column(modifier = Modifier.fillMaxSize()) {
                                ScreenHeader(title = route.artist.name, onBack = { libraryRoute = LibraryRoute.Artists }) {
                                    AlbumSortMenuButton(current = albumSortMode, onChange = { mode ->
                                        scope.launch { settingsRepository.setAlbumSortMode(mode) }
                                    })
                                }
                                LibraryScreen(
                                    albums = route.artist.albums.sortedByMode(albumSortMode),
                                    onAlbumClick = { libraryRoute = LibraryRoute.AlbumDetail(it, backTo = route) },
                                    resolveArt = albumArtRepository::resolveArt,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            is LibraryRoute.AlbumDetail -> AlbumDetailScreen(
                                album = route.album,
                                downloads = downloads,
                                onBack = { libraryRoute = route.backTo },
                                onTrackClick = { index ->
                                    onPlayAlbum(route.album, index)
                                    isPlayerExpanded = true
                                },
                                onDownloadTrack = { track -> downloadTracker.downloadTrack(track, route.album.id) },
                                onRemoveTrackDownload = { track -> downloadTracker.removeTrack(track.id) },
                                onDownloadAlbum = { downloadTracker.downloadAlbum(route.album) },
                                onRemoveAlbumDownload = { downloadTracker.removeAlbum(route.album) },
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
                onOpenQueue = { isQueueVisible = true },
                onOpenLyrics = { isLyricsVisible = true },
            )
        }

        if (isQueueVisible) {
            QueueScreen(
                state = queueState,
                onTrackClick = { index ->
                    mediaController?.seekTo(index, 0L)
                    isQueueVisible = false
                },
                onBack = { isQueueVisible = false },
            )
        }

        if (isLyricsVisible) {
            LyricsScreen(
                controller = mediaController,
                playerState = playerUiState,
                lyricsRepository = lyricsRepository,
                onBack = { isLyricsVisible = false },
            )
        }

        if (isSettingsVisible) {
            SettingsScreen(
                cacheLimitBytes = cacheLimitBytes,
                onCacheLimitChange = { bytes -> scope.launch { settingsRepository.setCacheLimitBytes(bytes) } },
                themeMode = themeMode,
                onThemeModeChange = { mode -> scope.launch { settingsRepository.setThemeMode(mode) } },
                defaultAlbumSortMode = albumSortMode,
                onDefaultAlbumSortModeChange = { mode -> scope.launch { settingsRepository.setAlbumSortMode(mode) } },
                onBack = { isSettingsVisible = false },
            )
        }
    }
}

@Composable
private fun LibraryTopBar(
    folderLabel: String,
    onChangeFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    selectedView: LibraryViewMode,
    onSelectView: (LibraryViewMode) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onChangeFolder, modifier = Modifier.weight(1f)) {
                Text(folderLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedView == LibraryViewMode.ARTISTS,
                onClick = { onSelectView(LibraryViewMode.ARTISTS) },
                label = { Text("Artists") },
            )
            FilterChip(
                selected = selectedView == LibraryViewMode.ALBUMS,
                onClick = { onSelectView(LibraryViewMode.ALBUMS) },
                label = { Text("Albums") },
            )
        }
    }
}

@Composable
private fun AlbumSortMenuButton(current: AlbumSortMode, onChange: (AlbumSortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort albums")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Name (A–Z)") },
                onClick = { onChange(AlbumSortMode.NAME); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Track count") },
                onClick = { onChange(AlbumSortMode.TRACK_COUNT); expanded = false },
            )
        }
    }
}
