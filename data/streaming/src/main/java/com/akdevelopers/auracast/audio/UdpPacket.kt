package com.akdevelopers.auracast.audio

/**
 * UdpPacket — pure encoder/decoder for the AuraCast UDP audio datagram format.
 *
 * Wire layout (12-byte header + variable Opus payload):
 *
 *  Offset  Len  Field
 *  ------  ---  -----
 *       0    1  version  — 0x01 (increment on breaking protocol change)
 *       1    1  flags    — 0x00 normal | 0x01 keyframe | 0x02 last-fragment
 *       2    2  sequence — wrapping uint16 big-endian, monotone per session
 *       4    8  token8   — first 8 bytes of the 16-byte session token (hex decoded)
 *      12    N  payload  — raw Opus frame bytes
 *
 * No I/O — unit-testable in isolation.
 */
object UdpPacket {

    const val VERSION: Byte = 0x01
    const val FLAG_NORMAL: Byte = 0x00
    const val FLAG_KEYFRAME: Byte = 0x01
    const val FLAG_LAST_FRAGMENT: Byte = 0x02
    const val HEADER_SIZE = 12

    /**
     * Encode a datagram ready for dispatch via [java.nio.channels.DatagramChannel].
     *
     * @param seq     wrapping 16-bit sequence counter (caller manages increment)
     * @param token8  exactly 8 bytes — the first 8 bytes of the decoded session token
     * @param payload raw Opus frame bytes
     * @param flags   [FLAG_NORMAL], [FLAG_KEYFRAME], or [FLAG_LAST_FRAGMENT]
     */
    fun encode(
        seq: UShort,
        token8: ByteArray,
        payload: ByteArray,
        flags: Byte = FLAG_NORMAL,
    ): ByteArray {
        require(token8.size == 8) { "token8 must be exactly 8 bytes, got ${token8.size}" }
        val buf = ByteArray(HEADER_SIZE + payload.size)
        buf[0] = VERSION
        buf[1] = flags
        buf[2] = (seq.toInt() shr 8).toByte()
        buf[3] = seq.toByte()
        token8.copyInto(buf, destinationOffset = 4)
        payload.copyInto(buf, destinationOffset = HEADER_SIZE)
        return buf
    }

    data class Parsed(
        val version: Byte,
        val flags: Byte,
        val seq: UShort,
        val token8: ByteArray,
        val payload: ByteArray,
    )

    /**
     * Decode a raw UDP datagram buffer.
     *
     * @return [Parsed] on success, or null if the buffer is too short or version is unrecognised.
     */
    fun parse(buf: ByteArray): Parsed? {
        if (buf.size < HEADER_SIZE) return null
        val version = buf[0]
        if (version != VERSION) return null
        val flags = buf[1]
        val seq = ((buf[2].toInt() and 0xFF shl 8) or (buf[3].toInt() and 0xFF)).toUShort()
        val token8 = buf.copyOfRange(4, 12)
        val payload = buf.copyOfRange(HEADER_SIZE, buf.size)
        return Parsed(version, flags, seq, token8, payload)
    }

    /**
     * Decode the first 16 hex characters of a 32-char session token string into 8 bytes.
     * e.g. "a1b2c3d4e5f60708..." → byteArrayOf(0xa1, 0xb2, 0xc3, ...)
     */
    fun tokenStringToBytes8(hexToken: String): ByteArray {
        require(hexToken.length >= 16) { "token must be at least 16 hex chars" }
        return hexToken.substring(0, 16)
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
