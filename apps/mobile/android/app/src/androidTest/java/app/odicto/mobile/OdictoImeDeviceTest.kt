package app.odicto.mobile

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.odicto.mobile.ime.ClipboardPanel
import app.odicto.mobile.ime.CursorNavigator
import app.odicto.mobile.ime.EditorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editor-facing behaviour that only a real [android.view.inputmethod.InputConnection] can prove.
 *
 * The pure logic is unit tested; this suite covers the parts that depend on the platform: what the
 * policy allows for a real `EditorInfo`, and that the clipboard really is read only when asked.
 */
@RunWith(AndroidJUnit4::class)
class OdictoImeDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun policyDecidesOnRealEditorInfoObjects() {
        val plain = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        assertTrue(EditorPolicy.permits(plain))
        assertTrue(EditorPolicy.manualPermits(plain))
        assertTrue(EditorPolicy.emojiPermits(plain))

        val number = EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }
        assertFalse("numeric fields must refuse dictation", EditorPolicy.permits(number))
        assertTrue("numeric fields must still accept typing", EditorPolicy.manualPermits(number))
        assertFalse(EditorPolicy.emojiPermits(number))

        val phone = EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }
        assertFalse(EditorPolicy.permits(phone))
        assertTrue(EditorPolicy.manualPermits(phone))
        assertFalse(EditorPolicy.clipboardPermits(phone))
    }

    @Test fun aPasswordFieldIsRefusedForEveryRichCapability() {
        val password = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertTrue(EditorPolicy.isPasswordField(password))
        assertFalse(EditorPolicy.permits(password))
        assertFalse(EditorPolicy.emojiPermits(password))
        assertFalse(EditorPolicy.clipboardPermits(password))
    }

    @Test fun aFieldThatRefusesLearningIsRefusedForEveryRichCapability() {
        val protected = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        assertFalse(EditorPolicy.permits(protected))
        assertFalse(EditorPolicy.emojiPermits(protected))
        assertFalse(EditorPolicy.clipboardPermits(protected))
        // Typing is still allowed: the user is not prevented from entering their own words.
        assertTrue(EditorPolicy.manualPermits(protected))
    }

    @Test fun theClipboardIsEmptyUntilItIsExplicitlyOpened() {
        val panel = ClipboardPanel(context)
        // Nothing has been opened, so nothing has been read and nothing is retained.
        assertFalse(panel.isOpen)
        assertTrue(panel.isEmpty)
    }

    @Test fun openingTheClipboardDisplaysSuppliedHistoryAndClosingDropsPanelReferences() {
        val marker = "odicto-clipboard-marker"
        val panel = ClipboardPanel(context)
        panel.submit(app.odicto.mobile.storage.ClipboardState(history = listOf(marker)))
        assertEquals(listOf(marker), panel.openForDisplay())
        panel.close()
        assertFalse(panel.isOpen)
        assertTrue(panel.isEmpty)
    }

    @Test fun aLongClipboardEntryKeepsItsPayloadAndOnlyShortensItsPreview() {
        val text = " \n" + "x".repeat(4000) + "\n "
        val panel = ClipboardPanel(context)
        panel.submit(app.odicto.mobile.storage.ClipboardState(history = listOf(text)))
        assertEquals(listOf(text), panel.openForDisplay())
        assertEquals(65, ClipboardPanel.preview(text, 64).length)
        panel.close()
    }

    @Test fun cursorMovementStaysGraphemeSafe() {
        val text = "a😀b"
        val forward = CursorNavigator.character(text, 1, 1, 1)
        assertEquals(3, forward!!.first)
        val back = CursorNavigator.character(text, 3, 3, -1)
        assertEquals(1, back!!.first)
    }
}
