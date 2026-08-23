package com.ettore.musicdrive.ui

import androidx.compose.ui.unit.dp

/**
 * Shared tile sizing for every grid-style browse screen (Home, Albums,
 * Artists): tuned so a typical phone width (~360-430dp) lands on exactly 3
 * columns, wider screens (large phones/tablets) get more. Adaptive rather
 * than a hard-coded column count so it still fills the width sensibly on
 * unusual screen sizes.
 */
val GRID_TILE_MIN_SIZE = 100.dp
val GRID_SPACING = 8.dp
