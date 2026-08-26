package com.ettore.musicdrive.data.source

/**
 * Matches "CD1", "CD 2", "Disc 1", "Disc 1 - Sounds Of The Universe", etc - the real-world naming
 * convention for multi-disc release subfolders. Shared by every MusicSource's own depth-first
 * album walk (DriveRepository.collectAlbumFolders and LocalMusicSource's SAF equivalent) since
 * the multi-disc-merge heuristic only ever looks at folder NAMES, never at anything provider-
 * specific.
 */
val discFolderPattern = Regex("""(?i)^(cd|disc)\s*(\d+)""")

fun discNumber(folderName: String): Int =
    discFolderPattern.find(folderName)?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE

/** Matches the leading track number in a filename, e.g. "01 - Song.mp3", "2. Song.mp3", "07 Song.flac". */
val leadingTrackNumberPattern = Regex("""^\s*0*(\d+)""")

/**
 * Track number parsed from the front of a filename, for sorting an album's tracks into their real
 * running order when no embedded tag is available (see AlbumArtRepository.resolveTrackOrder for
 * the tag-based order that takes priority over this). Falls back to Int.MAX_VALUE (sorts last,
 * then by name) for a track with no leading number at all.
 */
fun leadingTrackNumber(fileName: String): Int =
    leadingTrackNumberPattern.find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
