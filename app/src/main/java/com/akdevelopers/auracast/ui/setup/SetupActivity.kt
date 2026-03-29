package com.akdevelopers.auracast.ui.setup
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.akdevelopers.auracast.R
import com.akdevelopers.auracast.ui.MainActivity

/**
 * SetupActivity — mandatory one-time setup gate.
 *
 * Blocks entry to the main UI until ALL three steps are confirmed:
 *   Step 1 — Runtime permissions (RECORD_AUDIO, READ_PHONE_STATE, POST_NOTIFICATIONS)
 *   Step 2 — Battery optimisation exemption
 *   Step 3 — OEM Autostart / background launch (skipped on stock Android)
 *
 * No step can be permanently skipped. "Baad mein" on autostart only defers to
 * the NEXT app open — the blocker reappears every launch until confirmed.
 */
class SetupActivity : AppCompatActivity() {

    private enum class Step { PERMISSIONS, BATTERY, AUTOSTART }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private lateinit var tvIcon:   TextView
    private lateinit var tvTitle:  TextView
    private lateinit var tvDesc:   TextView
    private lateinit var btnAction:    TextView
    private lateinit var btnSecondary: TextView
    private lateinit var llWarn:   LinearLayout
    private lateinit var tvWarn:   TextView

    private var currentStep: Step = Step.PERMISSIONS
    /** True while we're waiting for the user to return from OEM settings screen. */
    private var waitingForAutostartReturn = false
    /** True if user already opened OEM settings at least once this session. */
    private var autostartSettingsOpened = false

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            advance()
        } else {
            // Show warning and keep action button to re-request / open app settings
            showWarn(getString(R.string.setup_permission_warning))
            btnAction.setText(R.string.setup_button_request_permissions_again)
            btnAction.setOnClickListener { requestPermissions() }
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.setText(R.string.setup_button_open_app_settings)
            btnSecondary.setOnClickListener { openAppSettings() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        bindViews()

        // Bug 2 fix: use OnBackPressedCallback instead of deprecated onBackPressed()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* block back — setup is mandatory */ }
        })

        // Bug 3 fix: restore state lost on rotation
        savedInstanceState?.let {
            autostartSettingsOpened    = it.getBoolean("autostartOpened", false)
            waitingForAutostartReturn  = it.getBoolean("waitingAutostart", false)
        }

        advance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autostartOpened",   autostartSettingsOpened)
        outState.putBoolean("waitingAutostart",   waitingForAutostartReturn)
    }

    override fun onResume() {
        super.onResume()
        // Returned from OEM autostart settings page
        if (waitingForAutostartReturn) {
            waitingForAutostartReturn = false
            showAutostartReturnDialog()
            return
        }
        // Returned from app settings (permission was manually granted)
        if (currentStep == Step.PERMISSIONS && allPermsGranted()) advance()
        // Returned from battery optimisation screen
        if (currentStep == Step.BATTERY && SetupManager.isBatteryExempt(this)) advance()
    }

    // ── Step advancement ──────────────────────────────────────────────────────

    private fun advance() {
        currentStep = when {
            !allPermsGranted()                     -> Step.PERMISSIONS
            !SetupManager.isBatteryExempt(this)    -> Step.BATTERY
            SetupManager.autostartStepRequired(this) -> Step.AUTOSTART
            else -> { launchMain(); return }
        }
        renderStep(currentStep)
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ── Render each step ──────────────────────────────────────────────────────

    private fun renderStep(step: Step) {
        hideWarn()
        btnSecondary.visibility = View.GONE

        updateDots(step)
        when (step) {
            Step.PERMISSIONS -> renderPermissionsStep()
            Step.BATTERY     -> renderBatteryStep()
            Step.AUTOSTART   -> renderAutostartStep()
        }
    }

    private fun renderPermissionsStep() {
        tvIcon.text  = "🎙️"
        tvTitle.setText(R.string.setup_title_permissions)
        tvDesc.setText(R.string.setup_desc_permissions)
        btnAction.setText(R.string.setup_button_allow_permissions)
        btnAction.setOnClickListener { requestPermissions() }
    }

    private fun renderBatteryStep() {
        tvIcon.text  = "🔋"
        tvTitle.setText(R.string.setup_title_battery)
        tvDesc.setText(R.string.setup_desc_battery)
        btnAction.setText(R.string.setup_button_open_battery_settings)
        btnAction.setOnClickListener {
            SetupManager.requestBatteryExemption(this)
        }
    }

    private fun renderAutostartStep() {
        val oemName = OemAutostartHelper.getOemName()
        tvIcon.text  = "🚀"
        tvTitle.text = getString(R.string.setup_title_autostart, oemName)
        tvDesc.text = getString(R.string.setup_desc_autostart, oemName)

        if (autostartSettingsOpened) {
            // User already opened settings — show confirm + re-open options
            btnAction.setText(R.string.setup_button_autostart_confirm)
            btnAction.setOnClickListener {
                SetupManager.markAutostartConfirmed(this)
                advance()
            }
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.setText(R.string.setup_button_autostart_reopen)
            btnSecondary.setOnClickListener { openAutostartSettings() }
        } else {
            btnAction.setText(R.string.setup_button_autostart_open)
            btnAction.setOnClickListener { openAutostartSettings() }
        }
    }

    // ── Autostart return dialog ───────────────────────────────────────────────

    private fun showAutostartReturnDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_autostart_return_title)
            .setMessage(getString(R.string.setup_autostart_return_message))
            .setCancelable(false)
            .setPositiveButton(R.string.setup_autostart_return_positive) { _, _ ->
                SetupManager.markAutostartConfirmed(this)
                advance()
            }
            .setNegativeButton(R.string.setup_autostart_return_negative) { _, _ ->
                openAutostartSettings()
            }
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openAutostartSettings() {
        autostartSettingsOpened = true
        waitingForAutostartReturn = true
        OemAutostartHelper.openAutostartSettings(this)
        // Refresh button state immediately so user sees confirm/re-open on return
        renderStep(Step.AUTOSTART)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun allPermsGranted(): Boolean {
        val base = hasPerm(Manifest.permission.RECORD_AUDIO) &&
                   hasPerm(Manifest.permission.READ_PHONE_STATE)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPerm(Manifest.permission.POST_NOTIFICATIONS) else true
        return base && notif
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun showWarn(msg: String) {
        llWarn.visibility = View.VISIBLE
        tvWarn.text = msg
    }

    private fun hideWarn() {
        llWarn.visibility = View.GONE
    }

    private fun updateDots(step: Step) {
        val activeColor   = getColor(R.color.accent_purple)
        val inactiveColor = 0xFF2E3251.toInt()
        val doneColor     = getColor(R.color.status_live)
        // Bug 1 fix: use GradientDrawable.setColor() — setBackgroundColor() would replace
        // the circular shape drawable with a flat ColorDrawable, making dots look like squares.
        fun tint(view: View, color: Int) =
            (view.background.mutate() as? GradientDrawable)?.setColor(color)
        tint(dot1, if (step == Step.PERMISSIONS) activeColor else doneColor)
        tint(dot2, when (step) {
            Step.PERMISSIONS -> inactiveColor
            Step.BATTERY     -> activeColor
            Step.AUTOSTART   -> doneColor
        })
        tint(dot3, if (step == Step.AUTOSTART) activeColor else inactiveColor)
    }

    private fun bindViews() {
        dot1          = findViewById(R.id.dot1)
        dot2          = findViewById(R.id.dot2)
        dot3          = findViewById(R.id.dot3)
        tvIcon        = findViewById(R.id.tvStepIcon)
        tvTitle       = findViewById(R.id.tvStepTitle)
        tvDesc        = findViewById(R.id.tvStepDesc)
        btnAction     = findViewById(R.id.btnStepAction)
        btnSecondary  = findViewById(R.id.btnStepSecondary)
        llWarn        = findViewById(R.id.llWarn)
        tvWarn        = findViewById(R.id.tvWarn)
    }
}
