package com.akdevelopers.auracast.domain.streaming

import com.akdevelopers.auracast.service.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object StreamRuntimeStore {
    private val _status = MutableStateFlow(StreamStatus.IDLE)
    val status: StateFlow<StreamStatus> = _status

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun updateStatus(status: StreamStatus) {
        _status.value = status
    }

    fun updateConnection(isConnected: Boolean) {
        _isConnected.value = isConnected
    }

    fun updateRunning(isRunning: Boolean) {
        _isRunning.value = isRunning
    }

    fun reset() {
        _status.value = StreamStatus.IDLE
        _isRunning.value = false
        _isConnected.value = false
    }
}
