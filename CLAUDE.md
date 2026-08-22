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
- No physical device attached yet (USB/adb unresolved); use emulator for now
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
- Library model: Drive folder = album (simplest starting point)
- Room caches the Drive index so the library browses instantly offline
- Settings persisted in DataStore (key: cache_limit_bytes, default 2 GB)
- Music folder root: in-app Drive folder browser (reuse the existing Drive
  API client, no separate Google Picker API/key needed) to choose the root
  folder; its id persisted in DataStore (key: library_root_folder_id).
  Subfolders directly under it = albums, per the existing folder=album model.
- Lyrics: Media3's extractor already parses embedded ID3 USLT/SYLT and FLAC
  LYRICS tags as part of format metadata during extraction — read that first,
  no separate tagging library needed. Fall back to LRCLIB (free, no API key,
  https://lrclib.net, matched by artist/title/duration) when no embedded
  lyrics are found. Cache fetched lyrics in Room keyed by track id.
- Album covers: same idea — Media3 extracts embedded APIC/FLAC picture frames
  during extraction, use that first. Fall back to the iTunes Search API
  (free, no key, matched by album+artist) when no embedded art is found.
  Cache resolved cover URLs/bitmaps in Room/disk cache keyed by album id.
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
  auth/       GoogleSignInManager, DriveAuthorizationManager, DriveTokenProvider
  data/drive/ Drive API wrapper, repository
  data/local/ Room entities + DAOs, DataStore settings repository
  playback/   MusicPlaybackService, cache providers, LayeredDataSourceFactory
  download/   MusicDownloadService, DownloadHolder
  ui/         library, album detail, player, queue, settings

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
  a nullable driveTokenProvider set by MainActivity once it starts (see the
  known limitation noted in that file — cold-starting playback before
  MainActivity has ever run isn't handled yet)
- playback/DriveDataSourceFactory.kt — AuthenticatingHttpDataSource injects a
  fresh Bearer token per request and retries once on 401; layered
  CacheDataSource.Factory chain (download cache, read-only here -> streaming
  cache, read+write -> auth http). Matches the download -> streaming ->
  network resolution order.
- playback/MusicPlaybackService.kt — MediaSessionService wiring ExoPlayer to
  the layered factory

MainActivity: launch now always attempts silent sign-in first (Loading state,
no button flash); on success it lists Drive audio files and each row is
tappable, building a MediaItem and playing it through a MediaController bound
to MusicPlaybackService. `./gradlew assembleDebug` succeeds, and the FULL
chain — silent sign-in, Drive listing, tap-to-play, auth'd network fetch,
streaming-cache write, MediaCodec decode, and the system media notification
with embedded album art (Media3 extracted it from the MP3's ID3 tags) — has
been verified live on the musicdrive_test emulator. No downloads, folder
picker, or real UI yet — this is still the tap-a-row-in-a-LazyColumn
smoke-test screen.

## Next steps
1. Music folder picker: in-app Drive folder browser, persist chosen root
   folder id in DataStore, scope the library index to it
2. Material 3 UI pass: library grid, album detail view, mini-player + full
   player (YouTube Music-style layout, not visual clone)
3. Lyrics: embedded-tag extraction via Media3, LRCLIB fallback
4. Automatic album covers: embedded-art extraction via Media3, iTunes Search
   API fallback
5. Android Auto: MediaLibraryService browsing tree + automotive app
   descriptor
6. Downloads: Media3 DownloadManager writing into the download cache,
   per-song/per-album, never evicted
7. Room-cached Drive index (currently every launch re-lists all of Drive)

## Reference
- androidx/media demo apps are the canonical reference for DownloadManager
- Considered forking mardous/BoomingMusic (has Jellyfin/Navidrome remote-source
  abstraction that a Drive backend could slot into). GPL-3.0 — fine for personal
  use, but publishing would require releasing source.
