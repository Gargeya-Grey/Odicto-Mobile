package app.odicto.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.AndroidClipboardSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardAndroidSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test fun preservesExactPlainTextWithoutCoercingUriContents() {
        val source = AndroidClipboardSource(context)
        val text = " \n" + "same-prefix".repeat(80) + "\u001F👩🏽‍💻\n "
        manager.setPrimaryClip(ClipData.newPlainText("test", text))
        assertEquals(listOf(text), source.readPlainText()!!.texts)
        manager.setPrimaryClip(ClipData.newRawUri("test", Uri.parse("content://clipboard-test/missing")))
        assertTrue(source.readPlainText()!!.texts.isEmpty())
    }

    @Test fun markedSensitiveClipIsRefusedAndDefaultImeUsesExactComponentIdentity() {
        val source = AndroidClipboardSource(context)
        val clip = ClipData.newPlainText("test", "synthetic-sensitive")
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        manager.setPrimaryClip(clip)
        assertTrue(source.isSensitive())
        assertNull(source.readPlainText())
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            ComponentName(context, "app.odicto.mobile.ime.OdictoImeService").flattenToString())
        assertTrue(source.isDefaultIme())
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            ComponentName(context, "app.odicto.mobile.ime.OtherImeService").flattenToString())
        assertFalse(source.isDefaultIme())
    }
}
