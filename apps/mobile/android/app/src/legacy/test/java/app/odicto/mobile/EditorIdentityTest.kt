package app.odicto.mobile

import app.odicto.mobile.accessibility.EditorIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorIdentityTest {
    @Test fun differentNodesAndWindowsStayDistinct() {
        assertNotEquals(EditorIdentity.key(653, "42", null, null), EditorIdentity.key(653, "43", null, null))
        assertNotEquals(EditorIdentity.key(653, "42", null, null), EditorIdentity.key(654, "42", null, null))
    }

    @Test fun aFieldWithoutAUniqueIdFallsBackToItsResourceNameThenClass() {
        assertEquals("653:com.app:id/field", EditorIdentity.key(653, null, "com.app:id/field", "android.widget.EditText"))
        assertEquals("653:android.widget.EditText", EditorIdentity.key(653, null, null, "android.widget.EditText"))
        assertEquals("653:field", EditorIdentity.key(653, null, null, null))
    }

    @Test fun aMovedFieldKeepsItsIdentity() {
        // Geometry is not an input, so a re-layout cannot key the same node differently: the previous
        // bounds-based key closed the session when the IME shifted the field mid-dictation.
        val node = EditorIdentity.key(653, "42", null, "android.widget.EditText")
        val moved = EditorIdentity.key(653, "42", null, "android.widget.EditText")
        assertEquals(node, moved)
    }
}
