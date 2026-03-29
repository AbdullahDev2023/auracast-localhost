package com.akdevelopers.auracast.audio

import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus

class OpusEncoderWrapper(
    sampleRate: Int,
    bitrate: Int = 32_000,
    complexity: Int = 9,
) {
    private val codec: Opus? = runCatching {
        val resolvedSampleRate = when (sampleRate) {
            8_000 -> Constants.SampleRate._8000()
            12_000 -> Constants.SampleRate._12000()
            16_000 -> Constants.SampleRate._16000()
            24_000 -> Constants.SampleRate._24000()
            else -> Constants.SampleRate._48000()
        }
        Opus().also { encoder ->
            encoder.encoderInit(resolvedSampleRate, Constants.Channels.mono(), Constants.Application.audio())
            encoder.encoderSetBitrate(Constants.Bitrate.instance(bitrate))
            encoder.encoderSetComplexity(Constants.Complexity.instance(complexity))
        }
    }.getOrNull()

    private var frameSize: Constants.FrameSize = Constants.FrameSize._320()

    fun updateFrameSize(samples: Int) {
        frameSize = when {
            samples <= 120 -> Constants.FrameSize._120()
            samples <= 240 -> Constants.FrameSize._240()
            samples <= 320 -> Constants.FrameSize._320()
            samples <= 480 -> Constants.FrameSize._480()
            samples <= 960 -> Constants.FrameSize._960()
            samples <= 1920 -> Constants.FrameSize._1920()
            else -> Constants.FrameSize._2880()
        }
    }

    /**
     * Hot-swap the Opus encoding bitrate without recreating the encoder.
     * Safe to call from any thread while encoding is in progress.
     */
    fun setBitrate(bps: Int) {
        runCatching { codec?.encoderSetBitrate(Constants.Bitrate.instance(bps)) }
    }

    fun encode(pcmBytes: ByteArray): ByteArray? =
        runCatching { codec?.encode(pcmBytes, frameSize) }.getOrNull()

    fun release() {
        runCatching { codec?.encoderRelease() }
    }
}
