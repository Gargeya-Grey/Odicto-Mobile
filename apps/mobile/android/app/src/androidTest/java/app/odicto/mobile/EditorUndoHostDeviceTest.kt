package app.odicto.mobile

import android.app.Service
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.odicto.mobile.ime.FieldPolishSnapshotReader
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only. The complete generated field is checked before any edit. */
@RunWith(AndroidJUnit4::class)
class EditorUndoHostDeviceTest {
    @Test fun toolbarUndoAlsoRestoresDeletionInANativeEditText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odictoUndoHost") == "generated-trial")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = "alpha bravo café e\u0301lan delta 👩🏽‍💻 東京 omega multiword fixture. ".repeat(160)
        lateinit var ime: OdictoImeService
        instrumentation.runOnMainSync {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
            ime = services.values.filterIsInstance<Service>().filterIsInstance<OdictoImeService>().single()
            assertTrue(ime.currentInputEditorInfo?.packageName == "app.odicto.keyboardtrial")
            assertTrue("Only the generated native fixture may be edited", FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source)
            val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
            val quick = root.findViewById<OdictoKeyboardView>(R.id.ime_quick_keys)
            val listener = OdictoKeyboardView::class.java.getDeclaredField("onKey").apply { isAccessible = true }.get(quick) as OdictoKeyboardView.OnKey
            listener.onKey(OdictoKeyboardView.KeySpec(OdictoKeyboardView.Key.BACKSPACE, "Backspace", "", 1f))
        }
        SystemClock.sleep(150)
        instrumentation.runOnMainSync {
            assertTrue("Backspace removes the final generated space", FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source.dropLast(1))
            val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
            root.findViewById<View>(R.id.ime_undo).performClick()
        }
        SystemClock.sleep(200)
        instrumentation.runOnMainSync {
            val restored = FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source
            instrumentation.sendStatus(0, android.os.Bundle().apply { putBoolean("nativeEditTextUndoRestoredOriginal", restored) })
            assertTrue("Undo restores the generated native field", restored)
        }
    }

    @Test fun hostUndoRestoresTheLastTextEdit() {
        val mode = InstrumentationRegistry.getArguments().getString("odictoUndoHost")
        assumeTrue(mode in listOf("generated-native-probe", "generated-toolbar"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = InstrumentationRegistry.getArguments().getString("undoScenario") ?: "deletion"
        val source = "Odicto undo trial ABC\n"
        lateinit var ime: OdictoImeService
        instrumentation.runOnMainSync {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
            ime = services.values.filterIsInstance<Service>().filterIsInstance<OdictoImeService>().single()
            assertTrue(ime.currentInputEditorInfo?.packageName == "com.samsung.android.app.notes")
            val connection = requireNotNull(ime.currentInputConnection)
            assertTrue("Use only the exact generated note", FieldPolishSnapshotReader.capture(connection)?.text == source)
            assertTrue(if (scenario == "selection") connection.setSelection(source.length - 4, source.length - 1)
                else connection.setSelection(source.length - 1, source.length - 1))
        }
        SystemClock.sleep(100)
        instrumentation.runOnMainSync {
            val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
            val quick = root.findViewById<OdictoKeyboardView>(R.id.ime_quick_keys)
            val listener = OdictoKeyboardView::class.java.getDeclaredField("onKey").apply { isAccessible = true }.get(quick) as OdictoKeyboardView.OnKey
            when (scenario) {
                "typing" -> listener.onKey(OdictoKeyboardView.KeySpec(OdictoKeyboardView.Key.CHARACTER, "D", "D", 1f))
                "paste" -> {
                    val controller = OdictoImeService::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(ime)
                    controller.javaClass.getDeclaredMethod("commit", String::class.java).apply { isAccessible = true }.invoke(controller, "XYZ")
                }
                else -> listener.onKey(OdictoKeyboardView.KeySpec(OdictoKeyboardView.Key.BACKSPACE, "Backspace", "", 1f))
            }
        }
        SystemClock.sleep(150)
        instrumentation.runOnMainSync {
            val connection = requireNotNull(ime.currentInputConnection)
            val edited = when (scenario) {
                "typing" -> "Odicto undo trial ABCD\n"
                "paste" -> "Odicto undo trial ABCXYZ\n"
                "selection" -> "Odicto undo trial \n"
                else -> "Odicto undo trial AB\n"
            }
            assertTrue("The generated edit must land before Undo", FieldPolishSnapshotReader.capture(connection)?.text == edited)
            val accepted = if (mode == "generated-toolbar") {
                val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
                root.findViewById<View>(R.id.ime_undo).performClick()
            } else if (InstrumentationRegistry.getArguments().getString("undoProtocol") == "shortcut") {
                val time = SystemClock.uptimeMillis()
                val meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
                val down = connection.sendKeyEvent(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_Z, 0, meta))
                val up = connection.sendKeyEvent(android.view.KeyEvent(time, SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_Z, 0, meta))
                down && up
            } else connection.performContextMenuAction(android.R.id.undo)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putBoolean("undoRequestAccepted", accepted); putString("scenario", scenario) })
        }
        SystemClock.sleep(200)
        instrumentation.runOnMainSync {
            val restored = FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source
            instrumentation.sendStatus(0, android.os.Bundle().apply { putBoolean("undoRestoredGeneratedOriginal", restored) })
            assertTrue("Host Undo must restore the exact generated original", restored)
        }
        if (mode == "generated-toolbar") {
            val file = java.io.File(ime.getExternalFilesDir(null), "general-undo-$scenario.png")
            java.io.FileOutputStream(file).use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
