package com.akdevelopers.auracast.ui.setup
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.akdevelopers.auracast.R
import java.util.Locale

/**
 * AutostartGuideDialog — OEM-specific Hinglish step-by-step guide
 * shown BEFORE opening the manufacturer's autostart settings screen.
 *
 * Iska purpose:
 *   Generic "Open Settings" dialog se zyaada helpful hai — user ko exactly
 *   batata hai kahan click karna hai, kya toggle karna hai, kis cheez ko
 *   dhundhna hai. Sabse popular Indian OEM brands covered hain.
 */
object AutostartGuideDialog {

    data class GuideContent(
        val oemName: String,
        val steps: List<String>,
        val tip: String
    )

    fun show(context: Context, onOpenSettings: () -> Unit, onSkip: () -> Unit) {
        val guide = buildGuide()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val parent = FrameLayout(context)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_autostart_guide, parent, false)
        dialog.setContentView(view)

        // Set OEM badge
        view.findViewById<TextView>(R.id.tvOemBadge).text = guide.oemName

        // Fill steps dynamically
        val llSteps = view.findViewById<LinearLayout>(R.id.llSteps)
        guide.steps.forEachIndexed { index, stepText ->
            val row = buildStepRow(context, index + 1, stepText)
            llSteps.addView(row)
        }

        // Tip
        val tvTip = view.findViewById<TextView>(R.id.tvTip)
        tvTip.text = guide.tip

        // Buttons
        view.findViewById<TextView>(R.id.btnSkip).setOnClickListener {
            dialog.dismiss(); onSkip()
        }
        view.findViewById<TextView>(R.id.btnOpenSettings).setOnClickListener {
            dialog.dismiss(); onOpenSettings()
        }

        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildStepRow(context: Context, number: Int, stepText: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(context, 14))
        }

        // Number bubble
        val numView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(context, 26), dpToPx(context, 26)
            ).also { it.marginEnd = dpToPx(context, 12); it.topMargin = dpToPx(context, 2) }
            text = String.format(Locale.getDefault(), "%d", number)
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = AppCompatResources.getDrawable(context, R.drawable.bg_step_number)
        }

        // Step text
        val textView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            this.text = stepText
            textSize = 13.5f
            setTextColor(Color.parseColor("#DDDDDD"))
            setLineSpacing(0f, 1.45f)
        }

        row.addView(numView)
        row.addView(textView)
        return row
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    // ── Guide content per OEM ─────────────────────────────────────────────────

    private fun buildGuide(): GuideContent {
        val brand = Build.BRAND.lowercase()
        val mfr   = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("poco") || mfr.contains("xiaomi")
                -> xiaomiGuide()
            brand.contains("samsung") || mfr.contains("samsung")
                -> samsungGuide()
            brand.contains("realme")
                -> realmeGuide()
            brand.contains("oppo") || mfr.contains("oppo")
                -> oppoGuide()
            brand.contains("vivo") || mfr.contains("vivo")
                -> vivoGuide()
            brand.contains("oneplus") || mfr.contains("oneplus")
                -> oneplusGuide()
            brand.contains("honor")
                -> honorGuide()
            brand.contains("huawei") || mfr.contains("huawei")
                -> huaweiGuide()
            brand.contains("asus") || mfr.contains("asus")
                -> asusGuide()
            else -> genericGuide()
        }
    }

    private fun xiaomiGuide() = GuideContent(
        oemName = "Xiaomi / MIUI",
        steps = listOf(
            "Jo settings screen khulegi usme upar search bar mein 'AuraCast' type karo.",
            "AuraCast ka naam list mein dikhega — uske saamne toggle 👆 ON kar do.",
            "Agar toggle already ON hai toh kuch karne ki zaroorat nahi — bas back press karo.",
            "Wapas AuraCast app mein aao — ab streaming band nahi hogi! ✅"
        ),
        tip = "MIUI mein yeh 'Autostart' setting hoti hai. Kai baar Security app ke andar milti hai → 'Manage apps' → AuraCast."
    )

    private fun samsungGuide() = GuideContent(
        oemName = "Samsung / One UI",
        steps = listOf(
            "Jo screen khulegi woh 'Battery' settings hai — 'Background usage limits' par tap karo.",
            "Neecha scroll karo — 'Never sleeping apps' ya 'Unrestricted' section dhundho.",
            "AuraCast ko us list mein add karo ya 'Unrestricted' select karo.",
            "Back press karke AuraCast par wapas aao. Ab sab theek rahega! ✅"
        ),
        tip = "Samsung One UI 4+ mein: Settings → Apps → AuraCast → Battery → 'Unrestricted' choose karo."
    )

    private fun realmeGuide() = GuideContent(
        oemName = "Realme / ColorOS",
        steps = listOf(
            "Screen mein 'Startup manager' ya 'Auto-launch' dhundho.",
            "AuraCast ke saamne toggle ON karo.",
            "Fir 'Battery' section mein jao → AuraCast → 'No restrictions' select karo.",
            "Back aakar AuraCast open karo — streaming ab stable rahegi! ✅"
        ),
        tip = "Kabhi kabhi 'Privacy Permissions' ke andar milta hai → 'Startup manager'. Agar na mile toh Settings → Apps → AuraCast → Battery try karo."
    )

    private fun oppoGuide() = GuideContent(
        oemName = "OPPO / ColorOS",
        steps = listOf(
            "Screen mein 'Startup manager' section dhundho.",
            "AuraCast ke saamne 'Allow auto-startup' toggle ON karo.",
            "Wapas jaao aur 'Battery' → AuraCast → 'No restrictions' karo.",
            "Done! Ab AuraCast background mein bina ruke chalega. ✅"
        ),
        tip = "OPPO ColorOS 11+: Settings → Battery → More battery settings → Background app management → AuraCast → Allow."
    )

    private fun vivoGuide() = GuideContent(
        oemName = "Vivo / FuntouchOS",
        steps = listOf(
            "Jo screen khule usme AuraCast dhundho.",
            "'Background app refresh' ya 'Auto-start' ka toggle ON karo.",
            "Fir Settings → Battery → AuraCast allow karo.",
            "Wapas aao — streaming tootegi nahi! ✅"
        ),
        tip = "Vivo mein iManager app mein bhi hoti hai: iManager → App Management → Autostart Management → AuraCast ON."
    )

    private fun oneplusGuide() = GuideContent(
        oemName = "OnePlus / OxygenOS",
        steps = listOf(
            "Screen mein app list aayegi — AuraCast dhundho.",
            "'Allow auto-launch' ke saamne toggle ON karo.",
            "Battery Optimization mein bhi jao → AuraCast → 'Don't optimize' choose karo.",
            "Ab wapas aao — AuraCast background mein poori raat chalega! ✅"
        ),
        tip = "Settings → Battery → Battery optimization → sab apps → AuraCast → 'Don't optimize' — yeh step bhoolna nahi!"
    )

    private fun huaweiGuide() = GuideContent(
        oemName = "Huawei / EMUI",
        steps = listOf(
            "App list mein AuraCast scroll karke dhundho.",
            "Startup Manager mein AuraCast ke saamne toggle ON karo.",
            "Settings → Battery → App launch → AuraCast → 'Manage manually' ON karo.",
            "'Auto-launch', 'Secondary launch', 'Run in background' teeno ON karo. ✅"
        ),
        tip = "'App launch' setting sabse important hai Huawei mein. Iske bina app Doze mode mein band ho jaayegi."
    )

    private fun honorGuide() = GuideContent(
        oemName = "Honor",
        steps = listOf(
            "Startup Manager mein AuraCast dhundho — toggle ON karo.",
            "Settings → Battery → App launch → AuraCast → 'Manage manually'.",
            "Teeno options ON karo: Auto-launch, Secondary launch, Background. ✅"
        ),
        tip = "Honor devices Huawei ka hi system use karte hain — steps same hain."
    )

    private fun asusGuide() = GuideContent(
        oemName = "ASUS / ZenUI",
        steps = listOf(
            "Mobile Manager mein AuraCast dhundho.",
            "'Autostart' ke saamne toggle ON karo.",
            "Settings → Battery → Battery optimization → AuraCast → 'Don't optimize'.",
            "Done — ab app 24/7 background mein chalti rahegi! ✅"
        ),
        tip = "Agar 'AutoStart Manager' na mile toh: Settings → Apps → AuraCast → Battery → Unrestricted."
    )

    private fun genericGuide() = GuideContent(
        oemName = "Android",
        steps = listOf(
            "Settings screen mein Apps section mein jaao.",
            "AuraCast dhundho aur us par tap karo.",
            "'Battery' ya 'Battery optimization' par tap karo.",
            "'Unrestricted' ya 'Don't optimize' choose karo — aur Save karo. ✅"
        ),
        tip = "Agar kahi na mile toh Settings → search bar mein 'background' ya 'autostart' type karo."
    )
}
