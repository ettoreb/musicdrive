# MusicDrive

Android music player that streams from Google Drive, functionally similar to
YouTube Music (not a visual clone). Personal/sideloaded app, not for Play Store.

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

## Core requirements
1. Stream audio files from Google Drive
2. Automatic streaming cache with a USER-CONFIGURABLE size cap, LRU eviction
   when the cap is exceeded
3. Separate, permanent offline downloads (per-song and per-album) that are
   NEVER auto-evicted
4. Background playback, media notification, queue, mini-player + full player

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
- auth/GoogleSignInManager.kt — Credential Manager sign-in (identity only)
- auth/DriveAuthorizationManager.kt — Identity.getAuthorizationClient consent
  flow for drive.readonly, via ActivityResultLauncher<IntentSenderRequest>
- auth/DriveTokenProvider.kt — in-memory token cache, forceRefresh param
- data/drive/DriveRepository.kt — lists audio files via com.google.api.services.drive
MainActivity has a smoke-test screen: "Sign in with Google" -> Drive
authorization -> lists audio files in a LazyColumn. `./gradlew assembleDebug`
succeeds. NOT yet tested live on an emulator/device (needs manual Google
consent flow) — do that before trusting the auth path. No playback/download
code yet.

## Next steps
1. Run the app on an emulator/device and manually verify the sign-in ->
   Drive-authorization -> file-listing smoke test actually works end to end
2. Move on to playback (Media3/ExoPlayer, MediaSessionService, the two
   SimpleCache instances)

## Reference
- androidx/media demo apps are the canonical reference for DownloadManager
- Considered forking mardous/BoomingMusic (has Jellyfin/Navidrome remote-source
  abstraction that a Drive backend could slot into). GPL-3.0 — fine for personal
  use, but publishing would require releasing source.
