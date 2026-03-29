package com.akdevelopers.auracast.audio

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * PhoneCallDetector — listens for phone call state changes and invokes
 * [onCallStateChanged] on the main thread.
 *
 * Uses [TelephonyCallback] on API 31+, deprecated [PhoneStateListener] on older devices.
 * Requires READ_PHONE_STATE permission (declared in AndroidManifest).
 *
 * When IN_CALL is detected, the streaming service switches to
 * [AudioQualityConfig.CALL] which uses VOICE_COMMUNICATION audio source.
 * When IDLE is detected, it reverts to the previously active quality config.
 */
class PhoneCallDetector(private val context: Context) {

    enum class CallState { IDLE, RINGING, IN_CALL }

    /** Invoked on every call state change. Set before calling [start]. */
    var onCallStateChanged: ((CallState) -> Unit)? = null

    private val TAG = "AC_PhoneCall"
    private val tm  = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // API 31+ callback held so we can unregister it later
    private var telephonyCallback: TelephonyCallback? = null

    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    fun start() {
        Log.i(TAG, "start — SDK=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = dispatch(state)
            }
            telephonyCallback = cb
            tm.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun stop() {
        Log.i(TAG, "stop")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            tm.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    private fun dispatch(state: Int) {
        val mapped = when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.IN_CALL
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            else                                -> CallState.IDLE
        }
        Log.d(TAG, "callState=$mapped (raw=$state)")
        onCallStateChanged?.invoke(mapped)
    }

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = dispatch(state)
    }
}
