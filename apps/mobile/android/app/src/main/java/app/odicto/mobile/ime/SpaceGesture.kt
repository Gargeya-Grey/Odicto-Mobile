package app.odicto.mobile.ime

import kotlin.math.abs

internal class SpaceGesture(
    private val slop: Int,
    private val lineStep: Int,
    private val lockSlop: Int = slop,
) {
    sealed interface Action {
        data object Tap : Action
        data class Cursor(val steps: Int) : Action
        data class Line(val steps: Int) : Action
        data object None : Action
    }

    private var started = false
    var armed = false
        private set
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var remainder = 0f
    private var direction = 0

    fun down(x: Float, y: Float) {
        cancel()
        started = true
        downX = x
        downY = y
        lastX = x
    }

    fun hold(): Boolean {
        if (!started || armed) return false
        armed = true
        remainder = 0f
        return true
    }

    fun move(x: Float, y: Float): Action? {
        if (!started) return null
        var delta = x - lastX
        lastX = x
        if (!armed) {
            if (abs(x - downX) < lockSlop && abs(y - downY) < lockSlop) return null
            if (abs(y - downY) > abs(x - downX)) {
                cancel()
                return null
            }
            hold()
            val travel = x - downX
            delta = travel - if (travel < 0) -lockSlop else lockSlop
        }
        val nextDirection = when { delta > 0 -> 1; delta < 0 -> -1; else -> return null }
        if (direction != 0 && direction != nextDirection) remainder = 0f
        direction = nextDirection
        remainder += delta
        val steps = (remainder / slop.coerceAtLeast(1)).toInt()
        if (steps == 0) return null
        remainder -= steps * slop.coerceAtLeast(1)
        return Action.Cursor(steps)
    }

    fun up(): Action {
        val result = if (started && !armed) Action.Tap else Action.None
        cancel()
        return result
    }

    fun cancel() {
        started = false
        armed = false
        remainder = 0f
        direction = 0
    }
}
