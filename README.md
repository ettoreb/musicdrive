# musicdrive

An Android music player that streams your library straight from Google
Drive — functionally similar to YouTube Music, not a visual clone. Personal
project, sideloaded (not published to the Play Store).

## Status

🚧 Early development. Sign-in, Drive access, and background playback (with
the streaming cache and media notification) are all working end to end; the
real UI, downloads, and the rest of the roadmap are still to come.

| Feature | Status |
|---|---|
| Google sign-in (Credential Manager, remembered across launches) | ✅ Working |
| Drive authorization (`drive.readonly`) | ✅ Working |
| List audio files from Drive | ✅ Working (smoke test) |
| Playback (Media3/ExoPlayer, background, notification, queue) | ✅ Working (smoke test) |
| Streaming cache (configurable size, LRU eviction) | ✅ Working (eviction limit not yet user-facing) |
| Music folder picker (choose Drive library root) | ✅ Working |
| Permanent offline downloads | ⏳ Planned |
| Material 3 UI, album view, mini/full player | ⏳ Planned |
| Lyrics (embedded + LRCLIB fallback) | ⏳ Planned |
| Automatic album covers (embedded + online fallback) | ⏳ Planned |
| Android Auto | ⏳ Planned |

## How it works

- **Library model**: a Drive folder = an album, found by searching down from
  your chosen root for the first folder(s) that directly contain audio files
  — so both `root/Album` and `root/Artist/Album` layouts work.
- **Playback**: Media3/ExoPlayer with two separate caches — an
  auto-managed streaming cache with a user-configurable size cap (LRU
  eviction), and a separate permanent cache for explicit downloads that are
  never evicted.
- **Auth**: Credential Manager for sign-in, the Identity API's
  `AuthorizationClient` for the separate Drive `drive.readonly` scope
  consent.

See [CLAUDE.md](CLAUDE.md) for the full architecture notes, environment
setup, and the detailed roadmap this table summarizes.

## Building

Requirements: JDK 17, Android SDK (compileSdk 37, AGP 9.3.1).

1. Create an OAuth 2.0 **Web** client ID in
   [Google Cloud Console](https://console.cloud.google.com/) (APIs & Services
   → Credentials) with the Drive API enabled, and add yourself as a test user
   under the OAuth consent screen.
2. Add it to `local.properties` (not committed):
   ```
   GOOGLE_WEB_CLIENT_ID=your-client-id.apps.googleusercontent.com
   ```
3. Build:
   ```
   ./gradlew assembleDebug
   ```

## License

Personal project — no license file yet; all rights reserved for now.
