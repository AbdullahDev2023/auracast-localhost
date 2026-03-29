package com.akdevelopers.auracast.system
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executors
import com.akdevelopers.auracast.analytics.Analytics

/**
 * Detects incoming/outgoing phone calls and notifies the service to
 * pause mic capture for the duration of the call, then auto-resume.
 *
 * Uses TelephonyCallback on API 31+, PhoneStateListener on API 26-30.
 */
class PhoneCallMonitor(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded:   () -> Unit
) {
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: Any? = null  // TelephonyCallback (API 31+)

    fun register() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerModern()
            } else {
                registerLegacy()
            }
        } catch (e: SecurityException) {
            // READ_PHONE_STATE not granted — phone call detection disabled, streaming continues normally
        } catch (e: Exception) {
            // Any other failure — non-fatal, ignore
        }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        legacyListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleState(state)
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun registerModern() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            modernCallback = cb
            tm.registerTelephonyCallback(Executors.newSingleThreadExecutor(), cb)
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> { Analytics.logPhoneCallStarted(); onCallStarted() }
            TelephonyManager.CALL_STATE_IDLE     -> { Analytics.logPhoneCallEnded();   onCallEnded()   }
        }
    }

    @Suppress("DEPRECATION")
    fun unregister() {
        legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        legacyListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modernCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
        }
        modernCallback = null
    }
}
