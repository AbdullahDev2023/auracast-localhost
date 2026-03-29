package com.akdevelopers.auracast.ui
import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.akdevelopers.auracast.R
import com.akdevelopers.auracast.analytics.Analytics
import com.akdevelopers.auracast.domain.streaming.StreamRuntimeStore
import com.akdevelopers.auracast.service.StreamIdentity
import com.akdevelopers.auracast.service.StreamStatus
import com.akdevelopers.auracast.ui.setup.SetupActivity
import com.akdevelopers.auracast.ui.setup.SetupManager

/**
 * MainActivity — pure streaming UI.
 * Setup (permissions, battery, autostart) is handled by SetupActivity.
 * If setup is somehow incomplete on arrival, we bounce back to SetupActivity.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var cardBtn:    CardView
    private lateinit var btnStream:  FrameLayout
    private lateinit var tvLabel:    TextView
    private lateinit var tvBadge:    TextView
    private lateinit var tvStreamId: TextView
    private lateinit var viewPulse:  View

    private var pulseAnimator: AnimatorSet? = null

    // ── MediaProjection launcher (one-time earpiece-capture permission) ────────

    /**
     * Launched once per install when the user has not yet seen the MediaProjection
     * rationale.  Result is stored in [MediaProjectionStore] so
     * [DefaultStreamOrchestrator] can pick it up on the next call.
     */
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            SetupManager.markMediaProjectionPrompted(this)
            if (result.resultCode == RESULT_OK && result.data != null) {
                // Android 14+: the FGS must call startForeground(MEDIA_PROJECTION) BEFORE
                // getMediaProjection() is invoked. Delegate to the service so it can do both
                // in the correct order.
                val intent = Intent(this, com.akdevelopers.auracast.service.StreamingService::class.java).apply {
                    action = com.akdevelopers.auracast.service.StreamingService.ACTION_SET_MEDIA_PROJECTION
                    putExtra(com.akdevelopers.auracast.service.StreamingService.EXTRA_MP_RESULT_CODE, result.resultCode)
                    putExtra(com.akdevelopers.auracast.service.StreamingService.EXTRA_MP_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
            }
            // RESULT_CANCELED → no token stored → orchestrator falls back to mic-only
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bug 4 fix: guard checks permissions too — mic revocation after setup must redirect
        if (!allPermsGranted() || SetupManager.autostartStepRequired(this) ||
            !SetupManager.isBatteryExempt(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish(); return
        }

        setContentView(R.layout.activity_main)
        Analytics.logAppOpen(alreadyRunning = StreamRuntimeStore.isRunning.value)

        cardBtn    = findViewById(R.id.cardStreamBtn)
        btnStream  = findViewById(R.id.btnStream)
        tvLabel    = findViewById(R.id.tvStreamLabel)
        tvBadge    = findViewById(R.id.tvStatusBadge)
        tvStreamId = findViewById(R.id.tvStreamId)
        viewPulse  = findViewById(R.id.viewPulseRing)

        tvStreamId.text = getString(R.string.main_stream_id_format, StreamIdentity.getStreamId(this))
        btnStream.setOnClickListener { onStreamButtonClicked() }

        lifecycleScope.launch {
            StreamRuntimeStore.status.collect { applyStatus(it) }
        }

        // Auto-connect on first open
        if (!StreamRuntimeStore.isRunning.value) {
            viewModel.fetchUrlAndAutoConnect(this)
        }

        // One-time MediaProjection rationale prompt (for dual-sided call capture)
        maybePromptMediaProjection()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
    }

    // ── Button ────────────────────────────────────────────────────────────────

    private fun onStreamButtonClicked() {
        when {
            !StreamRuntimeStore.isRunning.value ->
                viewModel.fetchUrlAndAutoConnect(this)
            StreamRuntimeStore.status.value == StreamStatus.STREAMING ->
                viewModel.stopMic(this)
            StreamRuntimeStore.status.value == StreamStatus.CONNECTED_IDLE ->
                viewModel.startMic(this)
            else ->
                viewModel.killService(this)
        }
    }

    // ── Status → UI ───────────────────────────────────────────────────────────

    private fun applyStatus(status: StreamStatus) {
        when (status) {
            StreamStatus.STREAMING -> {
                cardBtn.setCardBackgroundColor(getColor(R.color.status_live))
                tvLabel.setText(R.string.main_label_streaming_live)
                tvLabel.setTextColor(getColor(R.color.status_live))
                tvBadge.setText(R.string.main_status_live)
                tvBadge.setTextColor(getColor(R.color.status_live))
                startPulse()
            }
            StreamStatus.CONNECTING, StreamStatus.RECONNECTING -> {
                cardBtn.setCardBackgroundColor(getColor(R.color.status_connecting))
                tvLabel.setText(
                    if (status == StreamStatus.CONNECTING) {
                        R.string.main_label_connecting
                    } else {
                        R.string.main_label_reconnecting
                    }
                )
                tvLabel.setTextColor(getColor(R.color.status_connecting))
                tvBadge.setText(
                    if (status == StreamStatus.CONNECTING) {
                        R.string.main_status_connecting
                    } else {
                        R.string.main_status_reconnecting
                    }
                )
                tvBadge.setTextColor(getColor(R.color.status_connecting))
                stopPulse()
            }
            StreamStatus.CONNECTED_IDLE -> {
                cardBtn.setCardBackgroundColor(getColor(R.color.accent_purple))
                tvLabel.setText(R.string.main_label_tap_to_stream)
                tvLabel.setTextColor(getColor(R.color.text_secondary))
                tvBadge.setText(R.string.main_status_connected_idle)
                tvBadge.setTextColor(getColor(R.color.accent_purple))
                stopPulse()
            }
            StreamStatus.MIC_ERROR -> {
                cardBtn.setCardBackgroundColor(getColor(R.color.status_error))
                tvLabel.setText(R.string.main_label_mic_error)
                tvLabel.setTextColor(getColor(R.color.status_error))
                tvBadge.setText(R.string.main_status_mic_error)
                tvBadge.setTextColor(getColor(R.color.status_error))
                stopPulse()
            }
            StreamStatus.IDLE -> {
                cardBtn.setCardBackgroundColor(getColor(R.color.accent_purple))
                tvLabel.setText(R.string.main_label_tap_to_stream)
                tvLabel.setTextColor(getColor(R.color.text_secondary))
                tvBadge.setText(R.string.main_status_idle)
                tvBadge.setTextColor(getColor(R.color.status_idle))
                stopPulse()
            }
        }
    }

    // ── Pulse animation ───────────────────────────────────────────────────────

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        viewPulse.alpha = 0.45f
        val scaleX = ObjectAnimator.ofFloat(viewPulse, View.SCALE_X, 0.88f, 1.18f)
        val scaleY = ObjectAnimator.ofFloat(viewPulse, View.SCALE_Y, 0.88f, 1.18f)
        val alpha  = ObjectAnimator.ofFloat(viewPulse, View.ALPHA,  0.6f,  0.0f)
        scaleX.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleX.repeatMode  = android.animation.ValueAnimator.REVERSE
        scaleY.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY.repeatMode  = android.animation.ValueAnimator.REVERSE
        alpha.repeatCount  = android.animation.ValueAnimator.INFINITE
        alpha.repeatMode   = android.animation.ValueAnimator.RESTART
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1400; interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimator!!.start()
    }

    private fun stopPulse() {
        pulseAnimator?.cancel(); pulseAnimator = null
        viewPulse.alpha = 0f; viewPulse.scaleX = 1f; viewPulse.scaleY = 1f
    }

    // ── MediaProjection prompt ────────────────────────────────────────────────

    /**
     * Shows a one-time rationale dialog explaining why AuraCast needs the
     * MediaProjection permission.  Only shown if the user has never seen it.
     * After the user chooses Allow or Skip, [SetupManager.markMediaProjectionPrompted]
     * is called so the dialog never appears again.
     */
    private fun maybePromptMediaProjection() {
        if (SetupManager.isMediaProjectionPrompted(this)) return
        AlertDialog.Builder(this)
            .setTitle("Enable two-sided call recording?")
            .setMessage(
                "AuraCast can stream both sides of a phone call by capturing the earpiece audio.\n\n" +
                "This uses Android's screen-capture permission — audio only, no video is ever captured.\n\n" +
                "Tap \"Allow\" on the next screen to enable, or \"Skip\" to use mic-only mode."
            )
            .setPositiveButton("Allow") { _, _ -> launchMediaProjectionPrompt() }
            .setNegativeButton("Skip")  { _, _ -> SetupManager.markMediaProjectionPrompted(this) }
            .setCancelable(false)
            .show()
    }

    private fun launchMediaProjectionPrompt() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun allPermsGranted(): Boolean {
        val base = hasPerm(Manifest.permission.RECORD_AUDIO) &&
                   hasPerm(Manifest.permission.READ_PHONE_STATE)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPerm(Manifest.permission.POST_NOTIFICATIONS) else true
        return base && notif
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
