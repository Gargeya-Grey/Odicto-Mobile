package app.odicto.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.ClipboardPanel
import app.odicto.mobile.storage.ClipboardState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardPanelTest {
    @Test fun previewNeverChangesTheFullPayloadAndOpeningOnlyUsesSubmittedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val panel = ClipboardPanel(context)
        val text = " \n" + "x".repeat(900) + "👩🏽‍💻\u001F\n "
        panel.submit(ClipboardState(history = listOf(text, text + "tail")))
        assertEquals(listOf(text, text + "tail"), panel.openForDisplay())
        assertTrue(ClipboardPanel.preview(text).endsWith("…"))
        assertEquals(text, panel.readForDisplay().first())
        panel.close()
        assertFalse(panel.isOpen)
        assertTrue(panel.isEmpty)
    }
}
