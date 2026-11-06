package ir.smsforward

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import ir.smsforward.databinding.ActivitySplashBinding

/**
 * صفحه‌ی شروع با لوگوی انیمیشنی. فقط وقتی برنامه از صفر اجرا می‌شود نمایش داده می‌شود؛
 * کوچک‌کردن و برگشتن به برنامه دوباره آن را نشان نمی‌دهد.
 * هم‌زمان با انیمیشن، نسخه‌ی برنامه با سرور بررسی می‌شود.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var b: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())

    private var animDone = false
    private var checkDone = false
    private var resumed = false
    private var proceeded = false
    private var typer: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        b.logo.start()

        // نام برنامه
        b.appTitle.alpha = 0f
        b.appTitle.translationY = 24f * resources.displayMetrics.density
        b.appTitle.animate().alpha(1f).translationY(0f)
            .setStartDelay(1000).setDuration(600).setInterpolator(DecelerateInterpolator()).start()

        // «طراحی شده توسط Mr.PC» حرف‌به‌حرف تایپ می‌شود
        val text = getString(R.string.splash_credit)
        val full = SpannableStringBuilder(text)
        val brand = "Mr.PC"
        val at = text.indexOf(brand)
        if (at >= 0) {
            full.setSpan(ForegroundColorSpan(Color.parseColor("#9BF0F5")), at, at + brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            full.setSpan(StyleSpan(android.graphics.Typeface.BOLD), at, at + brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.credit.text = ""
        b.creditLine.scaleX = 0f
        b.creditLine.animate().scaleX(1f).setStartDelay(1500).setDuration(900)
            .setInterpolator(DecelerateInterpolator()).start()

        typer = ValueAnimator.ofInt(0, full.length).apply {
            startDelay = 1600
            duration = full.length * 55L
            interpolator = LinearInterpolator()
            addUpdateListener { b.credit.text = full.subSequence(0, it.animatedValue as Int) }
            start()
        }

        // پایان انیمیشن: بعد از تایپ کامل کمی صبر می‌کنیم
        handler.postDelayed({ animDone = true; maybeProceed() }, 1600L + full.length * 55L + 650L)

        // بررسی نسخه هم‌زمان با انیمیشن؛ حداکثر چند ثانیه منتظرش می‌مانیم
        val app = applicationContext
        Thread {
            UpdateChecker.check(app)
            handler.post { checkDone = true; maybeProceed() }
        }.start()
        handler.postDelayed({ checkDone = true; maybeProceed() }, 8000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        maybeProceed()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        typer?.cancel()
        super.onDestroy()
    }

    private fun maybeProceed() {
        if (proceeded || !animDone || !checkDone || !resumed || isFinishing) return
        proceeded = true
        val next = when {
            UpdateGate.state(this) != UpdateGate.State.OK -> UpdateActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, next))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
