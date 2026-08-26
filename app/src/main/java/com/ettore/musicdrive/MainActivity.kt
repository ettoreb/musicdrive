package com.ettore.musicdrive

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ettore.musicdrive.auth.ContextDriveAuthorizer
import com.ettore.musicdrive.auth.DriveAuthorizationManager
import com.ettore.musicdrive.auth.GoogleSignInManager
import com.ettore.musicdrive.auth.SignInResult
import com.ettore.musicdrive.data.LibraryRepository
import com.ettore.musicdrive.data.LyricsRepository
import com.ettore.musicdrive.data.PlayStatsRepository
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
import com.ettore.musicdrive.ui.HomeGridItem
import com.ettore.musicdrive.ui.HomeScreen
import com.ettore.musicdrive.ui.LibraryScreen
import com.ettore.musicdrive.ui.LyricsScreen
import com.ettore.musicdrive.ui.MiniPlayerBar
import com.ettore.musicdrive.ui.QueueScreen
import com.ettore.musicdrive.ui.ScreenHeader
import com.ettore.musicdrive.ui.SearchOverlayScreen
import com.ettore.musicdrive.ui.SettingsScreen
import com.ettore.musicdrive.ui.StatsScreen
import com.ettore.musicdrive.ui.TopArtistItem
import com.ettore.musicdrive.ui.UNKNOWN_ARTIST
import com.ettore.musicdrive.ui.groupByArtist
import com.ettore.musicdrive.ui.rememberPlayerUiState
import com.ettore.musicdrive.ui.rememberQueueState
import com.ettore.musicdrive.ui.sortedByMode
import com.ettore.musicdrive.ui.theme.MusicDriveTheme
import com.ettore.musicdrive.ui.withoutAudioExtension
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private lateinit var playStatsRepository: PlayStatsRepository
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
        albumArtRepository = AlbumArtRepository(
            this,
            tokenProvider,
            app.database.albumYearDao(),
            app.database.trackOrderDao(),
            app.database.albumTagsDao(),
        )
        settingsRepository = app.settingsRepository
        lyricsRepository = LyricsRepository(app.database.lyricsDao())
        downloadTracker = app.downloadTracker
        playStatsRepository = app.playStatsRepository

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
                    streamingCache = app.streamingCache,
                    playStatsRepository = playStatsRepository,
                    mediaController = mediaController,
                    onPlayAlbum = ::playAlbum,
                    isSignedInThisProcess = app.isSignedInThisProcess,
                    onSignedIn = { app.isSignedInThisProcess = true },
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
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(track.name.withoutAudioExtension()).setAlbumTitle(album.name).build(),
                )
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
    data object Home : LibraryRoute()
    data object Library : LibraryRoute()
    data object Settings : LibraryRoute()
    data class ArtistAlbums(val artist: ArtistSummary) : LibraryRoute()
    data class AlbumDetail(val album: DriveAlbum, val backTo: LibraryRoute) : LibraryRoute()
}

/** The three permanent bottom-nav destinations; Library covers browsing plus any drill-down within it. */
private enum class BottomTab { HOME, LIBRARY, SETTINGS }

private fun LibraryRoute.bottomTab(): BottomTab = when (this) {
    is LibraryRoute.Home -> BottomTab.HOME
    is LibraryRoute.Settings -> BottomTab.SETTINGS
    is LibraryRoute.Library, is LibraryRoute.ArtistAlbums, is LibraryRoute.AlbumDetail -> BottomTab.LIBRARY
}

/** Stable per-screen identity for [rememberSaveableStateHolder] - see its call site for why. */
private fun LibraryRoute.stateKey(): String = when (this) {
    is LibraryRoute.Home -> "home"
    is LibraryRoute.Library -> "library"
    is LibraryRoute.Settings -> "settings"
    is LibraryRoute.ArtistAlbums -> "artist:${artist.name}"
    is LibraryRoute.AlbumDetail -> "album:${album.id}"
}

private const val HOME_GRID_LIMIT = 12

@Composable
private fun MusicDriveApp(
    signInManager: GoogleSignInManager,
    driveRepository: DriveRepository,
    libraryRepository: LibraryRepository,
    albumArtRepository: AlbumArtRepository,
    settingsRepository: SettingsRepository,
    lyricsRepository: LyricsRepository,
    downloadTracker: DownloadTracker,
    streamingCache: Cache,
    playStatsRepository: PlayStatsRepository,
    mediaController: MediaController?,
    onPlayAlbum: (DriveAlbum, startIndex: Int) -> Unit,
    isSignedInThisProcess: Boolean,
    onSignedIn: () -> Unit,
) {
    var state by remember { mutableStateOf<AppState>(AppState.Loading) }
    var libraryRoute by remember { mutableStateOf<LibraryRoute>(LibraryRoute.Home) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isQueueVisible by remember { mutableStateOf(false) }
    var isLyricsVisible by remember { mutableStateOf(false) }
    var isStatsVisible by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var currentRootFolderId by remember { mutableStateOf<String?>(null) }
    var isRefreshingAlbum by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // This app hand-rolls routing with a plain `when (route)` instead of a navigation library,
    // which means a branch's own remembered state (a grid's scroll position, above all) is torn
    // down the moment you leave it and rebuilt from scratch when you come back - found live:
    // scroll deep into the Albums grid, open an album, play a track, collapse the player, back
    // out, and the grid had reset to the top. A SaveableStateHolder keyed per logical
    // screen (LibraryRoute.stateKey(), plus the Library route's own Artists/Albums sub-tab)
    // is the standard Compose fix for exactly this - it keeps each screen's saveable state
    // (LazyGridState/LazyListState both have a built-in Saver) in a map that outlives the
    // branch being torn down, since the holder itself lives here, above the `when`.
    val routeStateHolder = rememberSaveableStateHolder()

    fun selectBottomTab(tab: BottomTab) {
        libraryRoute = when (tab) {
            BottomTab.HOME -> LibraryRoute.Home
            BottomTab.LIBRARY -> LibraryRoute.Library
            BottomTab.SETTINGS -> LibraryRoute.Settings
        }
    }

    suspend fun loadLibrary(rootFolderId: String, rootFolderName: String?) {
        currentRootFolderId = rootFolderId
        state = AppState.Loading
        // Always reopens on Home, matching every mainstream music app's launch behavior -
        // Search/Library/Settings are reachable in one tap from the bottom nav regardless.
        libraryRoute = LibraryRoute.Home
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

    // Pull-to-refresh on the album detail page: re-fetches the WHOLE library from Drive (same
    // cost as the background refresh loadLibrary already does on every launch, cheap at
    // personal-library scale) rather than just this one album's folder, so a newly added song
    // shows up whichever album/artist view the user goes back to, not just this screen. Keeps
    // the current route instead of resetting to Home, and swaps in the refreshed album object
    // (by id) so the currently-open AlbumDetail screen shows the new track immediately.
    fun refreshCurrentAlbum(albumId: String) {
        val rootFolderId = currentRootFolderId ?: return
        if (isRefreshingAlbum) return
        scope.launch {
            isRefreshingAlbum = true
            try {
                libraryRepository.refreshLibrary(rootFolderId).onSuccess { albums ->
                    val current = state as? AppState.LibraryLoaded ?: return@onSuccess
                    state = current.copy(albums = albums)
                    val refreshedAlbum = albums.find { it.id == albumId }
                    val route = libraryRoute
                    if (refreshedAlbum != null && route is LibraryRoute.AlbumDetail && route.album.id == albumId) {
                        libraryRoute = route.copy(album = refreshedAlbum)
                    }
                }
            } finally {
                isRefreshingAlbum = false
            }
        }
    }

    fun onFolderSelected(folderId: String, folderName: String) {
        scope.launch {
            settingsRepository.setLibraryRootFolderId(folderId)
            libraryRepository.clearCache()
            loadLibrary(folderId, folderName)
        }
    }

    suspend fun proceedToLibraryOrPicker() {
        val rootFolderId = settingsRepository.libraryRootFolderId.first()
        if (rootFolderId == null) {
            state = AppState.PickingFolder
        } else {
            loadLibrary(rootFolderId, rootFolderName = null)
        }
    }

    fun signInAndProceed(interactive: Boolean) {
        state = AppState.Loading
        scope.launch {
            val signIn = if (interactive) signInManager.signInInteractive() else signInManager.signInSilently()
            when (signIn) {
                is SignInResult.Success -> {
                    onSignedIn()
                    proceedToLibraryOrPicker()
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

    // Try to resume a previously authorized account silently before ever showing the sign-in
    // button - but only hit Credential Manager once per process. Recomposing this effect (e.g.
    // the OS recreating the Activity after reclaiming it in the background) would otherwise
    // re-invoke getCredential() every time, which can flash a brief system UI even in "silent"
    // mode; isSignedInThisProcess survives that recreation, so once we're already authorized this
    // process, just re-derive the route from local state instead.
    LaunchedEffect(Unit) {
        if (isSignedInThisProcess) {
            proceedToLibraryOrPicker()
        } else {
            signInAndProceed(interactive = false)
        }
    }

    val playerUiState = rememberPlayerUiState(mediaController)
    val queueState = rememberQueueState(mediaController)
    val downloads by downloadTracker.downloads.collectAsState()
    val cacheLimitBytes by settingsRepository.cacheLimitBytes.collectAsState(initial = SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
    val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val albumSortMode by settingsRepository.albumSortMode.collectAsState(initial = AlbumSortMode.YEAR)
    val libraryViewMode by settingsRepository.libraryViewMode.collectAsState(initial = LibraryViewMode.ARTISTS)
    val topTrackCounts by playStatsRepository.observeTopTracks(HOME_GRID_LIMIT).collectAsState(initial = emptyList())
    val allPlayCounts by playStatsRepository.observeAll().collectAsState(initial = emptyList())

    // Refreshed each time Settings is opened (not continuously) - a personal-library-scale disk
    // walk / Cache.getCacheSpace() call is cheap but pointless to repeat while the user isn't
    // even looking at the Storage section.
    var streamingCacheUsageBytes by remember { mutableStateOf(0L) }
    var artDiskUsageBytes by remember { mutableStateOf(0L) }
    var freeStorageBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(libraryRoute) {
        if (libraryRoute is LibraryRoute.Settings) {
            streamingCacheUsageBytes = streamingCache.cacheSpace
            artDiskUsageBytes = albumArtRepository.diskUsageBytes()
            // Real device free space (same internal-storage partition filesDir/cacheDir both
            // live on) - shown in the storage dialog so the size slider can't offer a limit
            // bigger than what's actually available right now.
            freeStorageBytes = StatFs(context.filesDir.path).availableBytes
        }
    }

    val loadedAlbums = (state as? AppState.LibraryLoaded)?.albums ?: emptyList()

    // trackId -> (its album, its index within that album) so a Home tile, or the
    // synthetic Liked Songs playlist, can resume playback the same way AlbumDetailScreen
    // does (play the whole album starting from that track).
    val trackLocations = remember(loadedAlbums) {
        loadedAlbums.flatMap { album ->
            album.tracks.mapIndexed { index, track -> track.id to Triple(album, track, index) }
        }.toMap()
    }

    // Fills the player's cover-art gap on skip: Player.mediaMetadata.artworkData comes from
    // Media3 re-extracting ID3 art out of the new track's stream, which lags a beat behind the
    // transition itself (the placeholder briefly flashes). AlbumArtRepository already resolved
    // and disk/memory-cached this album's art for the library grid, so reusing it here is
    // effectively instant instead of waiting on the live stream.
    val currentPlayingAlbum = trackLocations[playerUiState.mediaId]?.first
    var currentTrackFallbackArt by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(currentPlayingAlbum?.id) {
        currentTrackFallbackArt = currentPlayingAlbum?.let { albumArtRepository.resolveArt(it) }
    }

    // Release years aren't known up front (they're resolved lazily, tag-first-then-iTunes,
    // same as art) but sorting by year needs them for the whole visible list at once, so this
    // eagerly resolves every loaded album's year in the background as soon as the library loads.
    // Bounded concurrency: resolving a year opens a MediaMetadataRetriever (a heavyweight,
    // binder-backed HTTP session) per album - firing one per album at once for a large library
    // starves the binder thread pool and stalls the whole app (found live: 37 concurrent
    // launches logged repeated "binder thread pool starved" and the sort never completed).
    var albumYears by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    val yearResolveSemaphore = remember { Semaphore(3) }
    LaunchedEffect(loadedAlbums) {
        loadedAlbums.forEach { album ->
            if (album.id !in albumYears) {
                launch {
                    yearResolveSemaphore.withPermit {
                        val year = albumArtRepository.resolveYear(album)
                        albumYears = albumYears + (album.id to year)
                    }
                }
            }
        }
    }
    val yearOf: (DriveAlbum) -> Int? = { albumYears[it.id] }

    // Embedded ID3 tags (TALB/TPE2/TPE1), when present, correct the Drive-folder-derived
    // name/artistHint (see AlbumArtRepository.resolveDisplayTags) - resolved eagerly for the
    // whole library, same pattern and same bounded-concurrency reasoning as year resolution
    // above, since the corrected name/artist feeds grouping/sorting/search immediately, not
    // just one open screen. tagsResolvedIds (not the album objects themselves) tracks which
    // albums have already been probed, since a successful correction replaces the album object
    // in `state` - there's no other stable "already resolved" signal to check against.
    var tagsResolvedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val tagsResolveSemaphore = remember { Semaphore(3) }
    LaunchedEffect(loadedAlbums) {
        loadedAlbums.forEach { album ->
            if (album.id !in tagsResolvedIds) {
                launch {
                    tagsResolveSemaphore.withPermit {
                        val resolved = albumArtRepository.resolveDisplayTags(album)
                        tagsResolvedIds = tagsResolvedIds + album.id
                        if (resolved.name != album.name || resolved.artistHint != album.artistHint) {
                            val current = state as? AppState.LibraryLoaded ?: return@withPermit
                            state = current.copy(albums = current.albums.map { if (it.id == resolved.id) resolved else it })
                        }
                    }
                }
            }
        }
    }

    // Real track order (see AlbumArtRepository.resolveTrackOrder) is resolved lazily per album,
    // only for whichever album is currently open - unlike year resolution above, doing this
    // eagerly for the WHOLE library would mean one MediaMetadataRetriever probe per TRACK
    // (1000+ for a real library) instead of per album, far too slow to run on every launch.
    // Merged back into both `state` and the current route (same pattern as refreshCurrentAlbum)
    // so AlbumDetailScreen re-renders with the corrected order and Home/Search/playback - which
    // all read tracks off this same shared album list - stay consistent with what's displayed.
    LaunchedEffect((libraryRoute as? LibraryRoute.AlbumDetail)?.album?.id) {
        val route = libraryRoute as? LibraryRoute.AlbumDetail ?: return@LaunchedEffect
        val resolved = albumArtRepository.resolveTrackOrder(route.album)
        val current = state as? AppState.LibraryLoaded ?: return@LaunchedEffect
        state = current.copy(albums = current.albums.map { if (it.id == resolved.id) resolved else it })
        val stillOnRoute = libraryRoute
        if (stillOnRoute is LibraryRoute.AlbumDetail && stillOnRoute.album.id == resolved.id) {
            libraryRoute = stillOnRoute.copy(album = resolved)
        }
    }

    // Hoisted out of the Home route so the Stats screen can reuse the same computed
    // lists without a separate route-scoped duplicate.
    val homeItems = remember(topTrackCounts, trackLocations) {
        topTrackCounts.mapNotNull { playCount ->
            trackLocations[playCount.trackId]?.let { (album, track, index) ->
                HomeGridItem(album, track, index, playCount.playCount)
            }
        }
    }
    val likedSongsAlbum = remember(homeItems) {
        if (homeItems.isEmpty()) {
            null
        } else {
            DriveAlbum(id = "liked-songs", name = "Liked Songs", artistHint = null, tracks = homeItems.map { it.track })
        }
    }
    val artistSummaries = remember(loadedAlbums) {
        loadedAlbums.groupByArtist().associateBy { it.name }
    }
    val topArtists = remember(allPlayCounts, trackLocations, artistSummaries) {
        val totals = mutableMapOf<String, Int>()
        allPlayCounts.forEach { playCount ->
            val album = trackLocations[playCount.trackId]?.first ?: return@forEach
            val artistName = album.artistHint ?: UNKNOWN_ARTIST
            totals[artistName] = (totals[artistName] ?: 0) + playCount.playCount
        }
        totals.entries.sortedByDescending { it.value }.take(6).mapNotNull { (name, total) ->
            artistSummaries[name]?.let { TopArtistItem(it, total) }
        }
    }

    // Counts a "play" on every track transition (manual skip, auto-advance, or the
    // initial track of a newly built playlist all count) - simple and good enough
    // for a personal most-played dashboard, not scrobble-grade precision.
    DisposableEffect(mediaController) {
        val controller = mediaController ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val trackId = mediaItem?.mediaId ?: return
                scope.launch { playStatsRepository.recordPlay(trackId) }
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    // Drill-down routes (an album's tracks, or an artist's albums) step back to where they
    // were opened from, matching their on-screen back arrow, instead of the system default
    // (which would exit the app). Registered before the overlay BackHandlers below so those
    // take priority when both are enabled at once (e.g. the full player open on top of an
    // album's track list).
    BackHandler(
        enabled = libraryRoute.let { it is LibraryRoute.AlbumDetail || it is LibraryRoute.ArtistAlbums },
    ) {
        when (val route = libraryRoute) {
            is LibraryRoute.AlbumDetail -> libraryRoute = route.backTo
            is LibraryRoute.ArtistAlbums -> libraryRoute = LibraryRoute.Library
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // Only on the four top-level tabs - AlbumDetail/ArtistAlbums already have their
                // own back-button+title ScreenHeader, and stacking both reads as clutter.
                val onTopLevelRoute = libraryRoute.let { it !is LibraryRoute.AlbumDetail && it !is LibraryRoute.ArtistAlbums }
                if (state is AppState.LibraryLoaded && onTopLevelRoute) {
                    MusicDriveTopBar()
                }
            },
            bottomBar = {
                if (state is AppState.LibraryLoaded) {
                    MusicDriveBottomBar(selected = libraryRoute.bottomTab(), onSelect = ::selectBottomTab)
                }
            },
        ) { innerPadding ->
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

                        is AppState.LibraryLoaded -> {
                        val route = libraryRoute
                        routeStateHolder.SaveableStateProvider(route.stateKey()) {
                        when (route) {
                            is LibraryRoute.Home -> Column(modifier = Modifier.fillMaxSize()) {
                                HomeScreen(
                                    topTracks = homeItems,
                                    topArtists = topArtists,
                                    likedSongsCount = likedSongsAlbum?.tracks?.size ?: 0,
                                    onTrackClick = { item ->
                                        onPlayAlbum(item.album, item.trackIndex)
                                        isPlayerExpanded = true
                                    },
                                    onArtistClick = { libraryRoute = LibraryRoute.ArtistAlbums(it) },
                                    onLikedSongsClick = {
                                        likedSongsAlbum?.let {
                                            onPlayAlbum(it, 0)
                                            isPlayerExpanded = true
                                        }
                                    },
                                    onOpenSearch = { isSearchVisible = true },
                                    resolveArt = albumArtRepository::resolveArt,
                                    resolveArtistArt = albumArtRepository::resolveArtistArt,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            is LibraryRoute.Library -> Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = libraryViewMode == LibraryViewMode.ARTISTS,
                                        onClick = { scope.launch { settingsRepository.setLibraryViewMode(LibraryViewMode.ARTISTS) } },
                                        label = { Text("Artists") },
                                    )
                                    FilterChip(
                                        selected = libraryViewMode == LibraryViewMode.ALBUMS,
                                        onClick = { scope.launch { settingsRepository.setLibraryViewMode(LibraryViewMode.ALBUMS) } },
                                        label = { Text("Albums") },
                                    )
                                }
                                // Nested under the same routeStateHolder used above - the Artists/Albums
                                // toggle is a `when` branch switch just like the outer route one, so its
                                // grids need the same fix to keep their own scroll position independently.
                                routeStateHolder.SaveableStateProvider("library:$libraryViewMode") {
                                when (libraryViewMode) {
                                    LibraryViewMode.ARTISTS -> ArtistListScreen(
                                        artists = current.albums.groupByArtist(),
                                        onArtistClick = { libraryRoute = LibraryRoute.ArtistAlbums(it) },
                                        resolveArtistArt = albumArtRepository::resolveArtistArt,
                                        modifier = Modifier.weight(1f),
                                    )

                                    LibraryViewMode.ALBUMS -> Column(modifier = Modifier.weight(1f)) {
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
                                            albums = current.albums.sortedByMode(albumSortMode, yearOf),
                                            onAlbumClick = { libraryRoute = LibraryRoute.AlbumDetail(it, backTo = LibraryRoute.Library) },
                                            resolveArt = albumArtRepository::resolveArt,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                }
                            }

                            is LibraryRoute.Settings -> SettingsScreen(
                                libraryFolderLabel = "Change library folder" +
                                    (current.libraryFolderName?.let { " (currently \"$it\")" } ?: ""),
                                onChangeFolder = { state = AppState.PickingFolder },
                                cacheLimitBytes = cacheLimitBytes,
                                onCacheLimitChange = { bytes -> scope.launch { settingsRepository.setCacheLimitBytes(bytes) } },
                                streamingCacheUsageBytes = streamingCacheUsageBytes,
                                artDiskUsageBytes = artDiskUsageBytes,
                                freeStorageBytes = freeStorageBytes,
                                downloads = downloads,
                                albums = loadedAlbums,
                                onRemoveDownloadedAlbum = { album -> downloadTracker.removeAlbum(album) },
                                onRemoveAllDownloads = { downloadTracker.removeAll() },
                                themeMode = themeMode,
                                onThemeModeChange = { mode -> scope.launch { settingsRepository.setThemeMode(mode) } },
                                onOpenStats = { isStatsVisible = true },
                                onBack = { libraryRoute = LibraryRoute.Home },
                                modifier = Modifier.fillMaxSize(),
                            )

                            is LibraryRoute.ArtistAlbums -> Column(modifier = Modifier.fillMaxSize()) {
                                ScreenHeader(title = route.artist.name, onBack = { libraryRoute = LibraryRoute.Library }) {
                                    AlbumSortMenuButton(current = albumSortMode, onChange = { mode ->
                                        scope.launch { settingsRepository.setAlbumSortMode(mode) }
                                    })
                                }
                                LibraryScreen(
                                    albums = route.artist.albums.sortedByMode(albumSortMode, yearOf),
                                    onAlbumClick = { libraryRoute = LibraryRoute.AlbumDetail(it, backTo = route) },
                                    resolveArt = albumArtRepository::resolveArt,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            is LibraryRoute.AlbumDetail -> AlbumDetailScreen(
                                album = route.album,
                                downloads = downloads,
                                resolveArt = albumArtRepository::resolveArt,
                                currentlyPlayingTrackId = playerUiState.mediaId.ifBlank { null },
                                isPlaying = playerUiState.isPlaying,
                                isRefreshing = isRefreshingAlbum,
                                onRefresh = { refreshCurrentAlbum(route.album.id) },
                                onBack = { libraryRoute = route.backTo },
                                onTrackClick = { index ->
                                    onPlayAlbum(route.album, index)
                                    isPlayerExpanded = true
                                },
                                onDownloadAlbum = { downloadTracker.downloadAlbum(route.album) },
                                onRemoveAlbumDownload = { downloadTracker.removeAlbum(route.album) },
                            )
                        }
                        }
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
                        fallbackArt = currentTrackFallbackArt,
                    )
                }
            }
        }

        if (isPlayerExpanded) {
            BackHandler { isPlayerExpanded = false }
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
                onToggleShuffle = {
                    mediaController?.let { controller ->
                        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
                    }
                },
                onToggleRepeat = {
                    mediaController?.let { controller ->
                        controller.repeatMode = when (controller.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                },
                fallbackArt = currentTrackFallbackArt,
            )
        }

        if (isQueueVisible) {
            BackHandler { isQueueVisible = false }
            QueueScreen(
                state = queueState,
                isPlaying = playerUiState.isPlaying,
                onTrackClick = { index ->
                    mediaController?.seekTo(index, 0L)
                    isQueueVisible = false
                },
                onBack = { isQueueVisible = false },
            )
        }

        if (isLyricsVisible) {
            BackHandler { isLyricsVisible = false }
            LyricsScreen(
                controller = mediaController,
                playerState = playerUiState,
                lyricsRepository = lyricsRepository,
                onBack = { isLyricsVisible = false },
            )
        }

        if (isStatsVisible) {
            BackHandler { isStatsVisible = false }
            StatsScreen(
                topTracks = homeItems,
                topArtists = topArtists,
                onBack = { isStatsVisible = false },
                resolveArt = albumArtRepository::resolveArt,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isSearchVisible) {
            BackHandler { isSearchVisible = false }
            SearchOverlayScreen(
                albums = loadedAlbums,
                onAlbumClick = {
                    isSearchVisible = false
                    libraryRoute = LibraryRoute.AlbumDetail(it, backTo = LibraryRoute.Home)
                },
                onTrackClick = { album, index ->
                    isSearchVisible = false
                    onPlayAlbum(album, index)
                    isPlayerExpanded = true
                },
                onBack = { isSearchVisible = false },
                resolveArt = albumArtRepository::resolveArt,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Persistent app branding (icon + wordmark), always visible at the top of every top-level tab -
 * lives in Scaffold's topBar slot so it never scrolls away with a tab's own content, the same way
 * MusicDriveBottomBar below is always pinned at the bottom.
 */
@Composable
private fun MusicDriveTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = rememberAppIconBitmap(),
            contentDescription = null,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(
            "MusicDrive",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * ic_launcher is an <adaptive-icon> XML (background + foreground layers), which
 * androidx.compose.ui.res.painterResource does NOT support - it throws
 * IllegalArgumentException("Only VectorDrawables and rasterized asset types are supported"),
 * confirmed live (real launch crash on a physical device, not a hypothetical). Rasterizing the
 * drawable into a bitmap ourselves - which correctly composites both adaptive-icon layers, same as
 * the launcher does - sidesteps that restriction entirely.
 */
@Composable
private fun rememberAppIconBitmap(): ImageBitmap {
    val context = LocalContext.current
    return remember {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = AndroidCanvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }
}

@Composable
private fun MusicDriveBottomBar(selected: BottomTab, onSelect: (BottomTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == BottomTab.HOME,
            onClick = { onSelect(BottomTab.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = selected == BottomTab.LIBRARY,
            onClick = { onSelect(BottomTab.LIBRARY) },
            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = selected == BottomTab.SETTINGS,
            onClick = { onSelect(BottomTab.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
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
                text = { Text("Release year (newest first)") },
                onClick = { onChange(AlbumSortMode.YEAR); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Name (A–Z)") },
                onClick = { onChange(AlbumSortMode.NAME); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Track count") },
                onClick = { onChange(AlbumSortMode.TRACK_COUNT); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Artist name (A–Z)") },
                onClick = { onChange(AlbumSortMode.ARTIST_NAME); expanded = false },
            )
        }
    }
}
