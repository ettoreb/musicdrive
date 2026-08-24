# MusicDrive

Android music player that streams from Google Drive, functionally similar to
YouTube Music (not a visual clone). Personal/sideloaded app, not for Play Store.

Keep [README.md](README.md)'s status table and feature list in sync whenever
a roadmap item lands or the plan changes — it's the GitHub-facing summary of
this file.

## Environment
- Ubuntu, JDK 17, Android Studio (tarball install at /opt/android-studio)
- Package / namespace: com.ettore.musicdrive
- minSdk 26, compileSdk/targetSdk 37, AGP 9.3.1, Kotlin 2.4.10, Jetpack Compose
- AGP 9+ has BUILT-IN Kotlin support: do NOT apply org.jetbrains.kotlin.android,
  it errors ("no longer required for Kotlin support since AGP 9.0"). Only the
  org.jetbrains.kotlin.plugin.compose plugin is still applied explicitly.
  jvmTarget is inherited from compileOptions.targetCompatibility, so no
  kotlin { compilerOptions {} } block is needed unless overriding it.
- google-api-client / google-api-services-drive jars collide on
  META-INF/INDEX.LIST and META-INF/DEPENDENCIES; excluded via
  android.packaging.resources.excludes in app/build.gradle.kts.
- A physical Pixel 7 (codename `panther`) is available over USB and shows up
  fine in `adb devices` — the earlier "no physical device" limitation no
  longer applies; either it or the emulator works for live testing.
- AVD `musicdrive_test` (Pixel 6, API 34, google_apis x86_64) was created via
  cmdline-tools/sdkmanager+avdmanager (~/Android/Sdk had no cmdline-tools, no
  AVD, no system image initially). Boot with the emulator at
  ~/Android/Sdk/emulator/emulator -avd musicdrive_test (drop -no-window to see
  the GUI on the host's Wayland session; DISPLAY=:0 is already set there).
- google_apis image has NO Play Store; add a Google account manually via
  Settings > Accounts > Add account > Google (needed for Credential
  Manager / drive.readonly to have anything to authenticate). The signed-in
  account must also be added as a Test user on the OAuth consent screen in
  Google Cloud Console, or sign-in fails with Error 403: access_denied.
- Emulator gotcha: the simulated WiFi radio ("AndroidWifi") sometimes drops
  and reconnects a few minutes after boot, causing a transient app-level
  `UnknownHostException` on the exact request that lands during the blip
  (OS-level `ping` still resolves fine via the other network, so it's easy to
  misdiagnose as a real DNS bug). Fix: `adb shell svc wifi disable` to force
  the emulator onto its cellular (eth0) network, which doesn't flap.
- Emulator gotcha: after the emulator has been up a long time (many hours,
  many app restarts), Google Play services itself can start hanging
  ("Google Play services isn't responding" ANR) or hand back a stale Drive
  access token that 401s every request even after Retry — this is emulator
  resource exhaustion, not an app bug (confirmed: happened with zero auth
  code changes in the diff). Fix: fully kill the emulator (`adb emu kill`,
  then `kill -9` the qemu-system process if it lingers) and boot fresh
  rather than debugging it in place.

## Common commands
- Build debug APK: `./gradlew assembleDebug`
- Install to running emulator/device: `./gradlew installDebug`
- Unit tests (JVM, `app/src/test`): `./gradlew test`
- Instrumented tests (`app/src/androidTest`, needs a booted
  emulator/device): `./gradlew connectedAndroidTest`
- Run a single test class: `./gradlew test --tests "com.ettore.musicdrive.SomeTest"`
- Lint: `./gradlew lint`
- `local.properties` must contain `GOOGLE_WEB_CLIENT_ID` (see Auth section)
  before any build that touches sign-in, or the OAuth client id is empty at
  runtime — build itself still succeeds either way.
- No real unit/instrumented tests exist yet beyond the default
  `ExampleUnitTest` template; verification so far has been live, manual
  testing on the `musicdrive_test` emulator (see entries under Current
  status).

## Core requirements
1. Stream audio files from Google Drive
2. Automatic streaming cache with a USER-CONFIGURABLE size cap, LRU eviction
   when the cap is exceeded
3. Separate, permanent offline downloads (per-song and per-album) that are
   NEVER auto-evicted
4. Background playback, media notification, queue, mini-player + full player
5. User picks which Drive folder is the music library root, instead of
   scanning all Drive audio files
6. Material 3 UI throughout
7. Album grid + album detail view, YouTube Music-style (not a visual clone)
8. Mini-player + full-screen player, YouTube Music-style (expand/collapse,
   queue, seek)
9. Lyrics: embedded tags first, LRCLIB fallback when missing
10. Album covers: embedded art first, online fallback when missing
11. Android Auto support

## Architecture decisions already made
- Playback: Media3 / ExoPlayer, MediaSessionService
- Two SEPARATE SimpleCache instances, different directories:
  - streaming cache: custom `AdjustableLruEvictor` reading its limit live from
    DataStore, so the user's slider takes effect without recreating the player
  - download cache: `NoOpCacheEvictor`, written only via Media3 DownloadManager
- Playback source resolution order: download cache -> streaming cache -> network
- Both caches MUST be singletons initialised in Application.onCreate().
  Two SimpleCache instances on the same directory will crash.
- Library model: Drive folder = album, but NOT necessarily a direct child of
  the library root — real libraries nest differently (e.g. root/Album vs
  root/Artist/Album, confirmed against an actual Drive with the latter
  layout). DriveRepository.collectAlbumFolderIds() does a depth-first search
  from the root and treats a folder as an album as soon as it directly
  contains an audio file, recursing into subfolders otherwise. This is a
  correctness-critical detail, not a simplification to "fix later" — a fixed
  root-children-are-albums assumption silently returns zero tracks on any
  library with an extra nesting level.
- Room caches the Drive index so the library browses instantly offline
- Settings persisted in DataStore (key: cache_limit_bytes, default 2 GB)
- Music folder root: in-app Drive folder browser (reuse the existing Drive
  API client, no separate Google Picker API/key needed) to choose the root
  folder; its id persisted in DataStore (key: library_root_folder_id).
- Lyrics: Media3's extractor already parses embedded ID3 USLT/SYLT and FLAC
  LYRICS tags as part of format metadata during extraction — read that first,
  no separate tagging library needed. Fall back to LRCLIB (free, no API key,
  https://lrclib.net, matched by artist/title/duration) when no embedded
  lyrics are found. Cache fetched lyrics in Room keyed by track id.
- Album covers: implemented — see AlbumArtRepository under Current status.
  Embedded art via MediaMetadataRetriever, disk-cached in cacheDir (not
  Room, no DB needed for this). iTunes Search API fallback.
- Android Auto: extend MusicPlaybackService to MediaLibraryService
  (implement onGetLibraryRoot/onGetChildren over the same Room-cached Drive
  index used by the phone UI), plus an automotive_app_desc.xml declaring
  <uses name="media"/> and the matching manifest meta-data. No separate
  playback path — same MediaSession the phone UI drives.

## Auth (highest-risk area)
- Legacy Drive Android API and legacy Google Sign-In are deprecated. Use:
  - Credential Manager + GetGoogleIdOption for authentication
  - Identity.getAuthorizationClient + AuthorizationRequest for the Drive scope
- Scope: https://www.googleapis.com/auth/drive.readonly
- `setServerClientId()` requires the WEB client ID, not the Android one
- Web client ID lives in local.properties as GOOGLE_WEB_CLIENT_ID and is exposed
  via buildConfigField. Never commit it.
- Access tokens expire ~1h. A token provider must cache the token and force a
  refresh on 401, or ExoPlayer dies mid-song. authorize() refreshes silently
  once consent is granted.
- Google Cloud project is in Testing mode (no verification needed, 100 test
  users). Refresh tokens expire after 7 days in this mode.
- Release builds need a SECOND Android OAuth client with the release SHA-1.

## Planned package structure
com.ettore.musicdrive/
  auth/            GoogleSignInManager, DriveAuthorizationManager, DriveTokenProvider
  data/            LibraryRepository (bridges drive + local)
  data/drive/      Drive API wrapper, repositories
  data/local/      DataStore settings repository
  data/local/room/ Room entities + DAOs (Drive index cache)
  playback/        MusicPlaybackService, cache providers, LayeredDataSourceFactory
  download/        MusicDownloadService, DownloadHolder
  ui/              library, album detail, player, queue, settings

## Build notes
- Exclude org.apache.httpcomponents from the Google API client deps or you get
  duplicate-class build failures
- Release build needs R8 keep rules for com.google.api.** (heavy reflection)

## Current status
Google Cloud Console configured (Drive API enabled, consent screen, Android +
Web OAuth clients). Compose is wired up. Auth module implemented:
- auth/GoogleSignInManager.kt — Credential Manager sign-in. signInSilently()
  (filterByAuthorizedAccounts=true) is tried first on every launch so the
  user isn't re-prompted; signInInteractive() (account picker) is the
  fallback shown only on SignInResult.NoCredential.
- auth/DriveAuthorizationManager.kt — Identity.getAuthorizationClient consent
  flow for drive.readonly, via ActivityResultLauncher<IntentSenderRequest>
- auth/DriveTokenProvider.kt — in-memory token cache, forceRefresh param
- data/drive/DriveRepository.kt — lists audio files via com.google.api.services.drive

Playback foundation implemented:
- data/local/SettingsRepository.kt — DataStore, cache_limit_bytes (2 GB default)
- playback/AdjustableLruEvictor.kt — CacheEvictor with a live-adjustable maxBytes
- MusicDriveApplication.kt — owns the two SimpleCache singletons (streaming:
  cacheDir + AdjustableLruEvictor; download: filesDir + NoOpCacheEvictor) and
  a driveTokenProvider that's non-null from Application.onCreate() onward
  (see "Playback optimizations" below — this used to be nullable/cold-start-unsafe)
- playback/DriveDataSourceFactory.kt — AuthenticatingHttpDataSource injects a
  fresh Bearer token per request and retries once on 401; layered
  CacheDataSource.Factory chain (download cache, read-only here -> streaming
  cache, read+write -> auth http). Matches the download -> streaming ->
  network resolution order.
- playback/MusicPlaybackService.kt — MediaSessionService wiring ExoPlayer to
  the layered factory

Music folder picker implemented:
- ui/FolderPickerScreen.kt — navigable Drive folder browser (path stack,
  Up button, "Use ... as library folder"), backed by DriveRepository.listFolders
- SettingsRepository.libraryRootFolderId (DataStore key
  library_root_folder_id) persists the choice across launches

Material 3 UI pass implemented (library grid, album detail, mini/full player):
- data/drive/DriveRepository.kt — listLibraryAlbums(rootFolderId) replaces
  the old flat file list: does the depth-first album search, then one
  combined query for every found album's tracks (with a `parents` field so
  tracks bucket back to their album), returns List<DriveAlbum>
- ui/LibraryScreen.kt — LazyVerticalGrid of albums, placeholder-color tile
  with the album's initial (real covers are a later roadmap item), name,
  track count
- ui/AlbumDetailScreen.kt — back button + album name header, numbered track
  list; tapping a track plays the WHOLE album from that index via
  controller.setMediaItems(...), not just the one file, so next/prev work
- ui/PlayerBar.kt — PlayerUiState + rememberPlayerUiState(controller):
  mirrors a MediaController's Player.Listener callbacks (metadata/isPlaying/
  playbackState) into Compose state, plus a 500ms polling LaunchedEffect for
  position while playing. MiniPlayerBar (bottom bar, tap to expand) and
  FullPlayerScreen (placeholder art, title/artist, seek Slider, play/pause/
  skip, collapse chevron) both read title/artist straight from the
  controller's live MediaMetadata — same ID3 data Media3 already extracts
  from the stream, no separate parsing needed.
- MainActivity: mediaController is now `by mutableStateOf` (Compose-observable,
  was a plain var) so the UI reacts once it connects; playAlbum(album,
  startIndex) replaced the old single-file playFile(); AppState/LibraryRoute
  sealed classes drive navigation (SignedOut/Loading/PickingFolder/Error/
  LibraryLoaded, with Albums/AlbumDetail as a sub-route). Mini-player is a
  persistent row under the main content, independent of which library route
  is showing; full player is a fillMaxSize overlay above the whole Scaffold.
- Needed androidx.compose.material:material-icons-extended (NOT -core:
  Pause/SkipNext/SkipPrevious aren't in the core icon set, only Play/Back/
  chevrons are — found by a real compile failure, not by reading docs first)

Automatic album covers implemented:
- data/drive/AlbumArtRepository.kt — resolveArt(album): tries an embedded
  picture first, via android.media.MediaMetadataRetriever pointed at the
  album's first track's authenticated Drive URL (setDataSource(uri,
  headers) with the Bearer token; NOT Media3's MetadataRetriever, the plain
  framework class is enough and avoids spinning up ExoPlayer machinery just
  to check for a picture). Found art is written to cacheDir/album_art/
  {albumId}.jpg so it survives process restarts without needing Room. Falls
  back to the iTunes Search API (free, no key) when nothing's embedded,
  querying "{artistHint} {albumName}" for precision. Both paths cached
  in-memory for the process lifetime too (including a cached "no art found"
  null, so failures aren't retried every recomposition).
- DriveAlbum gained artistHint: String? — see "Album/artist view" below for
  exactly what this is threaded from (it changed after a real bug was found).
- ui/LibraryScreen.kt — AlbumGridItem resolves art lazily per album
  (LaunchedEffect) and shows a Coil AsyncImage in place of the placeholder
  tile once resolved.
- ui/PlayerBar.kt — PlayerUiState gained artworkData: ByteArray?, read
  directly from controller.mediaMetadata.artworkData (Media3's own
  extraction from the currently-playing stream, zero extra network calls,
  same bytes the system notification already used). Decoded to an
  ImageBitmap once per change and shown in both MiniPlayerBar and
  FullPlayerScreen in place of their placeholder tiles.
- Added io.coil-kt.coil3 (coil-compose + coil-network-okhttp, v3.5.0) for
  loading the File/URL art models into Compose.

Verified live end to end on the musicdrive_test emulator: the library grid
shows real, correctly-matched cover art for every album (mix of embedded and
iTunes-resolved), and the full player shows the actual embedded artwork for
the currently-playing track alongside its real title/artist.

Playback optimizations implemented (buffering latency, cold-start reliability,
gapless — the three things "optimize playback" broke down into):
- playback/MusicPlaybackService.kt — custom DefaultLoadControl with
  bufferForPlaybackMs=1_000 / bufferForPlaybackAfterRebufferMs=2_000 (defaults
  are ~2_500/5_000, tuned for adaptive streaming; these are plain progressive
  HTTP MP3s, so that's needless tap-to-audio latency). Measured on the
  emulator via logcat: BUFFERING to PLAYING in ~450ms.
- Cold-start reliability: MusicPlaybackService is a START_STICKY
  MediaSessionService, so the OS can restart it directly after a process
  death (e.g. low-memory kill during playback) WITHOUT MainActivity ever
  running first — the old nullable driveTokenProvider meant that path just
  stopSelf()'d, silently failing to resume. Fixed by splitting auth into a
  new `DriveAuthorizer` interface:
  - auth/DriveAuthorizationManager.kt now implements it (unchanged behavior,
    still needs a ComponentActivity for the one-time consent UI)
  - auth/ContextDriveAuthorizer.kt is new: Identity.getAuthorizationClient
    DOES have a plain-Context overload (confirmed by compiling, not by
    trusting docs), so this can silently mint a fresh token with no
    Activity — it just can't show a consent UI if one's ever needed
    (hasResolution() true), which only happens on first run or revoked
    access; open the app in that case.
  - auth/DriveTokenProvider.kt now holds a mutable authorizer
    (setAuthorizer()) instead of a fixed one
  - MusicDriveApplication.onCreate() eagerly creates driveTokenProvider with
    a ContextDriveAuthorizer — always available, no longer nullable.
    MainActivity upgrades it to its ActivityDriveAuthorizationManager while
    alive and swaps back to a Context one in onDestroy so a later refresh
    never holds a dead Activity.
  - Verified for real: `adb root` + `kill -9 <pid>` while a track was
    playing → Android auto-restarted just MusicPlaybackService (confirmed
    via `dumpsys activity services`, no MainActivity in logcat) →
    ExoPlayerImpl/MediaSessionImpl both initialized successfully with no
    crash, proving the fix actually closes the gap rather than just
    compiling.
- Gapless: no code change needed — Media3 already plays consecutive
  MediaItems in a playlist back-to-back (playAlbum's setMediaItems call
  already builds the whole album as one playlist). Verified by seeking near
  the end of a track and confirming automatic advance to the next track's
  start with no user action and no stutter in dumpsys media_session. True
  crossfade (DSP-blended overlap between tracks) is a distinct, much bigger
  feature — not implemented, flag separately if wanted.

Room-cached Drive index implemented:
- data/local/room/: AlbumEntity (id, name, artistHint, rootFolderId),
  TrackEntity (id, albumId, name, mimeType, sizeBytes), AlbumWithTracks
  (@Relation), LibraryDao (replaceLibrary/clearAll transactions, scoped by
  rootFolderId since only one root is active at a time), MusicDriveDatabase
  (Room DB, singleton on MusicDriveApplication like the SimpleCaches)
- data/LibraryRepository.kt — bridges DriveRepository (remote) and
  LibraryDao (local): loadLibrary(rootFolderId) is a Flow that emits the
  cached albums immediately if present, then emits again once the live
  Drive fetch lands (and writes it back to Room). A background-refresh
  failure is swallowed, not surfaced, as long as cache is already showing —
  being offline shouldn't kick the user from "browsing a stale library" to
  an error screen. clearCache() wipes everything on an explicit folder
  change (only one root is ever active).
- MainActivity's loadLibrary() now collects that Flow instead of a one-shot
  suspend call; the route-reset to Albums happens once up front, not on
  every emission, so a background refresh mid-browse doesn't kick the user
  out of an album they're looking at.
- Needed the KSP Gradle plugin (androidx.room + Room 2.8.4's KSP compiler);
  confirmed the exact version to pair with Kotlin 2.4.10 by compiling
  (ksp = "2.3.11") rather than trusting a version-lookup search result that
  suggested a nonstandard string.
- Verified live on the emulator: `pm clear` + first launch does the full
  recursive fetch and populates Room (slow, as before); force-stop and
  relaunch shows the album grid (names, track counts) in ~3s total including
  sign-in, vs. the 10+s a from-scratch recursive Drive scan takes — cover
  art (which has its own separate disk cache, unrelated to this) fills in
  a moment after, not blocking the list itself.

Album/artist view implemented:
- Real bug found and fixed: artistHint was originally the album folder's
  IMMEDIATE parent name, which mislabels multi-disc releases. A real Drive
  library has releases like root/U2/Achtung Baby (30th Anniversary
  Edition)/CD1/track.mp3 — 4 levels, not the assumed 3 — so "CD1" (and its
  parent, the release name) qualified as "albums" and their immediate
  parents got read as fake artists ("18 Singles (deluxe)", "Achtung Baby
  (30th Anniversary Edition)" showed up as artists in the list). Fixed by
  changing collectAlbumFolders' artistHint (renamed from parentName) to
  track the ROOT'S DIRECT CHILD folder name — set once, the first time
  recursion leaves the root, then threaded unchanged through however many
  Release/CDn layers follow — instead of the immediate parent. Verified:
  artist list now correctly shows only "Depeche Mode" and "U2", and
  Depeche Mode's album grid correctly includes its CD1/CD2/etc. discs.
- Known follow-up NOT fixed (out of scope for this task, flagging for
  later): multi-disc releases still show as separate "albums" (CD1, CD2,
  Disc 1 - ...) rather than being merged into one album under the release
  name. Fixing that needs a different album-boundary heuristic (e.g. don't
  treat CD-numbered folders as the album boundary; use their parent
  instead) — it's a real, user-visible rough edge in the library model,
  not just a display nit.
- ui/PlaceholderArt.kt and ui/ScreenHeader.kt: extracted from LibraryScreen
  and AlbumDetailScreen respectively so ArtistListScreen (and the new
  ArtistAlbums route's header) could reuse them instead of a third copy.
- ui/ArtistListScreen.kt — ArtistSummary + List<DriveAlbum>.groupByArtist()
  (groups by artistHint, falling back to "Unknown Artist" for a flat
  root/Album layout with no hint), and a LazyColumn of artist rows (circular
  initial avatar, name, album/track counts).
- MainActivity's LibraryRoute is now a 3-level stack: Artists ->
  ArtistAlbums(artist) -> AlbumDetail(album, artist) — AlbumDetail carries
  the artist along so its back button returns to that artist's album grid,
  not straight to the artist list.
- Verified live end to end on the emulator: artist list, artist -> albums,
  album -> track detail, and back-navigation at every level all correct.

Queue view implemented:
- MainActivity's playAlbum() now sets MediaMetadata (title = track name,
  albumTitle = album name) on every MediaItem when building the playlist,
  not just mediaId/uri — otherwise tracks Media3 hasn't decoded yet had
  nothing to show in a queue list (title only gets populated from real ID3
  once a track actually starts playing).
- ui/QueueScreen.kt — QueueItem/QueueState + rememberQueueState(controller):
  same mirroring pattern as rememberPlayerUiState, but listens for
  onTimelineChanged/onMediaItemTransition and reads the controller's whole
  playlist (mediaItemCount / getMediaItemAt / currentMediaItemIndex) rather
  than just the current item. QueueScreen is a full-screen list, current
  track bold + tinted (primaryContainer), tapping any row does
  controller.seekTo(index, 0) and collapses back to the full player.
- FullPlayerScreen gained a queue icon button (top-right) opening it; wired
  as another top-level overlay in MainActivity alongside the full player
  (isQueueVisible, same pattern as isPlayerExpanded).
- Verified live: queue shows the whole album with the currently-playing
  track correctly highlighted; tapping an earlier/later track jumps
  playback there (confirmed via dumpsys media_session's active item id)
  and returns to the full player showing the new track.

Lyrics implemented:
- IMPORTANT correction to this doc's earlier plan: Media3 does NOT have a
  dedicated parser for embedded ID3 lyrics (USLT/SYLT) — confirmed against
  androidx/media source and GitHub issues androidx/media#922 and #1435.
  They arrive through Player.Listener#onMetadata as an opaque
  androidx.media3.extractor.metadata.id3.BinaryFrame(id="USLT", data:
  ByteArray). ui/LyricsScreen.kt hand-parses the ID3v2 USLT frame body
  (encoding byte, 3-byte language code, null-terminated descriptor, then
  the lyrics text) — no separate tagging library needed, but no free ride
  from Media3 either.
- data/local/room/LyricsEntity.kt + LyricsDao.kt: one row per trackId
  (lyrics text nullable, source: "embedded"/"lrclib"/"none",
  isInstrumental). MusicDriveDatabase bumped to version 2;
  MusicDriveApplication now builds it with
  fallbackToDestructiveMigration(dropAllTables = true) since this is
  pre-release local-only cache data (library index, lyrics) — fine to
  rebuild rather than write real migrations while the schema is still
  moving. Verified this Room 2.8.4 API signature compiles.
- data/LyricsRepository.kt: embedded lyrics (passed in by the caller,
  since extraction is tied to the player, not something this repository
  fetches) always win when present. Otherwise falls back to the LRCLIB
  API (https://lrclib.net/api/search, free, no key), matching by
  duration proximity (<2s) or first result with plainLyrics. Every
  result — including "not found" — is cached in Room keyed by trackId,
  so a track is only ever looked up once.
- ui/LyricsScreen.kt: rememberLyricsState listens for onMetadata (for
  embedded USLT) and, after a 400ms grace period to let that callback
  fire, calls LyricsRepository.getLyrics with whatever embedded lyrics
  it found (or null). This is a pragmatic heuristic, not a guarantee —
  there's a theoretical race if onMetadata fires after the delay — but
  lyrics aren't safety-critical so the tradeoff is fine.
- FullPlayerScreen gained a lyrics icon button (top-right, next to the
  queue button, Icons.AutoMirrored.Filled.Notes) opening it; wired as
  another top-level overlay in MainActivity (isLyricsVisible), same
  pattern as isQueueVisible/isPlayerExpanded.
- Verified live end to end on the emulator: an obscure Depeche Mode
  extended-mix b-side correctly shows "No lyrics found" (and that
  negative result is cached in Room); U2's "Beautiful Day" correctly
  fetches full lyrics from LRCLIB on first open. Confirmed via `adb
  shell run-as com.ettore.musicdrive sqlite3 .../musicdrive.db` that
  both the miss and the hit are persisted in LyricsEntity, so reopening
  either track is instant and offline from then on. (Our test library —
  Depeche Mode/U2 — has no embedded USLT tags, so only the LRCLIB path
  was exercised live; the embedded-tag path is implemented and correct
  per the ID3v2 spec but hasn't been verified against a real tagged
  file.)

Downloads implemented (Media3 DownloadManager, per-song and per-album):
- MusicDriveApplication now owns a single shared StandaloneDatabaseProvider
  (previously a local var in onCreate) so both SimpleCache instances AND
  DownloadManager's own index share one open db handle, plus a
  DownloadManager (over downloadCache, Requirements default) and a
  DownloadTracker.
- playback/DriveDataSourceFactory.kt: extracted driveMediaUri(fileId) (the
  "https://www.googleapis.com/drive/v3/files/{id}?alt=media" URL) and
  buildAuthenticatingHttpDataSourceFactory(tokenProvider) as public/shared,
  used by both MainActivity.playAlbum() and DownloadTracker — critical for
  correctness, not just DRY: Media3's CacheDataSource keys the download
  cache by request URI, so playback only ever finds a download if the two
  build the exact same URI string for a given file id.
- download/MusicDownloadService.kt: a DownloadService with a
  PlatformScheduler(this, JOB_ID) so JobScheduler can relaunch it and
  resume downloads if the process dies mid-download and requirements
  (e.g. network back) are later met, not just while the app is open.
  Needs android.permission.FOREGROUND_SERVICE_DATA_SYNC (API 34+,
  targetSdk 37) and a manifest <service> with the
  androidx.media3.exoplayer.downloadService.action.RESTART intent-filter.
  New drawable ic_notification_download.xml (status-bar icon) + string
  download_channel_name.
- download/DownloadTracker.kt: mirrors DownloadManager's state (seeded
  from downloadIndex.getDownloads() on init, kept live via
  DownloadManager.Listener) into a StateFlow<Map<trackId, Download>>.
  Album membership rides in DownloadRequest.data (the album's Drive folder
  id, UTF-8 bytes) rather than a separate Room table, since
  DownloadManager already persists each request's full byte payload in
  its own index — one less thing to keep in sync.
  albumDownloadState(album, downloads) has FOUR states, not three
  (NONE/PARTIAL/DOWNLOADING/COMPLETE): a real bug was found live on the
  emulator where PARTIAL (some tracks already downloaded standalone,
  none currently in flight) was originally folded into DOWNLOADING —
  tapping "download album" at that point read as "cancel the in-flight
  download" and deleted the one track that WAS already done, instead of
  fetching the rest. PARTIAL now renders the same download-icon affordance
  as NONE (tap resumes/finishes the album; re-adding an already-COMPLETED
  track's request is a no-op, Media3 doesn't re-fetch bytes it has).
- ui/AlbumDetailScreen.kt: per-track download icon (Download /
  spinner-while-downloading / DownloadDone, tap toggles) plus one
  album-level icon in a new ScreenHeader trailing-actions slot (see next
  bullet). ui/ScreenHeader.kt gained an optional
  `actions: @Composable RowScope.() -> Unit` slot, reused by the album
  sort button (see below) too instead of adding a third bespoke header.
- Verified live end to end on the emulator: downloaded a single track (a
  real 4.7MB file landed in files/download_cache), downloaded a whole
  7-track album (all 7 real audio files on disk, correct total bytes),
  removed the whole album (files actually deleted from download_cache),
  and reproduced + fixed the PARTIAL-state bug above by downloading one
  track standalone first, then tapping "download album" and confirming
  it finishes the album instead of deleting the first track.

Streaming cache size, theme, and album sort are now user-facing settings
(the cache size DataStore plumbing already existed per the original
architecture plan — Room, an AdjustableLruEvictor reading it live — but
had no UI before this, exactly the "not yet user-facing" gap README.md
had flagged):
- data/local/SettingsRepository.kt gained three more DataStore-backed
  settings: themeMode (ThemeMode: SYSTEM/LIGHT/DARK, key theme_mode),
  libraryViewMode (LibraryViewMode: ARTISTS/ALBUMS, key
  library_view_mode — which top-level browse tab was open last, restored
  on next launch), and albumSortMode (AlbumSortMode: NAME/TRACK_COUNT,
  key album_sort_mode).
- ui/theme/Theme.kt: MusicDriveTheme now takes a ThemeMode param instead
  of a raw darkTheme Boolean; SYSTEM still defers to isSystemInDarkTheme().
  Collected once at the MainActivity.onCreate/setContent level (wrapping
  the whole app) so a change from within the new Settings screen (nested
  deep inside) takes effect immediately without restarting the activity.
- ui/SettingsScreen.kt (new): radio-row sections for cache size (500 MB /
  1 GB / 2 GB / 5 GB / 10 GB presets, not a raw byte slider — simpler and
  plenty granular for a personal library), appearance (Follow system /
  Light / Dark), and default album sort. Opened via a new gear IconButton
  next to "Change library folder".
  Real bug found and fixed live: SettingsScreen (and, it turned out,
  QueueScreen's sibling LyricsScreen too) is shown as a full-screen
  overlay Box in MainActivity, same pattern as FullPlayerScreen/
  QueueScreen — but unlike those two, it had no
  `.background(MaterialTheme.colorScheme.surface)` on its root Column, so
  the album grid underneath showed through behind the settings text.
  QueueScreen already had the background; LyricsScreen was missing it too
  and got the same fix.
- Verified live: cache size, theme, and sort selections all persist
  across a full app relaunch (confirmed via re-opening Settings after
  force-stopping and restarting); switching to Dark visibly re-themes the
  whole app (including the Settings screen itself) instantly with no
  transparency artifacts after the background fix.

Artists/Albums view toggle and album sort implemented:
- MainActivity's LibraryRoute gained a top-level `Albums` route (flat grid
  of every album in the library, ungrouped) alongside the existing
  `Artists` route, and `AlbumDetail` now carries `backTo: LibraryRoute`
  instead of a fixed `artist: ArtistSummary` — generalizing the back
  target so AlbumDetail can be reached from either the flat Albums grid
  or an artist's album grid and return to the right place either way.
  loadLibrary() now reopens on whichever tab (settingsRepository's
  libraryViewMode) was open last, instead of always resetting to Artists.
- New private LibraryTopBar composable (folder-change button + settings
  gear + an Artists/Albums FilterChip pair) replaces the old bare
  "Change library folder" TextButton, shared by both top-level routes.
  Selecting a tab both switches the route immediately and persists the
  choice via setLibraryViewMode.
- ui/LibraryScreen.kt gained `List<DriveAlbum>.sortedByMode(AlbumSortMode)`
  (NAME → alphabetical, TRACK_COUNT → most tracks first); applied to both
  the flat Albums grid and an artist's album grid via a shared
  AlbumSortMenuButton (IconButton + DropdownMenu) in MainActivity, wired
  into ScreenHeader's new actions slot for the artist view and inline for
  the flat view.
- Verified live: the flat Albums tab correctly shows all 44 albums
  (24 Depeche Mode + 20 U2) vs. the Artists tab's 2 artist rows; switching
  sort to "Track count" correctly re-orders the grid descending (8, 7, 7,
  6, ... confirmed against the actual per-album track counts); the chosen
  tab and sort mode both survive a force-stop + relaunch.

Multi-disc release merging implemented:
- data/drive/DriveRepository.kt's collectAlbumFolders: a subfolder is now
  treated as one disc of its PARENT's release, not its own album, when its
  own recursion bottomed out immediately (it directly held audio) AND its
  name matches `^(cd|disc)\s*\d+` (case-insensitive) — covers "CD1",
  "CD 2", "Disc 1", "Disc 1 - Sounds Of The Universe", the real naming
  conventions seen in the test library. The parent folder becomes the
  merged DriveAlbum (release name, e.g. "Sounds Of The Universe (Deluxe
  Edition)"), with AlbumFolderWithArtist.sourceFolderIds (new field)
  listing every disc's physical folder id in disc-number order.
- listLibraryAlbums: the Drive query's parentsClause now covers every
  sourceFolderId, not just one id per album; tracks are grouped by their
  REAL physical parent folder id first, then concatenated back per album
  in sourceFolderIds order (disc 1's tracks before disc 2's) rather than
  re-sorted by name globally — a plain per-album name-sort would have
  interleaved same-numbered tracks across discs (both discs' "01 -
  ...mp3") instead of keeping each disc's running order intact.
- Verified live on the real Depeche Mode/U2 library (pm clear +
  relaunch to force a fresh scan): Depeche Mode went from 24 to 21 albums
  and U2 from 20 to 16 (fewer albums, same total track count - 236 and
  224 respectively - confirming tracks were merged, not lost); no more
  standalone "CD1"/"CD2" entries in the album grid; "Sounds Of The
  Universe (Deluxe Edition)" now shows as one 33-track album whose track
  list correctly restarts at "01 ..." partway through (disc 2 boundary)
  instead of interleaving with disc 1 alphabetically.

Home dashboard (most-played songs grid) implemented:
- data/local/room/PlayCountEntity.kt + PlayCountDao.kt: one row per
  trackId (playCount, lastPlayedAt), incremented via a raw upsert query
  (`INSERT ... ON CONFLICT(trackId) DO UPDATE SET playCount = playCount +
  1`) rather than a read-then-write, so concurrent increments can't race.
  observeTopTracks(limit) is a Flow, ordered by playCount desc then
  lastPlayedAt desc, so ties favor the most recently played. Bumped
  MusicDriveDatabase to version 3 (still fallbackToDestructiveMigration,
  per the existing pre-release-schema policy).
- data/PlayStatsRepository.kt: thin wrapper (observeTopTracks/recordPlay)
  over the DAO, same pattern as LyricsRepository/DownloadTracker.
- MainActivity records a play via a Player.Listener on the MediaController
  (onMediaItemTransition -> recordPlay(mediaItem.mediaId)) — fires on
  manual track taps, skip next/previous, and natural auto-advance to the
  next queued track alike. This is a simple "counts every transition"
  policy, not scrobble-grade (a 2-second skip counts the same as a full
  listen) - acceptable for a personal most-played dashboard, not aiming
  for Last.fm-level precision.
- ui/HomeScreen.kt: LazyVerticalGrid of HomeGridItem (album + track +
  track's index within that album + play count), same tile look as the
  album grid (art via the existing resolveArt, placeholder color/initial
  fallback). Tapping a tile calls onPlayAlbum(album, trackIndex) — same
  "play the whole album from here" behavior as tapping a track in
  AlbumDetailScreen, not a bare single-track queue.
- MainActivity builds the trackId -> (album, track, index) lookup from
  the currently loaded library (current.albums) and maps
  playStatsRepository's top-N play counts through it, dropping any
  trackId no longer present — avoids a second Room join/relation just to
  resolve track metadata that's already sitting in memory.
- data/local/SettingsRepository.kt: LibraryViewMode gained HOME (now the
  DEFAULT, ahead of ARTISTS) alongside ARTISTS/ALBUMS; MainActivity's
  LibraryRoute/LibraryTopBar extended with a third "Home" tab, first in
  the row, matching YouTube Music's Home-first tab order.
- Verified live end to end on the emulator: played 3 different tracks
  from the same album a controlled number of times (3x/2x/1x, confirmed
  via `adb shell run-as ... sqlite3 musicdrive.db "SELECT trackId,
  playCount FROM PlayCountEntity"` showing exactly 3/2/1), reopened the
  app to confirm Home is now the default tab, and confirmed the grid
  shows exactly those 3 tracks in the correct 3/2/1 order with real album
  art; tapping the #2 tile correctly started playback at that exact track
  (confirmed via dumpsys media_session's active item id) within its whole
  album's queue.

UI/UX overhaul and navigation rework implemented (a large batch of
user-requested fixes and features, done together — see git log for the
individual commits):
- Dark mode readability: the REAL bug wasn't the color palette, it was that
  `SettingsScreen`/`FullPlayerScreen`/`QueueScreen`/`LyricsScreen` are
  rendered as overlays outside the `Scaffold` that normally sets
  `LocalContentColor` (via `Surface`'s `contentColorFor`) — a bare
  `.background()` modifier doesn't set it, so every `Text` without an
  explicit `color=` silently fell back to Compose's hardcoded default
  (black), invisible on a dark background. Only noticed once dark mode was
  actually tested live (must have been tested in light mode previously).
  Fixed by wrapping each in `Surface(color = MaterialTheme.colorScheme.surface)`
  instead of a plain background modifier. `ui/theme/Theme.kt`'s dark scheme
  is also now explicit (brighter `onSurface`/`onSurfaceVariant`) rather than
  relying on M3's stock `darkColorScheme()`.
- Album detail screen redesigned YouTube-Music-style: cover art on top
  (previously had none), artist name, a single "Download album" pill button,
  clean numbered track list with NO per-track download icons anymore
  (per-track download is gone from the UI entirely — album-only downloads).
  Track titles everywhere now strip the file extension
  (`ui/TextFormatting.kt`'s `withoutAudioExtension()`).
- Home/Albums/Artists/Library all use an adaptive ~3-column grid
  (`ui/GridDefaults.kt`: `GRID_TILE_MIN_SIZE = 100.dp` tuned so phone widths
  land on exactly 3 columns, wider screens get more). Artist list converted
  from a row-list to a grid of circular tiles (art = first album's cover,
  no per-artist art concept exists). Track-count subtitles removed from
  album tiles (explicitly called out as a useless metric).
- Album sort gained a YEAR mode (now the default) — release year isn't
  captured anywhere else in the app, so `AlbumArtRepository.resolveYear()`
  reads it embedded-tag-first (`MediaMetadataRetriever.METADATA_KEY_YEAR`)
  then iTunes-fallback (`releaseDate` field), cached forever in a new
  `AlbumYearEntity`/`AlbumYearDao` (Room v4). Resolved eagerly in the
  background as soon as the library loads (`MainActivity`), NOT lazily like
  art, since sorting needs every album's year at once — but bounded to 3
  concurrent resolutions via a `Semaphore`: firing one `MediaMetadataRetriever`
  open per album unbounded (37 albums at once, live library) starved the
  binder thread pool and stalled the whole app, a real bug found live via
  logcat's repeated "binder thread pool starved" before the semaphore fix.
- Settings: storage size is now a dialog (opened from a "Storage" row) with
  a `Slider` snapped to the same 5 presets, replacing the old radio-button
  list. "Change library folder" moved from the top bar into Settings.
- Sign-in flash mitigation: `MusicDriveApplication.isSignedInThisProcess`
  (plain var, survives Activity recreation within the same process) gates
  the initial silent-sign-in `LaunchedEffect` so re-entering composition
  (e.g. the OS reclaiming/recreating the Activity while backgrounded)
  doesn't re-invoke Credential Manager's `getCredential()` — which can flash
  a brief system UI even in "silent" mode. A genuine cold process restart
  can still flash once; that part is a platform limitation, not fixable
  from app code.
- Swipe-back navigation: `androidx.activity.compose.BackHandler` added at
  every level that previously fell through to exiting the app — drill-down
  routes (`AlbumDetail`/`ArtistAlbums`) step back like their on-screen
  arrow, each overlay (full player, queue, lyrics, settings-as-overlay
  [later replaced by a route, see below], the folder picker's path stack)
  closes/steps up instead. Registered in inner-to-outer order so the
  innermost open thing wins when several are enabled at once.
- Full player: swipe left/right on the cover art skips next/previous
  (`detectHorizontalDragGestures`, threshold ~120px). A repeat-album toggle
  (icon below the cover, tinted `primary` when active) cycles
  `Player.REPEAT_MODE_OFF`/`REPEAT_MODE_ALL` on the shared `MediaController`.
- Home page gained a "Liked Songs" card (a synthetic `DriveAlbum` built
  client-side from the existing most-played-tracks query — id
  `"liked-songs"`, never touches Drive/art-resolution since it's not a real
  album) and an "Artists you've been playing" row: per-artist totals
  computed client-side by summing `PlayCountEntity.playCount` grouped by
  each track's album's `artistHint` (no new Room table needed — reuses
  `PlayCountDao.observeAll()`, a new unranked query alongside the existing
  `observeTopTracks(limit)`).
- New app icon (`res/mipmap-*/ic_launcher*.webp` + adaptive
  `res/drawable-xxxhdpi/ic_launcher_foreground.png` +
  `@color/ic_launcher_background`): a pink rounded-square cloud+play mark,
  built from a designer-supplied flat PNG by geometrically masking the
  rounded-square crop to transparent corners, then color-keying out the
  pink to isolate just the white glyph for the adaptive foreground layer
  (ImageMagick, not a design tool — ad hoc but the result is clean, verified
  live on both devices with no artifacts).
- Dynamic per-track color: `ui/PlayerBar.kt`'s `rememberDominantColor()`
  runs `androidx.palette.graphics.Palette` (new `androidx-palette-ktx`
  dependency) over the decoded artwork bitmap (vibrant → muted → dominant
  swatch fallback chain) and glows it as a top-fading gradient behind the
  full player — verified live, a U2 sepia cover correctly produces a warm
  amber glow.
- Offline album art caching hardened: `AlbumArtRepository`'s disk cache
  moved from `cacheDir` to `filesDir` (survives OS cache-clearing, same
  reasoning as the download cache) and the iTunes-fallback path now
  actually downloads and writes the image bytes to that same disk cache
  instead of just handing Coil a remote URL to fetch-and-cache-maybe —
  `resolveArt()`'s return type is now always `File?`, never a URL string.
- Bottom navigation replaces the old top FilterChip row: Home / Search /
  Library / Settings, via a `NavigationBar`+`NavigationBarItem`s in
  `Scaffold`'s `bottomBar` (only shown once `AppState.LibraryLoaded`).
  `LibraryRoute` restructured to `Home`/`Search`/`Library`/`Settings` as
  the four bottom-nav roots, with `ArtistAlbums`/`AlbumDetail` still
  drilling down from `Library`. `SettingsRepository.LibraryViewMode`
  dropped `HOME` (now only `ARTISTS`/`ALBUMS`, governing the Library tab's
  internal toggle — Home is a permanent top-level destination now, not a
  nested view mode) and defaults to `ARTISTS`. Settings changed from a
  Boolean-flag overlay to a real route rendered inside the Scaffold (so it
  needed its `.statusBarsPadding()` removed — double-padded once
  `innerPadding` started covering that).
- New `ui/SearchScreen.kt`: filters the already-loaded in-memory library
  (no new Drive query or index needed at personal-library scale) by
  substring match on album name/artistHint/track name, showing separate
  "Albums" and "Songs" result sections; tapping a track result plays that
  album from that track same as everywhere else. Verified live: "beautiful"
  correctly matched the same song across 3 different U2 albums.
- Synced lyrics with word-level highlighting (`ui/LyricsScreen.kt`):
  LRCLIB's `syncedLyrics` field (raw LRC `[mm:ss.xx]line` text, previously
  ignored — only `plainLyrics` was fetched) is now captured, cached in a
  new `LyricsEntity.syncedLyrics` column (Room v5), and parsed into timed
  lines. LRCLIB only has LINE-level sync, not word-level, so word-by-word
  highlighting is SYNTHESIZED by evenly distributing each line's words
  across that line's own timespan (its start to the next line's start) —
  the same trick Metrolist (see Reference below) uses for plain
  line-synced sources, not real per-word timing. A local ~80ms position
  ticker (`rememberLiveLyricsPositionMs`, stops polling while paused)
  drives the wipe at finer grain than the shared 500ms player-bar poll.
  Falls back to the existing plain scrolling text when a track has no
  synced lyrics (embedded USLT is always unsynced by spec; LRCLIB
  sometimes only has plain lyrics for a given match). Verified live end to
  end on U2's "With or Without You": active line renders bold with words
  turning `primary`-colored progressively, inactive lines dim, and the
  `LazyColumn` auto-scrolls to keep a couple of already-sung lines visible
  above the active one.
- Researched other open-source Android music players (Metrolist 12.1k★,
  Auxio 4.2k★, Rhythm, BoomingMusic, InnerTune/OuterTune, Kanade) for UX
  ideas — all GPL-3.0, so patterns are fair game to reimplement
  independently but files aren't copyable without triggering copyleft.
  Metrolist is the standout reference (see Reference section below).

Player polish batch (a "now playing" equalizer marker, a Stats screen, and a
round of user-reported full-player fixes) implemented:
- `ui/PlayingIndicator.kt`: the 3-bar equalizer marker flagged in Reference
  below is now built and wired in — `AlbumDetailScreen`'s and `QueueScreen`'s
  track-number column shows it (in place of the number) for whichever row is
  the currently-playing AND currently-playing(isPlaying) track.
- `ui/StatsScreen.kt`: the "Wrapped"-style recap flagged in Reference below
  is now built — a single scrollable page (hero cards for #1 song/artist,
  ranked top-5 lists for both), reusing the exact same `homeItems`/
  `topArtists` lists `MainActivity` already computes for the Home route
  (hoisted out so both routes share one computation) rather than a second
  query. Opened from a new "Stats" row in Settings.
- Real bug found and fixed live (physical Pixel, user-reported): the full
  player's artwork came ONLY from `controller.mediaMetadata.artworkData` —
  Media3 re-extracting ID3 art from the new track's stream on every skip,
  which lags a beat behind the transition itself, flashing the bare
  placeholder tile. `FullPlayerScreen`/`MiniPlayerBar` now take a
  `fallbackArt` param — `MainActivity` resolves it via the EXISTING
  `AlbumArtRepository.resolveArt()` (already memory/disk-cached from the
  library grid) keyed off `trackLocations[playerUiState.mediaId]?.first`,
  so skipping within an album now shows the right cover instantly instead
  of blanking. This is a live-metadata-vs-cache timing fix, not a caching
  gap — the art was already being cached correctly, the player screen just
  wasn't reusing it.
- Real bug found and fixed live (same session): the full player's content
  visibly reflowed/"shrank" on every skip, because the layout split
  flexible space between TWO independent `weight(1f)` spacers (one above
  the art, one below the controls) — a track whose title wrapped to 2 lines
  vs. 1 line shifted that split asymmetrically. Fixed by wrapping
  art-through-controls in a SINGLE `weight(1f)` `Column` with
  `Arrangement.Center` instead, and by capping the title to `maxLines = 1`
  with `Modifier.basicMarquee()` (scrolls instead of wrapping) so title
  height stopped varying in the first place.
- User-requested control changes to `FullPlayerScreen`: shuffle toggle and
  a 3-state repeat button (off → all → one, single cycling icon —
  `Icons.Filled.Repeat`/`RepeatOn`/`RepeatOne`) now flank the
  previous/play/next row, YouTube-Music-style (`PlayerUiState` gained
  `shuffleModeEnabled`, mirrored from `controller.shuffleModeEnabled` the
  same way `repeatMode` already was). The lyrics button moved from the top
  bar to its own row directly below the album art, alongside the queue
  button (both left-aligned) — icon changed from the generic
  `Icons.AutoMirrored.Filled.Notes` to `Icons.Filled.Lyrics` (more
  recognizable). The hand-drawn wavy `SquigglySlider` seek bar reverted to
  a plain straight line (user didn't like the wave) — same Canvas/drag/tap
  gesture handling, just a `drawLine` instead of a sine-wave `Path`; the
  wave-animation code (`rememberInfiniteTransition`, phase, amplitude/
  wavelength) was removed entirely rather than left dead.
- `ui/AlbumDetailScreen.kt`'s "Download album" button changed from a
  labeled `Button` to an icon-only `FilledIconButton` per request (kept the
  three-state Download/spinner/DownloadDone icon logic, dropped the text).
- Cache eviction policy changed from pure Media3 LRU (evicts by
  least-recently-touched byte span) to **least-played-first**:
  `playback/AdjustableLruEvictor.kt` now takes a live `trackId -> playCount`
  map (fed from the SAME `PlayCountDao.observeAll()` Home already uses,
  collected in `MusicDriveApplication.onCreate()` next to the existing
  `cacheLimitBytes` feed) and sorts eviction candidates by ascending play
  count first, `lastTouchTimestamp` as a tiebreaker — a track streamed once
  last week no longer outlives one streamed 50 times, which is what a
  most-played-driven personal cache should do. A missing/never-played track
  counts as play count 0 (evicted first). Added one safety net beyond what
  was asked: the actively-streaming span is protected from eviction where
  another candidate exists, since play-count alone has no notion of "this
  is what's playing right now" and could otherwise evict its own in-flight
  buffer for a rarely-played track. Verified: compiles, installs, plays,
  and the evictor's onStartFile/onSpanAdded path is exercised live (buffered
  position advancing normally) with no crash — a true fill-past-the-cap
  test wasn't practically forceable in-session (would need either a tiny
  cache cap or a lot of large files).
- Verified live end to end on the `musicdrive_test` emulator (the physical
  Pixel 7 dropped off USB mid-session and wasn't available for the second
  half of this batch — playing indicator/Stats/art-lag-fix were confirmed
  on the Pixel first, the rest of the batch only on the emulator so far):
  playing-indicator bars replace the track number on the active row in both
  Album Detail and Queue; Stats opens from Settings and shows correct
  ranked data; skipping within an album shows the right cover art
  instantly; the layout no longer visibly shifts between a 1-line and
  2-line title (title now scrolls via marquee instead of wrapping); the
  seek bar is a straight line; shuffle toggles and repeat correctly cycles
  off → all → one → off (confirmed via `dumpsys media_session` /
  `uiautomator dump`, not just visually); lyrics+queue icons sit together
  below the cover.

## Next steps
1. Android Auto: MediaLibraryService browsing tree + automotive app
   descriptor
2. Not yet re-verified on the physical Pixel 7: the second half of the
   "Player polish batch" above (shuffle/repeat, lyrics/queue repositioning,
   straight seek bar, least-played-first cache eviction) was only verified
   on the `musicdrive_test` emulator — the Pixel dropped off USB mid-session.
   Install the latest build there too before considering that batch fully
   done. (Everything from bottom-nav through the earlier UI/UX overhaul was
   already re-verified on the emulator per the entry above it; this item is
   now specifically about the polish batch, not the whole app.)

## Reference
- androidx/media demo apps are the canonical reference for DownloadManager
- Evaluated forking mardous/BoomingMusic (2026-08-22) — DECIDED AGAINST.
  Its entire library/index layer is built on Android's MediaStore (the OS
  already indexes local files for it); Room there is only used for
  playlists/queue/history/lyrics-cache, NOT a library index — it has no
  equivalent to "cache a remote API's listing", which is our actual hardest
  problem and the reason we need Room at all. Forking would mean ripping out
  its core data layer first, more work than continuing our own code. GPL-3.0
  either way, so anything taken beyond ideas/patterns would need attribution
  and source release on publish.
  Still useful as REFERENCE (not code to copy) for later roadmap items:
  - Lyrics: confirms LRCLIB is the right call. Their query:
    GET https://lrclib.net/api/search?q={artist}+{title}&album_name={album}
    then pick the result whose duration is within 2s of the track's,
    falling back to the first non-empty plainLyrics. Room cache is just
    `LyricsEntity(id, lyrics, provider, isInstrumental)` keyed by song id.
  - Queue persistence: `QueueEntity(id, order)` + a replaceQueue transaction
    (delete rows not in the new id list, then insert) — good minimal pattern.
  - Android Auto: their automotive_app_desc.xml is the standard AOSP
    `<uses name="media"/>` boilerplate, same as already planned here.
- Researched (2026-08-23) for lyrics/animation/design/stats ideas:
  Metrolist (github.com/MetrolistGroup/Metrolist, 12.1k★, GPL-3.0) is the
  standout — actively maintained, Compose+Media3, most feature-rich found.
  Confirmed the word-highlight-from-line-sync synthesis trick already
  implemented here. Other things worth revisiting later, NOT yet built:
  - ~~`SquigglySlider.kt`: hand-drawn wavy `Canvas` seek progress~~ — built,
    then reverted to a straight line per user preference (see "Player
    polish batch" above); the drag/tap-to-seek `Canvas` approach stayed.
  - ~~`PlayingIndicator.kt`: 3-bar "now playing" equalizer icon~~ — built,
    see "Player polish batch" above.
  - ~~A separate "Wrapped"-style annual stats screen~~ — built as
    `ui/StatsScreen.kt`, see "Player polish batch" above.
  - Auxio (4.2k★, GPL-3.0, View-based not Compose) drives its mini↔full
    player expand/collapse via a `BottomSheetBehavior` subclass with
    velocity-based fling — reference for a possible `AnchoredDraggable`
    swipe-to-expand gesture on the mini player (not implemented; currently
    tap-to-expand only).
