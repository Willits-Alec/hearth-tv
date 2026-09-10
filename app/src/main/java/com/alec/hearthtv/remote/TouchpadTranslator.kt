package com.alec.hearthtv.remote

import kotlin.math.abs

/** What a gesture on the touchpad means. The caller maps these to IRCC keys or Roku ECP keys. */
enum class PadAction { UP, DOWN, LEFT, RIGHT, OK, BACK }

/**
 * Turns finger movement into remote-control actions, so the Navigate card can be a thumb pad instead of arrow
 * buttons (SCOPE.md §9.1). Pure Kotlin: the Compose layer only feeds it coordinates and timestamps.
 *
 * The rules, which are what the tests pin:
 *  - a flick emits one direction per [stepPx] travelled, so a long drag scrolls a long list;
 *  - the first threshold crossing locks the axis, so a sloppy diagonal does not emit both;
 *  - a touch that never travels past [tapSlopPx] is a tap, and a drag is never also a tap;
 *  - one tap is OK and two quick taps are Back, which is why OK waits out [doubleTapWindowMs] before it fires
 *    (Alec's call, 2026-09-10: the delay is worth the gesture).
 */
class TouchpadTranslator(
    private val stepPx: Float,
    private val tapSlopPx: Float = stepPx / 3f,
    private val doubleTapWindowMs: Long = 250,
) {
    private var startX = 0f
    private var startY = 0f
    private var emittedSteps = 0
    private var axis: Axis? = null
    private var travelled = false

    /** An unresolved single tap: OK unless a second tap lands before [deadline]. */
    private var pendingTapDeadline: Long? = null

    private enum class Axis { HORIZONTAL, VERTICAL }

    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        emittedSteps = 0
        axis = null
        travelled = false
    }

    /** Call with the current position; returns the directions crossed since the last call. */
    fun move(x: Float, y: Float): List<PadAction> {
        val dx = x - startX
        val dy = y - startY
        if (abs(dx) > tapSlopPx || abs(dy) > tapSlopPx) travelled = true

        val current = axis ?: when {
            abs(dx) < stepPx && abs(dy) < stepPx -> return emptyList()
            abs(dx) >= abs(dy) -> Axis.HORIZONTAL
            else -> Axis.VERTICAL
        }.also { axis = it }

        val distance = if (current == Axis.HORIZONTAL) dx else dy
        val steps = (distance / stepPx).toInt()
        val newSteps = steps - emittedSteps
        if (newSteps == 0) return emptyList()
        emittedSteps = steps
        val action = when {
            current == Axis.HORIZONTAL && newSteps > 0 -> PadAction.RIGHT
            current == Axis.HORIZONTAL -> PadAction.LEFT
            newSteps > 0 -> PadAction.DOWN
            else -> PadAction.UP
        }
        return List(abs(newSteps)) { action }
    }

    /**
     * Finger lifted. Returns [PadAction.BACK] when this is the second tap of a pair; a lone tap returns nothing
     * yet and becomes OK through [pending] once its window closes.
     */
    fun up(atMs: Long): List<PadAction> {
        if (travelled || axis != null) {
            pendingTapDeadline = null
            return emptyList()
        }
        val open = pendingTapDeadline
        if (open != null && atMs <= open) {
            pendingTapDeadline = null
            return listOf(PadAction.BACK)
        }
        pendingTapDeadline = atMs + doubleTapWindowMs
        return emptyList()
    }

    /** Call on a timer: turns a tap that was not followed by a second one into OK. */
    fun pending(nowMs: Long): List<PadAction> {
        val deadline = pendingTapDeadline ?: return emptyList()
        if (nowMs < deadline) return emptyList()
        pendingTapDeadline = null
        return listOf(PadAction.OK)
    }

    /** True while a tap is still waiting to become OK, so the UI can hold off on anything else. */
    val hasPendingTap: Boolean get() = pendingTapDeadline != null
}
