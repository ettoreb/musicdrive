package com.ettore.musicdrive.ui

private val audioExtensionPattern = Regex("""\.(mp3|flac|m4a|aac|wav|ogg|opus|wma)$""", RegexOption.IGNORE_CASE)

/** Drive filenames are shown as track titles until real ID3 metadata loads; strip the file extension so ".mp3" doesn't show up as part of the song name. */
fun String.withoutAudioExtension(): String = audioExtensionPattern.replace(this, "")
