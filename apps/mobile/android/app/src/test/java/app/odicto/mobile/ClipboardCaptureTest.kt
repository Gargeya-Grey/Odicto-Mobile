package app.odicto.mobile

import app.odicto.mobile.ime.CapturedClipboard
import app.odicto.mobile.ime.ClipboardCaptureGate
import app.odicto.mobile.ime.ClipboardSource
import org.junit.Assert.*
import org.junit.Test

class ClipboardCaptureTest {
    private class Source : ClipboardSource {
        var defaultIme = true
        var sensitive = false
        var reads = 0
        var adds = 0
        var removes = 0
        var token = "copy-1"
        var callback: (() -> Unit)? = null
        override fun isDefaultIme() = defaultIme
        override fun isSensitive() = sensitive
        override fun readPlainText(): CapturedClipboard {
            reads++
            return CapturedClipboard(listOf(" full\ntext "), token)
        }
        override fun addListener(listener: () -> Unit) { adds++; callback = listener }
        override fun removeListener(listener: () -> Unit) { removes++; callback = null }
    }

    @Test fun protectedDisabledAndNonDefaultContextsNeverReadPayload() {
        val source = Source()
        val captured = mutableListOf<CapturedClipboard>()
        val gate = ClipboardCaptureGate(source) { captured += it }
        gate.update(enabled = true, editorEligible = false)
        gate.update(enabled = false, editorEligible = true)
        source.defaultIme = false
        gate.update(enabled = true, editorEligible = true)
        assertEquals(0, source.reads)
        assertEquals(0, source.adds)
        assertTrue(captured.isEmpty())
    }

    @Test fun sensitiveMetadataIsCheckedBeforeAnyPayloadRead() {
        val source = Source().apply { sensitive = true }
        val gate = ClipboardCaptureGate(source) { fail("Sensitive capture") }
        gate.update(true, true)
        source.callback!!.invoke()
        assertEquals(0, source.reads)
        gate.close()
        assertEquals(1, source.removes)
    }

    @Test fun oneListenerCapturesWhilePanelClosedAndRechecksDefaultOnEveryEvent() {
        val source = Source()
        val captured = mutableListOf<CapturedClipboard>()
        val gate = ClipboardCaptureGate(source) { captured += it }
        gate.update(true, true)
        gate.update(true, true)
        assertEquals(1, source.adds)
        assertEquals(1, source.reads)
        source.token = "copy-2"
        source.callback!!.invoke()
        assertEquals(listOf("copy-1", "copy-2"), captured.map { it.token })
        assertFalse(captured.first().isChange)
        assertTrue(captured.last().isChange)
        source.defaultIme = false
        source.callback!!.invoke()
        assertEquals(2, source.reads)
        assertEquals(1, source.removes)
        source.defaultIme = true
        gate.update(true, true)
        assertEquals(2, source.adds)
        gate.update(true, false)
        assertEquals(2, source.removes)
        gate.close()
        gate.update(true, true)
        assertEquals(2, source.adds)
    }
}
