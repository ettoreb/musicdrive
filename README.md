# musicdrive

An Android music player that streams your library straight from Google
Drive — functionally similar to YouTube Music, not a visual clone. Personal
project, sideloaded (not published to the Play Store).

## Status

🚧 Early development, but the core loop works: sign in, pick your Drive
music folder, and browse a YouTube-Music-style Home / Search / Library /
Settings bottom-nav — a Home dashboard of your most-played songs and
artists plus a "Liked Songs" playlist, full-text search across your
library, an album grid with real cover art (dynamically tinting the player
per track), and play — with background playback, a streaming cache, a
mini/full player with swipe-to-skip, a queue view, synced karaoke-style
lyrics, and permanent per-album downloads. Android Auto is still to come.

| Feature | Status |
|---|---|
| Google sign-in (Credential Manager, remembered across launches) | ✅ Working |
| Drive authorization (`drive.readonly`) | ✅ Working |
| Music folder picker (choose Drive library root, from Settings) | ✅ Working |
| Bottom navigation: Home / Search / Library / Settings | ✅ Working |
| Home dashboard (most-played songs + artists, Liked Songs playlist) | ✅ Working |
| Search (albums, artists, songs) | ✅ Working |
| Artist grid → album grid → album detail (Material 3, YouTube-Music-style) | ✅ Working |
| Multi-disc releases merged into one album | ✅ Working |
| Album sort by release year (default), name, or track count | ✅ Working |
| Automatic album covers (embedded art + iTunes fallback, cached offline) | ✅ Working |
| Dynamic per-track player color from the album art | ✅ Working |
| Playback (Media3/ExoPlayer, background, notification) | ✅ Working |
| Mini-player + full-screen player (expand/collapse, seek, skip, swipe, repeat) | ✅ Working |
| Queue view (see and jump to any upcoming/previous track) | ✅ Working |
| Streaming cache (user-configurable size, LRU eviction) | ✅ Working |
| Permanent offline downloads (per-album) | ✅ Working |
| Room-cached library index (instant browse on relaunch) | ✅ Working |
| Synced lyrics with karaoke-style word highlighting + LRCLIB fallback | ✅ Working |
| Settings (cache size, light/dark/system theme, default sort) | ✅ Working |
| Android Auto | ⏳ Planned |

## How it works

- **Library model**: a Drive folder = an album, found by searching down from
  your chosen root for the first folder(s) that directly contain audio files
  — so both `root/Album` and `root/Artist/Album` layouts work. Cached in
  Room so relaunching browses instantly, refreshing quietly in the background.
- **Playback**: Media3/ExoPlayer with two separate caches — an
  auto-managed streaming cache with a user-configurable size cap (LRU
  eviction), and a separate permanent cache for explicit downloads that are
  never evicted. Tuned for fast tap-to-audio start, gapless transitions
  between an album's tracks, and survives the OS killing and restarting the
  app process mid-playback.
- **Auth**: Credential Manager for sign-in, the Identity API's
  `AuthorizationClient` for the separate Drive `drive.readonly` scope
  consent.
- **Lyrics**: embedded ID3 tags first, then LRCLIB's line-synced lyrics
  when available (word-by-word karaoke highlighting is synthesized by
  evenly distributing each line's words across its own timespan, since
  LRCLIB doesn't provide true per-word timing), falling back to plain
  static text when nothing's synced.

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
