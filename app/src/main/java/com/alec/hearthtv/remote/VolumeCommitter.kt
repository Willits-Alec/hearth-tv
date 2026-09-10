package com.alec.hearthtv.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dragging the volume bar produces a value every few milliseconds; the TV and the Sonos want far fewer than that
 * (SCOPE.md §9.2). This sends the newest value, keeps at least [minIntervalMs] between calls, drops everything
 * superseded in between, and always sends the value the finger stopped on. Pinned by VolumeCommitterTest.
 */
class VolumeCommitter(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 180,
    private val send: suspend (Int) -> Unit,
) {
    private var pending: Int? = null
    private var job: Job? = null

    fun submit(level: Int) {
        pending = level
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val next = pending ?: break
                pending = null
                send(next)
                delay(minIntervalMs)
                if (pending == null) break
            }
        }
    }
}
