package app.odicto.mobile

import app.odicto.mobile.overlay.OverlayGeometry
import app.odicto.mobile.overlay.OverlayGeometry.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    private val screen = 411f
    private val reach = OverlayGeometry.OPTIONS_REACH_DP

    @Test fun controlsOpenTowardTheSideThatHasRoom() {
        assertEquals(Side.RIGHT, OverlayGeometry.chooseSide(16f, screen, reach, preferRight = false))
        assertEquals(Side.LEFT, OverlayGeometry.chooseSide(347f, screen, reach, preferRight = true))
    }

    @Test fun whenBothSidesFitTheCurrentSideStaysSoNothingFlips() {
        val wide = 1200f
        assertEquals(Side.LEFT, OverlayGeometry.chooseSide(600f, wide, reach, preferRight = false))
        assertEquals(Side.RIGHT, OverlayGeometry.chooseSide(600f, wide, reach, preferRight = true))
    }

    @Test fun inTheMiddleOfAPhoneNeitherSideFitsTheFullOptionsRow() {
        for (micLeft in listOf(120f, 160f, 200f, 230f)) {
            assertNull("mic at ${micLeft}dp", OverlayGeometry.chooseSide(micLeft, screen, reach, preferRight = false))
        }
    }

    @Test fun theRoomierSideWinsWhenNeitherFits() {
        assertEquals(Side.RIGHT, OverlayGeometry.roomierSide(100f, screen))
        assertEquals(Side.LEFT, OverlayGeometry.roomierSide(250f, screen))
    }

    @Test fun aCardNeverWiderThanTheScreenAllowsAndNeverTiny() {
        assertEquals(288, OverlayGeometry.cardWidth(288, 411f))
        assertEquals(264, OverlayGeometry.cardWidth(288, 280f))
        assertEquals(96, OverlayGeometry.cardWidth(288, 100f))
    }

    @Test fun aCardOrStackedRowSlidesToStayOnScreenAndNeverMovesTheMicrophone() {
        val width = 288
        for (side in Side.values()) {
            for (micLeft in listOf(8f, 60f, 150f, 250f, 347f)) {
                val left = OverlayGeometry.slideOnScreen(side, micLeft, screen, width)
                val onScreenLeft = micLeft + left
                assertTrue("$side mic=$micLeft left edge on screen", onScreenLeft >= OverlayGeometry.SCREEN_MARGIN_DP - 0.5f)
                assertTrue("$side mic=$micLeft right edge on screen", onScreenLeft + width <= screen - OverlayGeometry.SCREEN_MARGIN_DP + 0.5f)
            }
        }
    }

    @Test fun besideTheMicrophoneTheCardLinesUpWithItsOuterEdge() {
        assertEquals(OverlayGeometry.MIC_DP - 288, OverlayGeometry.slideOnScreen(Side.LEFT, 347f, screen, 288))
        assertEquals(0, OverlayGeometry.slideOnScreen(Side.RIGHT, 20f, screen, 288))
        // Above the microphone the row grows rightward from the mic's left edge when it fits, else leftward from its right edge.
        assertEquals(0, OverlayGeometry.slideOnScreen(Side.ABOVE, 20f, screen, 232))
        assertEquals(OverlayGeometry.MIC_DP - 232, OverlayGeometry.slideOnScreen(Side.ABOVE, 300f, screen, 232))
    }

    @Test fun mirroringAReachesPiecesAcrossTheMicrophone() {
        // The options pill ends exactly at the microphone's far edge, so mirrored it starts exactly at the microphone's near
        // edge: both ends are concentric with the disc, with nothing sticking out past it.
        val width = OverlayGeometry.OPTIONS_REACH_DP + OverlayGeometry.MIC_DP
        assertEquals(OverlayGeometry.MIC_DP, -OverlayGeometry.OPTIONS_REACH_DP + width)
        val x = OverlayGeometry.mirror(-OverlayGeometry.OPTIONS_REACH_DP, width)
        assertEquals(0, x)
        assertEquals(width, x + width)
    }

    @Test fun aBareMicrophoneIsTheCollapsedSeventyTwoWindow() {
        val box = OverlayGeometry.bounds(0, OverlayGeometry.MIC_DP)
        assertEquals(72, box.width)
        assertEquals(8, box.micOffset)
    }

    @Test fun theWindowWrapsEverythingWithTheMicrophoneInsideItAtAKnownOffset() {
        val box = OverlayGeometry.bounds(-OverlayGeometry.OPTIONS_REACH_DP, OverlayGeometry.MIC_DP)
        assertEquals(OverlayGeometry.OPTIONS_REACH_DP + OverlayGeometry.MIC_DP + 16, box.width)
        assertEquals(OverlayGeometry.OPTIONS_REACH_DP + 8, box.micOffset)
        val stacked = OverlayGeometry.bounds(-100, 132)
        assertEquals(232 + 16, stacked.width)
        assertEquals(108, stacked.micOffset)
        assertNotNull(stacked)
    }

    @Test fun stackedRowWidthGrowsByOneButtonSlot() {
        assertEquals(232, OverlayGeometry.stackedRowWidth(5))
        assertEquals(276, OverlayGeometry.stackedRowWidth(6))
    }

    @Test fun aSavedPlaceComesBackToTheExactPixel() {
        for ((first, last) in listOf(0 to 1000, 24 to 1056, 0 to 2200, 0 to 3000, 130 to 1930)) {
            for (pixel in first..last) {
                val share = OverlayGeometry.shareOf(pixel, first, last)
                assertEquals(" in ..", pixel, OverlayGeometry.valueAt(share, first, last))
            }
        }
    }

    @Test fun aSavedPlaceStaysProportionalWhenTheRoomChanges() {
        val share = OverlayGeometry.shareOf(500, 0, 1000)
        assertEquals(1000, OverlayGeometry.valueAt(share, 0, 2000))
        assertEquals(OverlayGeometry.ANCHOR_SCALE / 2, OverlayGeometry.shareOf(7, 10, 10))
    }
}
class OptionsPillGeometryTest {
    @Test fun thePillIsAsTallAsTheMicrophoneSoItsEndIsAHalfCircleAroundTheDisc() {
        assertEquals(OverlayGeometry.MIC_DP, OverlayGeometry.OPTIONS_HEIGHT_DP)
    }

    @Test fun fiveControlsAreCentredInTheVisiblePartOfThePill() {
        val first = -OverlayGeometry.OPTIONS_REACH_DP + (OverlayGeometry.OPTIONS_REACH_DP - (4 * OverlayGeometry.OPTION_PITCH_DP + 40)) / 2
        val lastEnd = first + 4 * OverlayGeometry.OPTION_PITCH_DP + 40
        val before = first - -OverlayGeometry.OPTIONS_REACH_DP
        val after = 0 - lastEnd
        assertEquals("equal space before the first control and between the last and the disc", before, after)
        assertTrue("room to breathe", before >= 8)
    }
}

class AnchoredEdgeTest {
    @Test fun theMicrophoneIsTheSameDistanceFromTheRightEdgeInEveryState() {
        // Whatever is open, the gap between the disc's right edge and the window's right edge is one constant. That is what keeps
        // the disc still while the window grows to the left.
        for ((low, high) in listOf(0 to OverlayGeometry.MIC_DP, -OverlayGeometry.OPTIONS_REACH_DP to OverlayGeometry.MIC_DP, -112 to OverlayGeometry.MIC_DP, -232 to OverlayGeometry.MIC_DP, -(OverlayGeometry.CARD_WIDTH_DP - OverlayGeometry.MIC_DP) to OverlayGeometry.MIC_DP)) {
            val box = OverlayGeometry.bounds(low, high)
            val micRightInWindow = box.micOffset + OverlayGeometry.MIC_DP
            assertEquals("open to $low", OverlayGeometry.INSET_DP, box.width - micRightInWindow)
        }
    }

    @Test fun theMicrophoneIsTheSameDistanceFromTheLeftEdgeInEveryStateWhenOpeningRight() {
        for (high in listOf(OverlayGeometry.MIC_DP, 112 + OverlayGeometry.MIC_DP, OverlayGeometry.OPTIONS_REACH_DP + OverlayGeometry.MIC_DP)) {
            assertEquals(OverlayGeometry.INSET_DP, OverlayGeometry.bounds(0, high).micOffset)
        }
    }
}

class StackedRowAnchorTest {
    private val screen = 411f

    @Test fun theStackedRowGrowsFromOneMicrophoneEdgeSoOneWindowEdgeNeverMoves() {
        for (micLeft in listOf(8f, 60f, 120f, 150f, 171f, 184f, 220f, 300f, 347f)) {
            if (micLeft in 172f..183f) continue // the band where neither side has room: the row slides and may grow both ways
            val left = OverlayGeometry.slideOnScreen(Side.ABOVE, micLeft, screen, 232)
            val box = OverlayGeometry.bounds(minOf(0, left), maxOf(OverlayGeometry.MIC_DP, left + 232))
            val fromLeft = box.micOffset == OverlayGeometry.INSET_DP
            val fromRight = box.width - (box.micOffset + OverlayGeometry.MIC_DP) == OverlayGeometry.INSET_DP
            assertTrue("mic at $micLeft: one window edge keeps a fixed distance from it", fromLeft || fromRight)
        }
    }
}
