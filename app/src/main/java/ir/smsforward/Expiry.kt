package ir.smsforward

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** نمایش شمارش معکوس پایان اشتراک */
object Countdown {
    /** میلی‌ثانیه‌ی باقی‌مانده تا پایان اشتراک فعال؛ null اگر اشتراک فعال نیست یا تاریخ پایان معلوم نیست */
    fun remaining(c: Context, now: Long = System.currentTimeMillis()): Long? {
        if (!Premium.isActive(c)) return null
        val cur = SubscriptionStore.current(c) ?: return null
        return (SubscriptionStore.expiry(cur, true, now) - now).coerceAtLeast(0L)
    }

    /** «۱۲ روز و ۰۳:۲۱:۰۵» یا فقط «۰۳:۲۱:۰۵» */
    fun format(c: Context, ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val d = total / 86_400
        val h = total % 86_400 / 3_600
        val m = total % 3_600 / 60
        val s = total % 60
        val clock = Fa.digits(String.format(Locale.US, "%02d:%02d:%02d", h, m, s))
        return if (d > 0) c.getString(R.string.countdown_days, Fa.num(d), clock) else clock
    }
}

/**
 * هر ثانیه یک بار [onTick] را صدا می‌زند (برای تایمر)، و اگر اشتراک وسط کار تمام شد
 * (فعال بود و دیگر نیست) [onExpired] را صدا می‌زند تا صفحه همان لحظه قفل شود.
 */
class ExpiryTicker(
    private val c: Context,
    private val onTick: (remainingMs: Long?) -> Unit = {},
    private val onExpired: () -> Unit
) {
    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var wasActive = false

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val active = Premium.isActive(c)
            if (wasActive && !active) {
                wasActive = false
                onExpired()
            } else {
                wasActive = active
            }
            onTick(Countdown.remaining(c))
            h.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        wasActive = Premium.isActive(c)
        h.post(loop)
    }

    fun stop() {
        running = false
        h.removeCallbacks(loop)
    }
}
