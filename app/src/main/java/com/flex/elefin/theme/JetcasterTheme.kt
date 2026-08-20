package com.flex.elefin.theme

import androidx.compose.ui.graphics.Color

/**
 * Jetcaster theme colors.
 *
 * NOTE: This file was missing from the upstream repository (screens reference
 * `com.flex.elefin.theme.Jetcaster*` but it was never committed). Recreated for
 * this fork with the standard Jetcaster (Compose TV sample) color palette.
 */
val JetcasterBackground = Color(0xFF1B1225)
val JetcasterSurfaceVariant = Color(0xFF43385C)
val JetcasterPrimary = Color(0xFF8C7BD2)
val JetcasterOnSurface = Color(0xFFF1E9FF)
val JetcasterOnBackground = Color(0xFFF1E9FF)

/** Mutable primary color state, updated from the theme color picker in settings. */
var JetcasterPrimaryColorState: Color = JetcasterPrimary
