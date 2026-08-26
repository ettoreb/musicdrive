# Multi-source library: design plan

Status: **implemented and verified live** (see CLAUDE.md's "Multi-source
library" entry for the implementation notes and live-verification details).
This doc is kept as the design record. Covers adding a local on-device
folder as a second, combinable music source alongside Google Drive.

## Selection model (as specified)

- **Local folder**: independent on/off toggle. Usable alone or combined with
  a cloud source.
- **Cloud**: single-select, at most one provider active — `NONE` /
  `GOOGLE_DRIVE` (future providers extend this enum, never coexist).
- Valid combinations: Local only, Drive only, Local + Drive, (future) Local +
  X, X only. Never two cloud providers together.

## Current Drive-only assumptions (grounding for everything below)

Read in full before this plan: `DriveRepository.kt`, `AlbumEntity.kt` /
`TrackEntity.kt`, `LibraryDao.kt`, `LibraryRepository.kt`,
`DriveDataSourceFactory.kt`, `SettingsRepository.kt`, `AlbumArtRepository.kt`,
`DownloadTracker.kt`, `MusicDriveApplication.kt`, and the relevant slice of
`MainActivity.kt`.

Key facts that shape this plan:

- **`track.id` (a raw Drive file id) is the universal track identity.** It's
  the Room PK for `TrackEntity`, `LyricsEntity`, `AlbumYearEntity` (album-id
  keyed, but same shape), `TrackOrderEntity`, `AlbumTagsEntity`, the FK in
  `PlayCountEntity.trackId`, the `DownloadRequest.id` DownloadManager uses,
  and `MediaItem.mediaId` throughout playback (`MainActivity.playAlbum`,
  `PlayerUiState.mediaId`, `trackLocations` lookup). Any multi-source change
  has to either keep this id space collision-free across sources or
  namespace it — see §3.
- **`AlbumEntity.rootFolderId` assumes exactly one active root** (the
  in-code comment says so explicitly). `LibraryDao.replaceLibrary` /
  `clearAll` are built around "one root swap wipes everything." Two sources
  active at once breaks that assumption — see §3 and §7.
- **The album-boundary / multi-disc-merge heuristics in
  `DriveRepository.collectAlbumFolders`** (folder-has-audio-files ⇒ leaf
  album, `discFolderPattern` merge, `artistHint` = root's direct child) only
  ever look at folder **names** and a **has-audio-files** boolean — never at
  Drive-specific fields. This is good news, confirmed in §4.
- **Auth/network is baked into three places, not one**:
  `DriveDataSourceFactory.buildDriveDataSourceFactory` (playback + downloads,
  layered cache chain), `DriveRepository` (folder/file listing, OAuth
  `Drive` client), and — easy to miss — **`AlbumArtRepository`**, which
  builds raw `https://www.googleapis.com/drive/v3/files/{id}?alt=media` URLs
  and Bearer headers directly in `openRetriever()` and
  `extractEmbeddedTrackNumber()` (lines 279–312). Cover art, release year,
  display-tag correction, and real track order all go through this, so it
  has to become source-aware too even though the task didn't name it
  explicitly — otherwise local tracks silently get no art/year/tags/order.
- **`DownloadTracker`** builds `DownloadRequest`s from `driveMediaUri(track.id)`
  — meaningless for local files (§5).
- **`MusicLibrarySessionCallback`** (Android Auto) reads
  `settingsRepository.libraryRootFolderId` and
  `libraryDao.getAlbumsWithTracks(rootFolderId)` directly — affected by the
  Room/settings shape change, called out as a follow-up, not solved here.

---

## 1. `MusicSource` abstraction

New package `data/source/`. Source-agnostic data classes replace the
Drive-named ones at the abstraction boundary (existing `DriveAlbum`/
`DriveAudioFile` stay as Drive's concrete implementation detail, or get
renamed — naming bikeshed, not a blocker):

```kotlin
enum class SourceType { DRIVE, LOCAL }

data class SourceTrack(
    val sourceType: SourceType,
    val rawId: String,        // Drive file id, or SAF document id
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

data class SourceAlbum(
    val sourceType: SourceType,
    val rawId: String,        // Drive folder id, or SAF document id
    val name: String,
    val artistHint: String?,
    val tracks: List<SourceTrack>,
)

interface MusicSource {
    val type: SourceType

    /** Same contract as DriveRepository.listLibraryAlbums today: depth-first album search + track listing under one root. */
    suspend fun listLibraryAlbums(rootId: String): Result<List<SourceAlbum>>

    /** The playable content/http URI for a track. */
    fun mediaUri(track: SourceTrack): Uri

    /** Opens a MediaMetadataRetriever positioned at this track, however this source needs to (Bearer header vs. context+content-Uri). Used by AlbumArtRepository for art/year/tags/track-order. Null if the track can't currently be opened (e.g. revoked SAF grant, network failure). */
    suspend fun openRetriever(track: SourceTrack): MediaMetadataRetriever?

    /** Whether this source needs network auth headers on its playback DataSource chain (true for Drive, false for local). */
    val requiresNetworkAuth: Boolean
}
```

`DriveMusicSource` wraps today's `DriveRepository` + auth. `LocalMusicSource`
wraps SAF (`DocumentsContract`, no `DriveTokenProvider` at all —
`requiresNetworkAuth = false`, `openRetriever` uses
`retriever.setDataSource(context, contentUri)`, no token/headers).

This interface is deliberately **not** trying to unify Drive's OAuth-token
model with a hypothetical future cloud provider's auth model — that's
scoped for whenever provider #2 actually gets built. The only contract a
future provider must satisfy is these four members.

`AlbumArtRepository` changes from building Drive URLs itself to taking a
`(sourceType) -> MusicSource` lookup and delegating `openRetriever` to the
right one; its extraction logic (`extractEmbeddedPicture`,
`extractEmbeddedYear`, `extractEmbeddedAlbumTags`, `extractEmbeddedTrackNumber`,
`isPlausibleTagValue`) is unchanged — it only ever consumed a
`MediaMetadataRetriever`, never the URL-building itself, once this moves out.

## 2. Settings model

Replace the single `libraryRootFolderId: Flow<String?>` in
`SettingsRepository` with:

```kotlin
enum class CloudProvider { NONE, GOOGLE_DRIVE }

val localFolderEnabled: Flow<Boolean>       // independent on/off
val localFolderTreeUri: Flow<String?>       // persisted SAF tree Uri.toString(), survives being toggled off

val cloudProvider: Flow<CloudProvider>      // single-select
val driveRootFolderId: Flow<String?>        // meaningful only while cloudProvider == GOOGLE_DRIVE, survives switching to NONE and back
```

Both sources keep their "last picked location" even while disabled —
turning local off doesn't forget the SAF tree, switching cloud to `NONE`
doesn't forget the Drive root. Re-enabling either is instant, no re-picking.
This mirrors nothing in the current code (today there's only one root and
switching it always clears — see §7 for why that stays true for an actual
folder *change*, just not for a toggle).

**Migration for existing installs** (DataStore, not Room — no destructive-
migration policy applies, each key just reads with a default): keep the
on-disk key name `library_root_folder_id` for `driveRootFolderId` so an
existing value migrates for free. `cloudProvider` has no stored key yet on
upgrade, so its default logic must be:

```kotlin
prefs[CLOUD_PROVIDER_KEY]?.let { runCatching { CloudProvider.valueOf(it) }.getOrNull() }
    ?: if (prefs[DRIVE_ROOT_FOLDER_ID_KEY] != null) CloudProvider.GOOGLE_DRIVE else CloudProvider.NONE
```

so an existing signed-in-with-a-root-chosen user comes back up as
"Drive active," not "no source configured."

**New requirement not present anywhere in this codebase today**: picking a
SAF tree via `ActivityResultContracts.OpenDocumentTree()` must call
`contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
at pick time, or the grant doesn't survive a reboot. On app start, verify the
stored tree URI is still in `contentResolver.persistedUriPermissions` before
trusting it — SAF grants can be silently revoked externally (e.g. don't
reliably survive a device backup/restore); if revoked, treat local as
unconfigured and prompt to re-pick rather than crashing on a dead URI.

## 3. Room schema changes

**Track/album identity must become source-namespaced.** Two independently-
issued raw ids (a Drive file id, a SAF document id like
`primary:Music/Artist/Album/01.mp3`) have no collision guarantee against
each other, and every entity keyed by "the track id" (`PlayCountEntity`,
`LyricsEntity`, `AlbumYearEntity`, `TrackOrderEntity`, `AlbumTagsEntity`,
`DownloadRequest.id`, `MediaItem.mediaId`) needs one unambiguous global id
space. Recommended: a compound string id, `"${sourceType.name}:$rawId"`
(e.g. `"DRIVE:1a2b3c"`, `"LOCAL:primary:Music/..."`) — every one of those
call sites already types the id as a plain `String`, so this is a pure
value-format change, not a type/schema-shape change at any of those sites.

```kotlin
@Entity(indices = [Index("sourceType"), Index("rootId")])
data class AlbumEntity(
    @PrimaryKey val id: String,      // compound, e.g. "DRIVE:<folderId>"
    val name: String,
    val artistHint: String?,
    val sourceType: String,          // SourceType.name — explicit column, not just parsed from id prefix, so "delete all LOCAL rows" is a plain indexed query
    val rootId: String,              // renamed from rootFolderId: the active root for that source (Drive folder id, or local tree's root document id)
)

@Entity(indices = [Index("albumId")])
data class TrackEntity(
    @PrimaryKey val id: String,      // compound, e.g. "LOCAL:<documentId>"
    val albumId: String,             // FK -> AlbumEntity.id, already compound-consistent
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
)
```

**`LibraryDao` scoping changes.** Today exactly one root is ever active, so
`getAlbumsWithTracks(rootFolderId)` / `replaceLibrary` / `clearAll` are
built around a single global swap. With Local and Drive both potentially
active simultaneously, both sources' rows must coexist in the same tables.
Proposed shape:

- `getAlbumsWithTracks(): List<AlbumWithTracks>` — **no source filter**,
  returns everything cached (personal-library scale, same "just read it
  all" pattern `AlbumArtRepository`'s eager year/tags resolution already
  uses). The merge-by-enabled-sources step happens one layer up, in the
  repository/ViewModel layer (§6) — Room stays a dumb source-agnostic
  cache of whatever's been fetched.
- `replaceLibraryForSource(sourceType, rootId, albums, tracks)` — same
  transaction shape as today's `replaceLibrary`, scoped by
  `(sourceType, rootId)` instead of just `rootFolderId`, so refreshing one
  source never touches the other's rows.
- `clearSource(sourceType)` — deletes only that source's rows (used when a
  source's *root itself* changes — a different Drive folder picked, or a
  different SAF tree picked — not when a source is merely toggled off; see
  §7 for that distinction).

**Migration**: bump `MusicDriveDatabase` to version 8, still
`fallbackToDestructiveMigration(dropAllTables = true)` per existing policy —
every Room table wipes and re-populates lazily (library index, lyrics,
year, track-order, tags — all already-established as fine to lose and
re-resolve). One real, non-obvious hazard this creates: **`PlayStatsRepository`'s
JSON backup** (`filesDir/play_stats_backup.json`, deliberately kept *outside*
Room so schema bumps don't erase real listening history — see CLAUDE.md's
"Drive pagination, real track order, and play-stats persistence" entry)
stores `trackId` keyed by the **old, bare Drive file id**. If mediaId
format changes to `"DRIVE:<fileId>"`, `restoreFromBackupIfEmpty()` needs an
explicit one-time transform — prefix any legacy backup key that doesn't
already contain the `SOURCE:` delimiter with `"DRIVE:"` before reinserting —
or every existing user's play-count history silently fails to match up
against the new mediaIds and looks like it reset to zero, defeating the
entire reason that backup file exists. This must be handled explicitly in
`PlayStatsRepository`, not left as a side effect of the schema bump.

## 4. Album-boundary / multi-disc-merge heuristics on SAF

Confirmed: `collectAlbumFolders`, `discFolderPattern`, `discNumber()`,
`leadingTrackNumberPattern`, `trackNumber()`, and `trackOrderComparator` all
operate purely on folder/file **names** and a folder's
**directly-contains-audio-files** boolean (`folderHasAudioFiles`) — nothing
Drive-specific leaks into the algorithm itself. The plan is to extract the
walk into a source-agnostic function parameterized over three primitives
(list child folders, list child audio files, resolve a folder's name),
which both `DriveRepository` and a new `LocalMusicSource` implement:

```kotlin
interface FolderListing {
    suspend fun listSubfolders(folderId: String): List<FolderRef>       // id + name
    suspend fun listAudioFiles(folderId: String): List<SourceTrack>     // direct children only
}
```

`collectAlbumFolders`'s body moves to a shared function taking a
`FolderListing`; the regex/comparator logic is untouched.

**SAF implementation notes:**
- `DocumentsContract.buildChildDocumentsUriUsingTree` +
  `ContentResolver.query` (columns: `DOCUMENT_ID`, `DISPLAY_NAME`,
  `MIME_TYPE`, `SIZE`) — **not** the `DocumentFile` wrapper, which does one
  binder round-trip per child for `isDirectory`/`getName`. A single query
  per folder returns every child's type in one pass, actually simpler than
  Drive's split `listFoldersRaw` + `folderHasAudioFiles` (two separate
  queries) since SAF's one query already carries MIME type per row.
- No pagination concern — `ContentResolver.query` returns a full `Cursor`,
  no `nextPageToken` equivalent, so `listAllPages`'s exhaustive-paging logic
  has no local analogue.
- **Real risk carried over from a documented past incident**: SAF calls are
  binder-IPC-backed, the same binder thread pool that got starved by
  unbounded concurrent `MediaMetadataRetriever` opens (see CLAUDE.md's
  year-resolution `Semaphore(3)` fix). `collectAlbumFolders` recursion
  launches one `async` per subfolder with **no concurrency bound** — fine
  for Drive (HTTP, not binder), but a large local tree recursing unbounded
  over SAF's binder-backed provider is a plausible repeat of that same
  failure mode. Recommend bounding the local folder-walk's recursive
  concurrency with a `Semaphore` from the start, rather than waiting to
  find it live.
- SAF document ids (e.g. `primary:Music/Artist/Album`) are stable and
  unique within one tree grant — safe to use directly as `rawId`.

## 5. Local playback bypasses the cache layers

**Playback.** `MusicPlaybackService`'s `ExoPlayer` is built once with a
single `MediaSource.Factory` for the player's whole lifetime (it must
handle a Drive album played now and a local album played later, in the same
session) — but any one *queue* (`controller.setMediaItems(...)` from
`playAlbum`) is always single-source, since an album folder always belongs
to exactly one source. So no per-`MediaItem` source dispatch is needed
inside a queue — only the player-wide factory needs to route by URI scheme.

Concretely: wrap today's `buildDriveDataSourceFactory(...)` output as the
**base** factory of a `DefaultDataSource.Factory(context, driveBasedFactory)`.
Media3's `DefaultDataSource` already special-cases `content://` →
`ContentDataSource` and `file://` → `FileDataSource`, delegating only
`http(s)://` to the wrapped base — so a local track's `content://` URI
never touches the Drive auth layer or either `CacheDataSource` at all,
automatically, with no new branching code anywhere else in playback. This
is close to a one-line change in `MusicPlaybackService`/
`DriveDataSourceFactory`.

`MusicSource.mediaUri()` reflects this split: `DriveMusicSource` returns the
existing `https://www.googleapis.com/drive/v3/files/{id}?alt=media`,
`LocalMusicSource` returns
`DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)`.

**Downloads.** Explicit per-song/album downloads exist to turn a *remote*
file into a locally-cached one — meaningless for a file that's already
local. Local-sourced albums should not offer a download action at all:

- `AlbumDetailScreen`'s "Download album" `FilledIconButton` — hidden (not
  disabled) when `album.sourceType == LOCAL`.
- `DownloadTracker` / `MusicDownloadService` / `DownloadManager` stay
  entirely Drive-only; no local-source `DownloadRequest`s are ever created,
  so no changes needed inside them.
- Settings' Storage section and Downloads popup never show local albums,
  since none will ever have a `DownloadRequest`. Local files already occupy
  their own space wherever the user picked them — outside this app's
  managed storage/eviction model entirely, and outside the "Storage" limit
  (streaming cache + downloads) by definition. Worth one line of Settings
  copy clarifying this ("Local files aren't counted here — they're already
  on your device.") so the numbers don't look like they're silently
  ignoring local usage; not a functional requirement.

## 6. Unified Home/Library/Artists merge

Today: one `LibraryRepository.loadLibrary(rootFolderId)` `Flow` tied to a
single Drive root, collected once in `MainActivity.loadLibrary()`, feeding
`AppState.LibraryLoaded.albums`.

Proposed: the repository (rename conceptually to reflect multi-source, e.g.
keep `LibraryRepository` but parameterize) exposes one `loadLibrary()` with
no root-id parameter, that internally:

1. Reads the settings flows (`localFolderEnabled`, `localFolderTreeUri`,
   `cloudProvider`, `driveRootFolderId`) to determine which
   `(MusicSource, rootId)` pairs are currently active — 0, 1, or 2 of them.
2. Runs each active source's own cache-first-then-refresh
   `Flow<Result<List<SourceAlbum>>>` (identical pattern to today's
   `loadLibrary`, just generalized over `MusicSource` instead of hardcoded
   `DriveRepository`).
3. Combines them with `kotlinx.coroutines.flow.combine` (or manual
   `merge`-and-accumulate, since the "emit cache immediately, then again on
   refresh" per-source behavior should be preserved independently per
   source, not block on both) into one `List<SourceAlbum>` — a plain list
   union, since compound ids (§3) guarantee no collision between sources.

**Degrades cleanly to single-source**: when only one source is enabled, its
`Flow` is the only one included in the combine — not "included but always
empty" — so the merged output is byte-for-byte what today's single-source
flow already produces. Zero sources enabled is a real, new state (possible
even after being fully signed into Drive, if the user disables it and never
enables local) — needs a new `AppState` case (e.g. `NoSourceConfigured`,
generalizing today's `PickingFolder`) prompting the user to enable
something in Settings.

Everything downstream of `state.albums` — `groupByArtist()`, the album-sort
comparators, `LibrarySearch.searchLibrary`, the Home "most played"/"Liked
Songs" computation, `trackLocations` — already operates on a plain
`List<DriveAlbum>` and is source-blind by construction (they never inspect
where an album came from), so none of that needs to change, **provided the
merge step below produces one already-deduplicated list before anything
downstream sees it.**

### Cross-source dedup: local takes precedence over Drive (decided)

Confirmed: same-named albums across sources merge into **one tile**, and
within a matched album, a **song available locally always displays instead
of its Drive counterpart** — the goal is availability/usability (offline,
no streaming, no cache-eviction risk), not preserving both copies as
separate entries. This runs as a pure post-processing step over the
combined `List<SourceAlbum>` from §6's `combine`, before grouping/sorting/
search ever see it — none of those need to know dedup happened.

**Matching, not exact-id equality** (a local rip and a Drive folder for the
"same" album never share an id). Normalize for comparison:

```kotlin
private fun normalizeForMatch(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]"), "")  // case/whitespace/punctuation-insensitive
```

- **Album match key**: `normalizeForMatch(artistHint.orEmpty()) to normalizeForMatch(name)`.
  Two albums (one per source) with equal keys are the same album.
- **Track match key**, only evaluated within an already-matched album pair:
  `normalizeForMatch(track.name.withoutAudioExtension())`.

Exact-after-normalization only for v1 (no fuzzy/edit-distance matching) —
simple, and this codebase already leans on this exact style of
alphanumeric-only normalization elsewhere (`resolveArtistArt`'s filename
sanitizing, `isPlausibleTagValue`'s letter/digit ratio check). Flag fuzzy
matching as a future refinement if exact normalization proves too strict
against real libraries (e.g. `"Song (Remastered)"` vs `"Song"`), not
designed further here.

**Merge algorithm for a matched `(localAlbum, driveAlbum)` pair:**

1. Output album keeps **local's** `id`, `name`, `artistHint` — local wins
   the display identity outright. (If local's tag-corrected name/artistHint
   isn't resolved yet — `resolveDisplayTags` hasn't run — fall back to
   Drive's for those still-null fields only, same "tag corrects display,
   never blocks on it" spirit as today's `resolveDisplayTags`.)
2. Output tracks = **all of local's tracks**, **plus** any Drive track
   whose track-match-key has no equal among local's tracks (the Drive-only
   songs — gap-fill, not override).
3. **Deliberately no re-sorting/interleaving logic in the merge step
   itself.** The union list is handed to the existing, unchanged
   `AlbumArtRepository.resolveTrackOrder()` exactly as any other album's
   track list would be — it already resolves each track's real running
   order from *that track's own* embedded tag (via whichever `MusicSource`
   owns it, per §1) and sorts the whole list by that, independent of where
   a track came from. Compound ids (§3) make this safe: two tracks from
   different sources never collide, so they interleave correctly by real
   track number for free, with no special-casing needed here.
4. An album present on **only one** source passes through unchanged — this
   is the existing "degrades cleanly to single-source" behavior, unaffected
   by adding matching logic (no match found ⇒ no merge attempted).

**Known follow-on effects, not solved here, worth being aware of before
implementing:**

- **Downloads can go stale/orphaned.** If a Drive track was downloaded
  before a matching local copy was added, that download still exists in
  `DownloadManager`/Room but its song no longer displays (the local copy
  hides it). Not a crash risk — `DownloadsDialog`'s existing
  `groupDownloadsByAlbum` already buckets an unresolvable album id into an
  "Other downloads" group rather than erroring — but it's dead storage the
  user can't see tied to a visible song anymore. Worth a manual "clean up
  downloads for hidden tracks" pass later; not designed here.
- **Play-count history doesn't merge.** If the *same* song was played both
  as a Drive track before, and later as its local counterpart, those are
  two different compound track ids in `PlayCountEntity` — Home/Stats would
  show it as two separate, lower-count entries rather than one combined
  count. No proposed fix here; flagging so it isn't a surprise later.

### Android Auto: removed, not deferred

Per explicit decision, this pass **removes** Android Auto support entirely
rather than adapting it for multi-source — see the new §8 below for the
exact removal plan. `MusicLibrarySessionCallback`'s
`settingsRepository.libraryRootFolderId` / `libraryDao.getAlbumsWithTracks
(rootFolderId)` reads are moot once that file is deleted.

## 7. Settings UI: "Music Sources"

Replaces today's "Library" section (currently just one
`SettingsActionRow("Change library folder")`).

```
Music Sources
┌─────────────────────────────────────────┐
│ Local files                      [ ⚪—● ]│  ← Switch, independent toggle
│   /storage/emulated/0/Music              │  ← sub-row, shown when enabled;
│                                           │     tapping it (or first-time
│                                           │     toggle-on) launches SAF's
│                                           │     OpenDocumentTree picker
├─────────────────────────────────────────┤
│ Cloud                                     │
│  ⚪ None                                  │
│  ⚫ Google Drive                          │  ← SettingsRadioRow, single-select
│     Change Drive folder             〉    │  ← only shown when Drive selected;
│                                           │     same click target as today's
│                                           │     row, just relabeled
└─────────────────────────────────────────┘
```

`Switch` is a new pattern for this codebase (today's `SettingsScreen.kt`
only uses `RadioButton`) — fine, it's the standard M3 widget for an
independent boolean, distinct from the existing single-select radio rows.
No new in-app picker screen is needed for local folder selection — SAF's
`OpenDocumentTree` is a system-level UI, unlike Drive's custom
`FolderPickerScreen` (which exists only because the Drive API has no
picker UI of its own).

**Selecting "Google Drive"** when not yet signed in triggers today's
existing sign-in flow; when signed in but no root ever chosen, opens
`FolderPickerScreen`; when a root was previously chosen (including one from
before Drive was switched to `NONE`), re-enables it directly with no
re-picking. **Selecting "None"** turns Drive off without forgetting
`driveRootFolderId`.

**On/off vs. clear — the one real behavioral fork, worth confirming
explicitly:**

| Action | Room effect |
|---|---|
| Toggle local **off** | Filter only — rows stay cached, `clearSource` is **not** called. Re-enabling is instant. |
| Toggle local **on**, same tree URI as before | Nothing to do — cache is already there (may show slightly stale until the background refresh lands, same as any relaunch today). |
| Switch cloud to **None** | Filter only — Drive rows stay cached, same reasoning as local off. |
| Switch cloud to **Google Drive**, same root as before | Nothing to do, same as local re-enable. |
| Pick a **different** Drive root (via "Change Drive folder") | `clearSource(DRIVE)` — same destructive behavior as today's `clearCache()`, because the root itself changed, not just whether it's shown. |
| Pick a **different** local SAF tree (re-tapping the folder row) | `clearSource(LOCAL)` — same reasoning. |

This preserves today's one real precedent (`LibraryRepository.clearCache()`
/ the "only one library root is ever active at a time" comment on
`AlbumEntity`) for an actual folder *change*, while treating a plain on/off
toggle as new, non-destructive behavior — turning local off to save a
Settings screen glance and back on five minutes later shouldn't force a
full folder rescan.

## 8. Android Auto removal (decided: remove, not adapt)

Confirmed via `grep`, this is the complete, exact set of Auto-only surface
to remove — nothing here is shared with non-Auto playback except where
noted:

- **`playback/MusicLibrarySessionCallback.kt`** — delete entirely (the
  whole browse tree, `onSearch`/`onGetSearchResult`).
- **`playback/ListenableFutureBridge.kt`** — delete. Confirmed
  Auto-only (`CoroutineScope.toListenableFuture` exists solely to bridge
  suspend calls into `MediaLibrarySession`'s `ListenableFuture` callback
  API); nothing else references it.
- **`playback/MusicPlaybackService.kt`** — revert `MediaLibraryService` back
  to `MediaSessionService` (its pre-Auto base class); `mediaLibrarySession:
  MediaLibrarySession` back to a plain `mediaSession: MediaSession`; drop
  the `MusicLibrarySessionCallback(app, serviceScope)` constructor arg (plain
  `MediaSession.Builder(this, player)`); drop `onGetSession`'s
  `MediaLibrarySession` return type back to `MediaSession`; drop the now-
  unused `serviceScope`/`SupervisorJob` (only existed to back the callback's
  suspend work). **Keep** `setSessionActivity(sessionActivityPendingIntent)`
  — `MediaSessionService` supports it too, it's what makes the system
  notification/lock-screen art tappable to reopen the app, not an
  Auto-specific feature; just correct the comment above it (currently reads
  "Powers Android Auto's 'open app' affordance," which overstates what it's
  for).
- **`AndroidManifest.xml`** — remove the `<meta-data
  android:name="com.google.android.gms.car.application">` entry (lines
  22–24) and the `<action
  android:name="android.media.browse.MediaBrowserService">` intent-filter
  entry inside `MusicPlaybackService`'s `<service>` block (line 46) — keep
  the `androidx.media3.session.MediaSessionService` action, still required
  for normal playback binding.
- **`res/xml/automotive_app_desc.xml`** — delete the file.
- **`data/LibraryRepository.kt`** — `toDriveAlbum()` itself stays (used by
  `LibraryRepository` internally), just drop its doc comment's "Shared with
  the Android Auto browse tree" line, now inaccurate.
- **`data/LibrarySearch.kt`** — **keep**. Confirmed shared with the phone
  `HomeScreen.kt`'s search overlay (`searchLibrary` has two call sites:
  `MusicLibrarySessionCallback` and `HomeScreen`) — only the Auto call site
  goes away.
- Not touched: the Desktop Head Unit tooling note in CLAUDE.md's Next Steps
  is a dev-environment note, not code; can be cleaned up in the same commit
  as a doc-only aside, not part of this plan.

---

## Decisions locked in

1. **Cross-source dedup**: merge same-named albums into one tile; within a
   matched album, a local song always displays instead of its Drive
   counterpart. Detailed algorithm in §6 above.
2. **Android Auto**: remove entirely this pass (§8 above), not adapted for
   multi-source.
3. **Compound-id format**: `"DRIVE:<id>"` / `"LOCAL:<id>"` as originally
   proposed — confirmed.

No further open questions — ready to implement on confirmation.
