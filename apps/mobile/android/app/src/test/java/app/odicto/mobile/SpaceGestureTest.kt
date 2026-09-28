package app.odicto.mobile

import app.odicto.mobile.ime.SpaceGesture
import org.junit.Assert.*
import org.junit.Test

class SpaceGestureTest {
    @Test fun activationConsumesOnlyDeadZoneWhenEventsAreBatched() {
        val gesture = SpaceGesture(4, 40, 8)
        gesture.down(0f, 0f)
        assertNull(gesture.move(7f, 0f))
        assertEquals(SpaceGesture.Action.Cursor(1), gesture.move(13f, 0f))
        assertEquals(SpaceGesture.Action.Cursor(1), gesture.move(17f, 0f))
        assertEquals(SpaceGesture.Action.None, gesture.up())
    }

    @Test fun reversalRespondsImmediatelyAndCrossesOrigin() {
        val gesture = SpaceGesture(4, 40, 8)
        gesture.down(0f, 0f)
        gesture.move(8f, 0f)
        assertEquals(SpaceGesture.Action.Cursor(10), gesture.move(48f, 0f))
        assertNull(gesture.move(49f, 0f))
        assertEquals(SpaceGesture.Action.Cursor(-1), gesture.move(45f, 0f))
        assertEquals(SpaceGesture.Action.Cursor(-12), gesture.move(-3f, 0f))
    }

    @Test fun fractionalMotionAccumulatesWithoutVerticalJumps() {
        val gesture = SpaceGesture(4, 40, 8)
        gesture.down(0f, 0f)
        gesture.move(8f, 0f)
        assertNull(gesture.move(9.5f, 100f))
        assertNull(gesture.move(11.5f, -100f))
        assertEquals(SpaceGesture.Action.Cursor(1), gesture.move(12f, 200f))
    }

    @Test fun verticalExcursionCancelsTapWithoutDocumentJump() {
        val gesture = SpaceGesture(4, 40, 8)
        gesture.down(0f, 0f)
        assertNull(gesture.move(1f, 20f))
        assertEquals(SpaceGesture.Action.None, gesture.up())
    }

    @Test fun holdConsumesJitterAndIsIdempotent() {
        val gesture = SpaceGesture(4, 40, 8)
        gesture.down(0f, 0f)
        gesture.move(3f, 0f)
        assertTrue(gesture.hold())
        assertFalse(gesture.hold())
        assertEquals(SpaceGesture.Action.Cursor(1), gesture.move(7f, 0f))
        assertEquals(SpaceGesture.Action.None, gesture.up())
        assertFalse(gesture.hold())
    }

    @Test fun tapCancelAndReuse() {
        val gesture = SpaceGesture(4, 40, 8)
        assertNull(gesture.move(20f, 0f))
        gesture.down(0f, 0f)
        gesture.move(2f, 2f)
        assertEquals(SpaceGesture.Action.Tap, gesture.up())
        gesture.down(0f, 0f)
        gesture.cancel()
        assertEquals(SpaceGesture.Action.None, gesture.up())
    }
}
