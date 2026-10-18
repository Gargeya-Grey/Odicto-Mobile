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
        assertEquals(OverlayGeometry.MIC_DP / 2 - 144, OverlayGeometry.slideOnScreen(Side.ABOVE, 180f, screen, 288))
    }

    @Test fun mirroringAReachesPiecesAcrossTheMicrophone() {
        // The options pill: 296 wide, starting 236 left of the microphone, ends 4 past its right edge.
        val x = OverlayGeometry.mirror(-236, 296)
        assertEquals(-4, x)
        assertEquals(292, x + 296)
    }

    @Test fun aBareMicrophoneIsTheCollapsedSeventyTwoWindow() {
        val box = OverlayGeometry.bounds(0, OverlayGeometry.MIC_DP)
        assertEquals(72, box.width)
        assertEquals(8, box.micOffset)
    }

    @Test fun theWindowWrapsEverythingWithTheMicrophoneInsideItAtAKnownOffset() {
        val box = OverlayGeometry.bounds(-236, 60)
        assertEquals(304, box.width)
        assertEquals(240, box.micOffset)
        val stacked = OverlayGeometry.bounds(-100, 132)
        assertEquals(232 + 8, stacked.width)
        assertEquals(104, stacked.micOffset)
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