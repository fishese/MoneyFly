package com.fishese.moneyfly.capture

import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

enum class CaptureProblem { ACCESS_DENIED, DISCONNECTED, INTAKE_BUSY, CAPTURE_FAILED, SETTINGS_UNAVAILABLE }

data class CaptureConnection(
    val accessGranted: Boolean = false,
    val connected: Boolean = false,
    val refreshing: Boolean = false,
    val lastRefresh: Instant? = null,
    val problem: CaptureProblem? = null,
)

interface CaptureController {
    val connection: StateFlow<CaptureConnection>
    fun onForeground()
    fun openAccessSettings()
    fun requestReconnect()
    suspend fun refresh(): Boolean
    fun sourceName(packageName: String): String = packageName
}
