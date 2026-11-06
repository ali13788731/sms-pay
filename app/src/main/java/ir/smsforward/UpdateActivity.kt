package ir.smsforward

import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ir.smsforward.databinding.ActivityUpdateBinding

/**
 * پنجره‌ی بروزرسانی اجباری. دکمه‌ی برگشت کار نمی‌کند و تا نصب نسخه‌ی جدید
 * هیچ راهی برای رفتن به بقیه‌ی برنامه نیست. حالت دوم: «اتصال به اینترنت لازم است».
 */
class UpdateActivity : RtlActivity() {

    private lateinit var b: ActivityUpdateBinding
    private var bob: ObjectAnimator? = null
    private var introPlayed = false
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(b.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* بازگشت ممنوع */ }
        })
        b.btnExit.setOnClickListener { finishAffinity() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        bob?.cancel()
        super.onPause()
    }

    private fun render() {
        when (UpdateGate.state(this)) {
            UpdateGate.State.OK -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
            UpdateGate.State.UPDATE -> showUpdate()
            UpdateGate.State.ONLINE -> showOnline()
        }
        playIntro()
    }

    private fun showUpdate() {
        val p = Prefs(this)
        b.title.setText(R.string.upd_title)
        b.subtitle.setText(R.string.upd_subtitle)
        b.versionRow.isVisible = true
        b.verOld.text = getString(R.string.upd_ver_old, UpdateChecker.installedName(this))
        b.verNew.text = getString(R.string.upd_ver_new, p.reqName.ifBlank { p.reqCode.toString() })

        b.reasonBox.isVisible = true
        b.reasonText.text = p.reqReason.ifBlank { getString(R.string.upd_default_reason) }

        val changes = p.reqChanges
        b.changesBox.isVisible = changes.isNotEmpty()
        b.changesList.removeAllViews()
        changes.forEach { b.changesList.addView(changeRow(it)) }

        b.note.setText(R.string.upd_note)
        val link = p.reqUrl
        if (link.isBlank()) {
            b.btnUpdate.isVisible = false
            b.note.setText(R.string.upd_no_link)
        } else {
            b.btnUpdate.isVisible = true
            b.btnUpdate.setText(R.string.upd_button)
            b.btnUpdate.isEnabled = true
            b.btnUpdate.setOnClickListener { openLink(link) }
        }
    }

    private fun showOnline() {
        b.title.setText(R.string.upd_online_title)
        b.subtitle.setText(R.string.upd_online_msg)
        b.versionRow.isVisible = false
        b.reasonBox.isVisible = false
        b.changesBox.isVisible = false
        b.note.setText(R.string.upd_online_note)
        b.btnUpdate.isVisible = true
        b.btnUpdate.isEnabled = !checking
        b.btnUpdate.setText(if (checking) R.string.upd_checking else R.string.upd_retry)
        b.btnUpdate.setOnClickListener { retry() }
    }

    private fun retry() {
        if (checking) return
        checking = true
        showOnline()
        val app = applicationContext
        Thread {
            val r = UpdateChecker.check(app)
            runOnUiThread {
                checking = false
                if (isFinishing) return@runOnUiThread
                if (r == UpdateChecker.Result.OFFLINE || r == UpdateChecker.Result.ERROR) {
                    Toast.makeText(this, R.string.upd_still_offline, Toast.LENGTH_LONG).show()
                }
                render()
            }
        }.start()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.upd_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun changeRow(text: String): View {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * d).toInt() }
        }
        val dot = TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_App_Body)
            this.text = "✓"
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (10 * d).toInt() }
        }
        val tv = TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_App_Body)
            this.text = text
            gravity = Gravity.START
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(dot)
        row.addView(tv)
        return row
    }

    private fun playIntro() {
        if (!introPlayed) {
            introPlayed = true
            val d = resources.displayMetrics.density
            b.card.alpha = 0f
            b.card.translationY = 48 * d
            b.card.scaleX = 0.94f
            b.card.scaleY = 0.94f
            b.card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(550).setInterpolator(DecelerateInterpolator()).start()
        }
        val d = resources.displayMetrics.density
        bob?.cancel()
        bob = ObjectAnimator.ofFloat(b.updateIcon, View.TRANSLATION_Y, -5 * d, 5 * d).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
