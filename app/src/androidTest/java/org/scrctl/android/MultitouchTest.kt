package org.scrctl.android

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: sends touches through the real mirror View to an already-paired iPhone. */
@RunWith(AndroidJUnit4::class)
class MultitouchTest {
    @Test fun mirrorViewForwardsFiveStableContactsAndCancellation() {
        val arguments = InstrumentationRegistry.getArguments()
        val udid = arguments.getString("xdbHardwareUdid").orEmpty()
        assumeTrue("live touch acceptance requires an explicitly selected device",
            arguments.getString("xdbMultitouch") == "true" && udid.isNotEmpty())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun views(action: (List<View>) -> Unit) { scenario.onActivity { action(descendants(it.findViewById(android.R.id.content))) } }
            fun waitFor(message: String, condition: (List<View>) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 45000
                while (true) {
                    var done = false
                    views { done = condition(it) }
                    if (done) return
                    assertTrue(message, SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(100)
                }
            }
            waitFor("paired iPhone discovery") { list -> list.filterIsInstance<TextView>().any { it.text.contains(udid) && it.isEnabled } }
            views { list -> list.filterIsInstance<TextView>().first { it.text.contains(udid) }.let { (it.parent as View).performClick() } }
            waitFor("enabled Connect button") { list -> list.filterIsInstance<TextView>().any { it.text == "Connect" && it.isEnabled } }
            views { list -> list.filterIsInstance<TextView>().first { it.text == "Connect" }.performClick() }
            waitFor("actual mirrored frame") { list -> list.filterIsInstance<MirrorView>().any { it.isShown && it.width > 0 && it.height > 0 } }
            SystemClock.sleep(1500)
            val pointers = mutableListOf<Int>()
            val coordinates = mutableMapOf<Int, Pair<Float, Float>>()
            val downTime = SystemClock.uptimeMillis()
            fun dispatch(action: Int) {
                views { list ->
                    val mirror = list.filterIsInstance<MirrorView>().single()
                    val props = pointers.map { pointer -> MotionEvent.PointerProperties().apply { id = pointer; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                    val coords = pointers.map { pointer -> MotionEvent.PointerCoords().apply {
                        val position = coordinates.getValue(pointer)
                        x = mirror.width * position.first; y = mirror.height * position.second
                        pressure = 1f; size = .1f
                    } }.toTypedArray()
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointers.size,
                        props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue("mirror accepted touch", mirror.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(180)
            }
            val ids = listOf(27, 8, 19, 3, 12)
            ids.forEachIndexed { i, pointer ->
                pointers.add(pointer); coordinates[pointer] = Pair(.2f + i * .15f, .5f)
                dispatch(if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            }
            ids.forEach { coordinates[it] = coordinates.getValue(it).let { (x, _) -> Pair(x, .55f) } }
            dispatch(MotionEvent.ACTION_MOVE)
            dispatch(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT));pointers.removeAt(2)
            dispatch(MotionEvent.ACTION_MOVE)
            while (pointers.size > 1) { dispatch(MotionEvent.ACTION_POINTER_UP);pointers.removeAt(0) }
            dispatch(MotionEvent.ACTION_UP);pointers.clear()
            pointers.add(25);coordinates[25] = Pair(.35f, .5f);dispatch(MotionEvent.ACTION_DOWN)
            pointers.add(9);coordinates[9] = Pair(.65f, .5f);dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            dispatch(MotionEvent.ACTION_CANCEL)
            SystemClock.sleep(500)
        }
    }
}
