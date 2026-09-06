package com.flex.elefin.player.mpv

/** mpv string-list options use commas as separators; commas inside auth values must be escaped. */
fun toMpvHeaderOption(headers: String): String = headers.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,") }
