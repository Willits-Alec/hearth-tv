package com.alec.hearthtv.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9.1, written before the touchpad existed: what each gesture means, and nothing more. */
class TouchpadTranslatorTest {
    private fun pad() = TouchpadTranslator(stepPx = 30f, tapSlopPx = 10f, doubleTapWindowMs = 250)

    @Test fun `a flick emits one direction`() {
        val p = pad()
        p.down(100f, 100f)
        assertEquals(emptyList<PadAction>(), p.move(110f, 100f))       // inside the threshold
        assertEquals(listOf(PadAction.RIGHT), p.move(135f, 100f))
        assertEquals(emptyList<PadAction>(), p.up(1000))
    }

    @Test fun `all four directions come out the right way round`() {
        fun flick(dx: Float, dy: Float): List<PadAction> {
            val p = pad()
            p.down(200f, 200f)
            return p.move(200f + dx, 200f + dy)
        }
        assertEquals(listOf(PadAction.RIGHT), flick(40f, 0f))
        assertEquals(listOf(PadAction.LEFT), flick(-40f, 0f))
        assertEquals(listOf(PadAction.DOWN), flick(0f, 40f))
        assertEquals(listOf(PadAction.UP), flick(0f, -40f))
    }

    @Test fun `a long drag keeps emitting, one per step, and never repeats a step`() {
        val p = pad()
        p.down(0f, 0f)
        assertEquals(listOf(PadAction.DOWN), p.move(0f, 35f))
        assertEquals(emptyList<PadAction>(), p.move(0f, 40f))          // same step, nothing new
        assertEquals(listOf(PadAction.DOWN), p.move(0f, 65f))
        assertEquals(listOf(PadAction.DOWN, PadAction.DOWN), p.move(0f, 125f))   // crossed two at once
        assertEquals(emptyList<PadAction>(), p.move(0f, 125f))                   // a still finger emits nothing
        // Emissions track where the finger IS, not how far it has moved since the last call, so the total can
        // never drift from the gesture. Coming back from 125 to 88 crosses two step bands, so two UPs.
        assertEquals(listOf(PadAction.UP, PadAction.UP), p.move(0f, 88f))
    }

    @Test fun `a sloppy diagonal locks to the axis it started on`() {
        val p = pad()
        p.down(0f, 0f)
        assertEquals(listOf(PadAction.RIGHT), p.move(40f, 12f))
        // the finger now wanders down as much as across; the pad stays horizontal
        assertEquals(listOf(PadAction.RIGHT), p.move(75f, 70f))
        assertEquals(emptyList<PadAction>(), p.move(75f, 200f))
    }

    @Test fun `one tap becomes OK, but only once its window has closed`() {
        val p = pad()
        p.down(50f, 50f)
        p.move(53f, 52f)
        assertEquals(emptyList<PadAction>(), p.up(1_000))
        assertTrue(p.hasPendingTap)
        assertEquals(emptyList<PadAction>(), p.pending(1_100))
        assertEquals(listOf(PadAction.OK), p.pending(1_250))
        assertFalse(p.hasPendingTap)
        assertEquals(emptyList<PadAction>(), p.pending(1_400))         // fires once
    }

    @Test fun `two quick taps are Back, and no OK escapes`() {
        val p = pad()
        p.down(50f, 50f)
        assertEquals(emptyList<PadAction>(), p.up(1_000))
        p.down(52f, 51f)
        assertEquals(listOf(PadAction.BACK), p.up(1_150))
        assertFalse(p.hasPendingTap)
        assertEquals(emptyList<PadAction>(), p.pending(2_000))
    }

    @Test fun `a second tap after the window is another OK, not Back`() {
        val p = pad()
        p.down(50f, 50f)
        p.up(1_000)
        assertEquals(listOf(PadAction.OK), p.pending(1_300))
        p.down(50f, 50f)
        assertEquals(emptyList<PadAction>(), p.up(1_400))
        assertEquals(listOf(PadAction.OK), p.pending(1_700))
    }

    @Test fun `a drag is never also a tap`() {
        val p = pad()
        p.down(0f, 0f)
        p.move(90f, 0f)
        assertEquals(emptyList<PadAction>(), p.up(1_000))
        assertFalse(p.hasPendingTap)
        assertEquals(emptyList<PadAction>(), p.pending(9_000))
    }

    @Test fun `a small wobble under the slop still counts as a tap`() {
        val p = pad()
        p.down(0f, 0f)
        p.move(6f, 4f)
        p.move(0f, 0f)
        assertEquals(emptyList<PadAction>(), p.up(500))
        assertEquals(listOf(PadAction.OK), p.pending(800))
    }

    @Test fun `a drag cancels a tap that was still waiting`() {
        val p = pad()
        p.down(0f, 0f)
        p.up(1_000)
        assertTrue(p.hasPendingTap)
        p.down(0f, 0f)
        p.move(0f, 80f)                                                // a drag, not a second tap
        assertEquals(emptyList<PadAction>(), p.up(1_100))
        assertFalse(p.hasPendingTap)
    }
}
