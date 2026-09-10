package com.alec.hearthtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alec.hearthtv.remote.PadAction
import com.alec.hearthtv.remote.TouchpadTranslator
import kotlinx.coroutines.delay

/**
 * The thumb pad that replaces the arrow buttons when the toggle on the card is on (SCOPE.md §9.1). All the
 * meaning lives in [TouchpadTranslator]; this only feeds it coordinates, ticks on every action it emits, and
 * polls it so a lone tap can become OK once its double-tap window closes.
 */
@Composable
fun Touchpad(onAction: (PadAction) -> Unit, modifier: Modifier = Modifier, height: Int = 168) {
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { 40.dp.toPx() }
    val pad = remember(step) { TouchpadTranslator(stepPx = step) }

    LaunchedEffect(pad) {
        while (true) {
            delay(50)
            pad.pending(System.currentTimeMillis()).forEach { haptic.tick(); onAction(it) }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(SlateLight, RoundedCornerShape(16.dp))
            .border(1.dp, Slate, RoundedCornerShape(16.dp))
            .pointerInput(pad) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    pad.down(first.position.x, first.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == first.id } ?: break
                        if (!change.pressed) break
                        pad.move(change.position.x, change.position.y).forEach { haptic.tick(); onAction(it) }
                        change.consume()
                    }
                    pad.up(System.currentTimeMillis()).forEach { haptic.tick(); onAction(it) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Swipe to move\nTap for OK · double tap for Back",
            color = PaperDim,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
    }
}
