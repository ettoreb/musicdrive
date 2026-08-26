package com.ettore.musicdrive.data.source

/**
 * Which provider a track/album physically comes from. Threaded through as a prefix on every
 * track/album id (see [compoundId]) rather than a separate field on the model classes, since a
 * merged album (a local song taking precedence over its Drive counterpart - see
 * LibraryRepository's dedup step) can contain tracks from BOTH sources at once; per-track
 * provenance has to live on the id itself, not on the album.
 */
enum class SourceType { DRIVE, LOCAL }

private const val ID_SEPARATOR = ':'

private fun SourceType.idPrefix(): String = "$name$ID_SEPARATOR"

/**
 * Builds a globally-unique track/album id from this source's raw provider id (a Drive file id,
 * or a SAF document id like "primary:Music/Artist/Album/01.mp3" - note SAF ids can themselves
 * contain a colon, which is why [rawId] only ever splits on the FIRST colon).
 */
fun SourceType.compoundId(rawId: String): String = "${idPrefix()}$rawId"

/**
 * The source a compound id (see [compoundId]) was built by. Falls back to DRIVE for a
 * non-namespaced id (e.g. MainActivity's synthetic "liked-songs" playlist id, which isn't tied to
 * any one source) rather than throwing - such an id is never actually dispatched through a
 * MusicSource in practice, so this is purely a defensive default against a future call site doing
 * so unexpectedly.
 */
fun String.sourceTypeOfId(): SourceType = SourceType.entries.firstOrNull { startsWith(it.idPrefix()) } ?: SourceType.DRIVE

/** The raw, source-specific id underneath a compound id - what's actually needed to address the file (a Drive file id for a URL, a SAF document id for a content Uri). */
fun String.rawId(): String = substringAfter(ID_SEPARATOR)
