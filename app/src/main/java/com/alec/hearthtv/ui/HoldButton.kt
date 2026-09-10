package com.alec.hearthtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Remote-control feel: one haptic tick per press (SCOPE.md §4.2). */
fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.LongPress)

/**
 * Fires [onPress] on touch-down with a tick, then again every [periodMs] once the finger has stayed down for
 * [delayMs] — the way a real remote repeats Volume and the arrows. Nothing fires on release, so nothing fires twice.
 */
fun Modifier.repeatOnHold(haptic: HapticFeedback?, onPress: () -> Unit, delayMs: Long = 450, periodMs: Long = 170): Modifier =
    pointerInput(onPress) {
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                haptic?.tick()
                onPress()
                val repeater = launch {
                    delay(delayMs)
                    while (true) {
                        haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPress()
                        delay(periodMs)
                    }
                }
                waitForUpOrCancellation()
                repeater.cancel()
            }
        }
    }

/** A big icon key that repeats while held: volume and the D-pad arrows. */
@Composable
fun HoldButton(
    icon: ImageVector,
    description: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = SlateLight,
    content: Color = Paper,
    shape: Shape = RoundedCornerShape(14.dp),
    iconSize: Dp = 30.dp,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .background(container, shape)
            .semantics { role = Role.Button; contentDescription = description }
            .repeatOnHold(haptic, onPress),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(iconSize), tint = content) }
}
