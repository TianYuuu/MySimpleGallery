package com.simplemobiletools.gallery.pro.helpers

/**
 * Tap zones for the fullscreen viewer.
 *
 * The geometry is copied from Mihon (the Tachiyomi successor) so the muscle memory
 * carries over: every zone is a normalized rectangle, (0,0) is the top left corner
 * and (1,1) the bottom right one. Zones never overlap, and together they cover the
 * whole screen, so a tap always resolves to something.
 */
data class TapZone(val left: Float, val top: Float, val right: Float, val bottom: Float, val action: Int) {
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom

    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

object TapZones {
    /** All zones of the given layout, already mirrored when [invert] is set. */
    fun zonesOf(mode: Int, invert: Int = TAP_ZONE_INVERT_NONE): List<TapZone> {
        val zones = baseZonesOf(mode)
        if (invert == TAP_ZONE_INVERT_NONE) {
            return zones
        }
        return zones.map { it.inverted(invert) }
    }

    /**
     * Which action a tap at ([x], [y]) inside a [width] x [height] area triggers.
     * Illegal sizes (layout not done yet) are treated as a menu tap.
     */
    fun resolve(mode: Int, invert: Int, x: Float, y: Float, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) {
            return TAP_ACTION_MENU
        }
        val normalizedX = x / width
        val normalizedY = y / height
        zonesOf(mode, invert).forEach {
            if (it.contains(normalizedX, normalizedY)) {
                return it.action
            }
        }
        return TAP_ACTION_MENU
    }

    private fun TapZone.inverted(invert: Int): TapZone {
        val horizontal = invert == TAP_ZONE_INVERT_HORIZONTAL || invert == TAP_ZONE_INVERT_BOTH
        val vertical = invert == TAP_ZONE_INVERT_VERTICAL || invert == TAP_ZONE_INVERT_BOTH
        var newLeft = left
        var newTop = top
        var newRight = right
        var newBottom = bottom
        if (horizontal) {
            newLeft = 1f - right
            newRight = 1f - left
        }
        if (vertical) {
            newTop = 1f - bottom
            newBottom = 1f - top
        }
        return TapZone(newLeft, newTop, newRight, newBottom, action)
    }

    private fun baseZonesOf(mode: Int): List<TapZone> {
        val third = 1f / 3f
        val twoThirds = 2f / 3f
        return when (mode) {
            // top bar + middle left third = previous (an L opening to the bottom right),
            // bottom bar + middle right third = next, the very middle third toggles the ui
            TAP_ZONE_L_SHAPE -> listOf(
                TapZone(0f, 0f, 1f, third, TAP_ACTION_PREVIOUS),
                TapZone(0f, third, third, twoThirds, TAP_ACTION_PREVIOUS),
                TapZone(twoThirds, third, 1f, twoThirds, TAP_ACTION_NEXT),
                TapZone(0f, twoThirds, 1f, 1f, TAP_ACTION_NEXT),
                TapZone(third, third, twoThirds, twoThirds, TAP_ACTION_MENU)
            )

            // kindle like: top third toggles the ui, bottom left goes back, the rest goes forward
            TAP_ZONE_KINDLISH -> listOf(
                TapZone(0f, 0f, 1f, third, TAP_ACTION_MENU),
                TapZone(0f, third, third, 1f, TAP_ACTION_PREVIOUS),
                TapZone(third, third, 1f, 1f, TAP_ACTION_NEXT)
            )

            // both side columns go forward, only the bottom middle goes back,
            // so you never have to aim at a specific side while flipping through
            TAP_ZONE_EDGE -> listOf(
                TapZone(0f, 0f, third, 1f, TAP_ACTION_NEXT),
                TapZone(third, twoThirds, twoThirds, 1f, TAP_ACTION_PREVIOUS),
                TapZone(twoThirds, 0f, 1f, 1f, TAP_ACTION_NEXT),
                TapZone(third, 0f, twoThirds, twoThirds, TAP_ACTION_MENU)
            )

            // plain left / right thirds, the middle one toggles the ui
            TAP_ZONE_LEFT_RIGHT -> listOf(
                TapZone(0f, 0f, third, 1f, TAP_ACTION_PREVIOUS),
                TapZone(twoThirds, 0f, 1f, 1f, TAP_ACTION_NEXT),
                TapZone(third, 0f, twoThirds, 1f, TAP_ACTION_MENU)
            )

            else -> listOf(TapZone(0f, 0f, 1f, 1f, TAP_ACTION_NONE))
        }
    }
}
