package com.akdevelopers.auracast.audio

import android.media.MediaRecorder

/**
 * Audio quality presets.
 *  LOW / MEDIUM / HIGH / HD — manual selection from the UI or server.
 *  CALL — automatically applied when a phone call is detected; uses
 *          VOICE_COMMUNICATION source with AEC/NS enabled.
 */
enum class AudioQualityPreset(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    HD("HD"),
    CALL("Call"),
}

data class AudioQualityConfig(
    val preset:          AudioQualityPreset,
    val sampleRate:      Int,
    val frameMs:         Int,
    val enableAgc:       Boolean,
    val enableNs:        Boolean,
    val enableAec:       Boolean,
    val silenceGateRms:  Double,
    val opusBitrate:     Int,
    val opusComplexity:  Int = 9,
    /** Android AudioSource constant — defaults to MIC; CALL preset uses VOICE_COMMUNICATION. */
    val audioSource:     Int = MediaRecorder.AudioSource.MIC,
) {
    val frameSamples: Int get() = sampleRate * frameMs / 1000
    val frameBytes:   Int get() = frameSamples * 2

    companion object {

        /** 16 kbps · 48 kHz — minimal bandwidth, voice intelligible. Good for poor connections. */
        val LOW = AudioQualityConfig(
            preset = AudioQualityPreset.LOW, sampleRate = 48_000, frameMs = 20,
            enableAgc = true, enableNs = true, enableAec = true,
            silenceGateRms = 0.0, opusBitrate = 16_000, opusComplexity = 5,
        )

        /** 64 kbps · 48 kHz — balanced quality/bandwidth for typical home networks. */
        val MEDIUM = AudioQualityConfig(
            preset = AudioQualityPreset.MEDIUM, sampleRate = 48_000, frameMs = 40,
            enableAgc = false, enableNs = false, enableAec = false,
            silenceGateRms = 0.0, opusBitrate = 64_000, opusComplexity = 7,
        )

        /** 128 kbps · 48 kHz — perceptually transparent for most content. */
        val HIGH = AudioQualityConfig(
            preset = AudioQualityPreset.HIGH, sampleRate = 48_000, frameMs = 40,
            enableAgc = false, enableNs = false, enableAec = false,
            silenceGateRms = 0.0, opusBitrate = 128_000, opusComplexity = 9,
        )

        /**
         * 192 kbps · 48 kHz · complexity 10 — maximum quality.
         * Raw ADC samples; no DSP processing. Signal chain: mic → PCM → Opus → network.
         */
        val HD = AudioQualityConfig(
            preset = AudioQualityPreset.HD, sampleRate = 48_000, frameMs = 60,
            enableAgc = false, enableNs = false, enableAec = false,
            silenceGateRms = 0.0, opusBitrate = 192_000, opusComplexity = 10,
        )

        /**
         * 32 kbps · 48 kHz — auto-applied during phone calls.
         * Uses VOICE_COMMUNICATION audio source so Android routes the signal
         * through its VoIP audio path (echo cancellation, noise suppression).
         * NOTE: Only captures the outgoing mic — incoming call audio requires
         * CAPTURE_AUDIO_OUTPUT (system privilege) and is not available here.
         */
        val CALL = AudioQualityConfig(
            preset = AudioQualityPreset.CALL, sampleRate = 48_000, frameMs = 20,
            enableAgc = true, enableNs = true, enableAec = true,
            silenceGateRms = 0.0, opusBitrate = 32_000, opusComplexity = 7,
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )

        /** Kept for backward-compat — same as HD. */
        val HIGH_QUALITY = HD

        /**
         * Build a config from server-pushed parameters (set_quality command).
         * sampleRate is always fixed at 48 000 Hz — server-provided values are ignored.
         * All DSP (AGC/NS/AEC) is off for server-driven configs to preserve raw audio.
         */
        fun fromServerConfig(
            bitrate: Int, sampleRate: Int = 48_000, frameMs: Int, complexity: Int,
        ) = AudioQualityConfig(
            preset = AudioQualityPreset.HD,
            sampleRate = 48_000, // locked — never changes regardless of server request
            frameMs = frameMs,
            enableAgc = false, enableNs = false, enableAec = false,
            silenceGateRms = 0.0, opusBitrate = bitrate, opusComplexity = complexity,
        )

        fun fromPreset(preset: AudioQualityPreset) = when (preset) {
            AudioQualityPreset.LOW    -> LOW
            AudioQualityPreset.MEDIUM -> MEDIUM
            AudioQualityPreset.HIGH   -> HIGH
            AudioQualityPreset.HD     -> HD
            AudioQualityPreset.CALL   -> CALL
        }
    }
}
