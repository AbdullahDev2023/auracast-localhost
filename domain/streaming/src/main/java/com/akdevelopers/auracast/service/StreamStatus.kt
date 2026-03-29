package com.akdevelopers.auracast.service
enum class StreamStatus {
    IDLE,            // Service not running
    CONNECTING,      // WebSocket connecting (first attempt)
    CONNECTED_IDLE,  // WebSocket open, mic OFF — phone visible on dashboard, not streaming
    STREAMING,       // WebSocket open, mic ON, frames flowing
    RECONNECTING,    // WebSocket dropped, auto-reconnecting
    MIC_ERROR        // AudioRecord hardware failure
}
