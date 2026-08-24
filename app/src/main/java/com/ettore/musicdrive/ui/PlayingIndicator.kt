package com.ettore.musicdrive.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * A small "now playing" equalizer marker - 3 bars independently animating to
 * random heights while a track plays, used in place of a track number/index
 * wherever a list row is the currently-playing track (album detail, queue).
 * Each bar loops its own Animatable.animateTo(random) on a staggered
 * duration so they don't move in lockstep - cheap, no Canvas needed.
 */
@Composable
fun PlayingIndicator(color: Color, modifier: Modifier = Modifier) {
    val bar1 = remember { Animatable(0.4f) }
    val bar2 = remember { Animatable(0.9f) }
    val bar3 = remember { Animatable(0.6f) }

    listOf(bar1 to 320, bar2 to 260, bar3 to 380).forEach { (bar, durationMs) ->
        LaunchedEffect(bar) {
            while (true) {
                bar.animateTo(Random.nextFloat().coerceIn(0.15f, 1f), animationSpec = tween(durationMs))
            }
        }
    }

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(bar1, bar2, bar3).forEach { bar ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(bar.value)
                    .background(color),
            )
        }
    }
}
