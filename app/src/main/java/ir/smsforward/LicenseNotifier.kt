package ir.smsforward

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/** اعلان‌های اشتراک و پیشنهادها. همه‌ی اعلان‌ها با دسترسی اعلان کاربر و تنظیمات برنامه کنترل می‌شوند. */
object LicenseNotifier {
    private const val CH_LICENSE = "license"
    private const val CH_PROMO = "promo"
    private const val ID_LICENSE = 1003
    private const val ID_PROMO = 1005

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_LICENSE, c.getString(R.string.ch_license), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROMO, c.getString(R.string.ch_promo), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun canNotify(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** نه شب و نه نیمه‌شب: اعلان‌ها فقط بین ۹ صبح و ۹ شب نمایش داده می‌شوند. */
    fun quietHoursOk(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h in 9..20
    }

    private fun subscriptionIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 11,
        Intent(c, SubscriptionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** صفحه‌ی برنامه در بازار؛ اگر بازار نصب نبود، خود برنامه باز می‌شود. */
    private fun storeIntent(c: Context): PendingIntent {
        val i = if (LicenseManager.bazaarInstalled(c)) {
            Intent(Intent.ACTION_VIEW, Uri.parse("bazaar://details?id=${c.packageName}"))
                .setPackage("com.farsitel.bazaar")
        } else {
            Intent(c, MainActivity::class.java)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(c, 12, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(
        c: Context, channel: String, id: Int, title: String, text: String, big: String,
        pi: PendingIntent, actionLabel: String?, high: Boolean
    ): Boolean {
        if (!canNotify(c)) return false
        return try {
            ensureChannel(c)
            val b = NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(c, R.color.brand))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_PROMO)
                .setContentIntent(pi)
                .setAutoCancel(true)
            if (actionLabel != null) b.addAction(0, actionLabel, pi)
            NotificationManagerCompat.from(c).notify(id, b.build())
            true
        } catch (e: SecurityException) {
            false
        }
    }

    // ------------------------------------------------------------------ اشتراک

    /** چند روز مانده به پایان اشتراک */
    fun notifySoon(c: Context, plan: Plan, daysLeft: Int, expiry: Long): Boolean {
        val days = if (daysLeft <= 0) c.getString(R.string.notif_soon_today) else
            c.getString(R.string.notif_soon_days, Fa.num(daysLeft))
        return post(
            c, CH_LICENSE, ID_LICENSE,
            c.getString(R.string.notif_soon_title, days),
            c.getString(R.string.notif_soon_text, plan.title(c)),
            c.getString(R.string.notif_soon_big, plan.title(c), Fa.date(expiry)),
            subscriptionIntent(c), c.getString(R.string.notif_action_renew), true
        )
    }

    private fun lockedLine(c: Context): String {
        val n = Prefs(c).rules().count { it.lock.isNotEmpty() }
        return if (n > 0) "\n\n" + c.getString(R.string.notif_locked_line, Fa.num(n)) else ""
    }

    /** اشتراک همین حالا تمام شده است (فقط یک بار برای هر اشتراک) */
    fun notifyEnded(c: Context): Boolean = post(
        c, CH_LICENSE, ID_LICENSE,
        c.getString(R.string.notif_ended_title),
        c.getString(R.string.notif_ended_text),
        c.getString(R.string.notif_ended_big) + lockedLine(c),
        subscriptionIntent(c), c.getString(R.string.notif_action_buy), true
    )

    /** یادآوری تکراریِ «اشتراک تمام شده»؛ متن‌ها چرخشی‌اند تا خسته‌کننده نشود. */
    fun notifyExpiredRepeat(c: Context, index: Int): Boolean {
        val titles = intArrayOf(R.string.notif_rep0_title, R.string.notif_rep1_title, R.string.notif_rep2_title, R.string.notif_rep3_title)
        val texts = intArrayOf(R.string.notif_rep0_text, R.string.notif_rep1_text, R.string.notif_rep2_text, R.string.notif_rep3_text)
        val i = index % titles.size
        val text = if (texts[i] == R.string.notif_rep3_text) c.getString(texts[i], Plan.summary(c)) else c.getString(texts[i])
        return post(
            c, CH_LICENSE, ID_LICENSE,
            c.getString(titles[i]), text, text + lockedLine(c),
            subscriptionIntent(c), c.getString(R.string.notif_action_buy), false
        )
    }

    fun cancelAll(c: Context) {
        try {
            NotificationManagerCompat.from(c).cancel(ID_LICENSE)
        } catch (e: Exception) {
            // مهم نیست
        }
    }

    fun cancelEnded(c: Context) = cancelAll(c)

    // ------------------------------------------------------------------ پیشنهاد و تازه‌ها

    /** «بروزرسانی کنید، این امکانات اضافه می‌شود» */
    fun notifyNews(c: Context): Boolean {
        val p = Prefs(c)
        val name = p.newsName.ifBlank { p.newsCode.toString() }
        val changes = p.newsChanges
        val list = if (changes.isEmpty()) c.getString(R.string.promo_update_empty)
        else changes.joinToString("\n") { "• $it" }
        return post(
            c, CH_PROMO, ID_PROMO,
            c.getString(R.string.promo_update_title, name),
            c.getString(R.string.promo_update_text),
            c.getString(R.string.promo_update_big, list),
            storeIntent(c), c.getString(R.string.promo_update_action), false
        )
    }

    /** معرفی یک امکان ویژه برای کاربران رایگان */
    fun notifyTip(c: Context, feature: Feature): Boolean {
        val title = c.getString(R.string.promo_tip_title, c.getString(feature.title))
        val desc = c.getString(feature.desc)
        return post(
            c, CH_PROMO, ID_PROMO, title, desc,
            desc + "\n\n" + c.getString(R.string.promo_tip_price, Plan.summary(c)),
            subscriptionIntent(c), c.getString(R.string.promo_tip_action), false
        )
    }
}

/**
 * یادآوری پایان اشتراک؛ فقط محلی و ارزان است و از کار پس‌زمینه‌ی دوره‌ای صدا زده می‌شود.
 * - چند روز مانده به پایان: طرح سالانه ۷، ۳ و ۱ روز؛ طرح ماهانه ۳ و ۱ روز مانده (هر آستانه یک بار).
 * - بعد از پایان: همان لحظه یک اعلان، و بعد هر ۳ روز یک بار (بعد از ۱۰ یادآوری، هفته‌ای یک بار) تا اشتراک دوباره خریداری شود.
 */
object Reminders {
    private val SOON_YEARLY = intArrayOf(7, 3, 1)
    private val SOON_MONTHLY = intArrayOf(3, 1)
    private const val EVERY_DAYS = 3L
    private const val EVERY_DAYS_LATE = 7L
    private const val FAST_COUNT = 10

    fun run(c: Context) {
        val p = Prefs(c)
        if (!p.remindersOn) return
        val cur = SubscriptionStore.current(c) ?: return // کسی که هیچ‌وقت اشتراک نداشته یادآوری نمی‌گیرد
        if (!LicenseNotifier.quietHoursOk()) return
        val now = System.currentTimeMillis()

        if (Premium.isActive(c)) {
            val exp = SubscriptionStore.expiry(cur, true, now)
            val left = Fa.daysLeft(exp, now)
            val list = if (cur.days >= 180) SOON_YEARLY else SOON_MONTHLY
            val t = list.filter { left <= it }.minOrNull() ?: return
            if (p.remExpiry == exp && p.remThreshold <= t) return
            if (LicenseNotifier.notifySoon(c, cur.plan, left, exp)) {
                p.remExpiry = exp
                p.remThreshold = t
            }
            return
        }

        if (!cur.ended) return // هنوز بازار پایان اشتراک را تأیید نکرده (مثلاً فقط اینترنت قطع است)

        if (p.expiredNotifiedFor != cur.purchaseTime) {
            if (LicenseNotifier.notifyEnded(c)) {
                p.expiredNotifiedFor = cur.purchaseTime
                p.expiredLast = now
                p.expiredCount = 1
            }
            return
        }
        val gap = (if (p.expiredCount >= FAST_COUNT) EVERY_DAYS_LATE else EVERY_DAYS) * Fa.DAY
        if (now - p.expiredLast >= gap) {
            if (LicenseNotifier.notifyExpiredRepeat(c, p.expiredCount)) {
                p.expiredLast = now
                p.expiredCount = p.expiredCount + 1
            }
        }
    }
}

/** پیشنهادهای دوره‌ای (هر چند روز): بروزرسانی جدید با فهرست امکانات، یا معرفی یک امکان ویژه. */
object PromoNotifier {
    private const val EVERY_DAYS = 3L

    fun run(c: Context) {
        val p = Prefs(c)
        if (!p.promoOn) return
        val now = System.currentTimeMillis()
        if (now - p.promoLast < EVERY_DAYS * Fa.DAY) return
        if (!LicenseNotifier.quietHoursOk()) return
        // بروزرسانی اجباری اعلان‌های خودش را دارد
        if (UpdateGate.state(c) != UpdateGate.State.OK) return

        if (p.newsCode > UpdateChecker.installedCode(c)) {
            if (LicenseNotifier.notifyNews(c)) p.promoLast = now
            return
        }
        if (Premium.isActive(c)) return
        // کاربری که اشتراکش تمام شده یادآوری‌های خودش را می‌گیرد
        if (SubscriptionStore.current(c)?.ended == true && p.remindersOn) return
        val all = Feature.values()
        val f = all[p.promoIndex % all.size]
        if (LicenseNotifier.notifyTip(c, f)) {
            p.promoLast = now
            p.promoIndex = (p.promoIndex + 1) % all.size
        }
    }
}
