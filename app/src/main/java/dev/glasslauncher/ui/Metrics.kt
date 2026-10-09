package dev.glasslauncher.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * tvOS 27 home-screen geometry, measured from 1080p frames (see the tvos27-guidelines skill,
 * visual-spec.md) and halved because the UI runs at 960x540dp. [textScale] is the Text Size
 * setting: like tvOS's Dynamic Type it grows tiles and gutters too, dropping columns as it does.
 */
@Immutable
data class Metrics(val textScale: Float = 1f) {
    val columns: Int = when {
        textScale >= 1.3f -> 4
        textScale >= 1.15f -> 5
        else -> 6
    }
    /** Content inset from the screen edge (tvOS ~90px). */
    val inset: Dp = 45.dp
    /** Gap between tiles in a row (tvOS ~48px). */
    val gutter: Dp = (24 * textScale).dp
    /** Tiles are 5:3 rounded rectangles (tvOS ~250x150px, radius 30px). */
    val tileAspect: Float = 5f / 3f
    val tileRadius: Dp = (15 * textScale).dp
    /** Space under each grid row for the focus label (tvOS row pitch ~260px). */
    val labelSpace: Dp = (55 * textScale).dp
    /** Dock tray: nearly full width, ~225px tall, radius ~60px, ~40px vertical padding. */
    val trayMargin: Dp = 22.dp
    val trayPadVertical: Dp = (20 * textScale).dp
    /** How far the tray's tiles move up to make room under them for the focused app's name. */
    val trayLabel: Dp = (16 * textScale).dp
    val trayRadius: Dp = 30.dp
    /** Where the tray's top edge sits when Home is at rest, leaving the top shelf above it (tvOS's place). */
    val trayTopAtRest: Dp = 384.dp

    /** The tray's height on a screen [screenWidth] wide: one row of tiles plus its padding. */
    fun trayHeight(screenWidth: Dp): Dp {
        val tile = (screenWidth - inset * 2 - gutter * (columns - 1)) / columns
        return tile / tileAspect + trayPadVertical * 2
    }

    /**
     * Where the tray's top sits: tvOS's place, unless larger text makes the tray taller than the room below
     * it (at 1.4× it ran ~15 dp off a 540 dp screen); then it rises just enough to keep a margin.
     */
    fun trayTop(screenWidth: Dp, screenHeight: Dp): Dp =
        minOf(trayTopAtRest, screenHeight - trayHeight(screenWidth) - 24.dp)
    /** Gap between the tray and the first grid row (tvOS ~72px). */
    val trayToGrid: Dp = 36.dp
    /** Where a focused grid row settles when scrolled. */
    val gridPivot: Dp = 181.dp
    /**
     * Floating chrome (status pill, Control Center) sits closer to the edges than content: tvOS 27 measures
     * 36–50 px at 1080p (18–25 dp), inside the 5% content safe area ([inset]) on purpose.
     */
    val chromeInset: Dp = 22.dp
}

val LocalMetrics = staticCompositionLocalOf { Metrics() }
