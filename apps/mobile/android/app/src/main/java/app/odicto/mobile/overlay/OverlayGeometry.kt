package app.odicto.mobile.overlay

/**
 * Where the floating window's pieces go relative to its microphone.
 *
 * The microphone is the one thing the user placed, so it never moves for anything else: the window is
 * sized around it, never the other way round. Controls open beside it, toward the side with room, and
 * when neither side has room they stack on a row above it. The message card is independent of the
 * microphone horizontally and slides to stay on screen. All values are dp, relative to the left edge of
 * the microphone unless a name says "screen".
 */
internal object OverlayGeometry {
    const val MIC_DP = 56
    /** Space kept around the microphone when nothing else is open. */
    const val INSET_DP = 8
    /** Space kept around the whole group once something is open. */
    const val GROUP_MARGIN_DP = 4
    const val SCREEN_MARGIN_DP = 8f
    /** The full options pill, with the mic inside it, reaches this far left of the mic. */
    const val OPTIONS_REACH_DP = 236
    const val CARD_WIDTH_DP = 288
    /** One button slot in a stacked row: a 40dp button on a 44dp pitch. */
    const val BUTTON_PITCH_DP = 44
    const val STACKED_ROW_DP = 64

    enum class Side { LEFT, RIGHT, ABOVE }

    /** Mirrors a left-side frame x (with width [w]) to the right of the microphone. */
    fun mirror(x: Int, w: Int): Int = MIC_DP - x - w

    /**
     * Which side controls reaching [reachDp] left of the microphone open toward, or null when neither
     * side has room. The current side is kept whenever it still fits so nothing flips needlessly.
     */
    fun chooseSide(micLeftDp: Float, screenWidthDp: Float, reachDp: Int, preferRight: Boolean): Side? {
        val fitsLeft = micLeftDp - reachDp >= SCREEN_MARGIN_DP
        val fitsRight = micLeftDp + MIC_DP + reachDp <= screenWidthDp - SCREEN_MARGIN_DP
        return when {
            preferRight && fitsRight -> Side.RIGHT
            !preferRight && fitsLeft -> Side.LEFT
            fitsLeft -> Side.LEFT
            fitsRight -> Side.RIGHT
            else -> null
        }
    }

    fun roomierSide(micLeftDp: Float, screenWidthDp: Float): Side =
        if (micLeftDp - SCREEN_MARGIN_DP >= screenWidthDp - SCREEN_MARGIN_DP - micLeftDp - MIC_DP) Side.LEFT else Side.RIGHT

    /** Width of the message card on a screen that may be narrower than the card. */
    fun cardWidth(wantedDp: Int, screenWidthDp: Float): Int =
        minOf(wantedDp.toFloat(), screenWidthDp - 2 * SCREEN_MARGIN_DP).toInt().coerceAtLeast(96)

    /**
     * Left edge of a card or stacked row of [widthDp]. Beside the microphone it lines up with the
     * microphone's outer edge; above it, it is centred. Either way it is then slid to stay on screen.
     */
    fun slideOnScreen(side: Side, micLeftDp: Float, screenWidthDp: Float, widthDp: Int): Int {
        val wanted = when (side) {
            Side.LEFT -> MIC_DP - widthDp
            Side.RIGHT -> 0
            Side.ABOVE -> MIC_DP / 2 - widthDp / 2
        }
        val minScreen = SCREEN_MARGIN_DP
        val maxScreen = (screenWidthDp - widthDp - SCREEN_MARGIN_DP).coerceAtLeast(minScreen)
        val screenLeft = (micLeftDp + wanted).coerceIn(minScreen, maxScreen)
        return Math.round(screenLeft - micLeftDp)
    }

    /** Width of a stacked options row holding [count] buttons on a [pitch]dp pitch. */
    fun stackedRowWidth(count: Int, pitch: Int = BUTTON_PITCH_DP): Int = pitch * count + 12

    /**
     * The button pitch for a stacked row, tightened on a very narrow screen so the row still fits. A button is
     * 40dp wide, so the pitch never goes below that.
     */
    fun stackedPitch(count: Int, screenWidthDp: Float): Int =
        minOf(BUTTON_PITCH_DP, ((screenWidthDp - 2 * SCREEN_MARGIN_DP - 12) / count).toInt()).coerceAtLeast(40)

    /** The window that wraps the microphone and everything placed with it. */
    data class Bounds(val width: Int, val micOffset: Int)

    /** [minX]..[maxX] are the leftmost and rightmost edges of all placed pieces, microphone included. */
    fun bounds(minX: Int, maxX: Int): Bounds {
        val low = minOf(0, minX)
        val high = maxOf(MIC_DP, maxX)
        val margin = if (low < 0 || high > MIC_DP) GROUP_MARGIN_DP else INSET_DP
        return Bounds(width = high - low + 2 * margin, micOffset = margin - low)
    }

    /** A placement is saved as a share of the room it moves in, in millionths, so no pixel is lost to rounding. */
    const val ANCHOR_SCALE = 1_000_000

    fun shareOf(value: Int, first: Int, last: Int): Int =
        if (last <= first) ANCHOR_SCALE / 2 else ((value - first) * ANCHOR_SCALE.toLong() / (last - first)).toInt().coerceIn(0, ANCHOR_SCALE)

    /** Rounds to the nearest pixel, so a saved place comes back to the exact pixel it was saved from. */
    fun valueAt(share: Int, first: Int, last: Int): Int =
        first + (((last - first) * share.toLong() + ANCHOR_SCALE / 2) / ANCHOR_SCALE).toInt()
}