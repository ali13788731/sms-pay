package ir.smsforward

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** دو اعلان جدا: ۱) غیرفعال‌شدن برنامه و دلیلش ۲) چیزهای تازه‌ی نسخه‌ی بعدی. */
object UpdateNotifier {
    private const val CH_DISABLED = "update_disabled"
    private const val CH_NEWS = "update_news"
    private const val ID_DISABLED = 1001
    private const val ID_NEWS = 1002

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_DISABLED, c.getString(R.string.ch_disabled), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NEWS, c.getString(R.string.ch_news), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun notifyUpdate(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ensureChannels(c)
            val p = Prefs(c)
            val name = p.reqName.ifBlank { p.reqCode.toString() }
            val reason = p.reqReason.ifBlank { c.getString(R.string.upd_default_reason) }
            val open = PendingIntent.getActivity(
                c, 0,
                Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nm = NotificationManagerCompat.from(c)

            // اعلان ۱: غیرفعال شد
            nm.notify(
                ID_DISABLED,
                NotificationCompat.Builder(c, CH_DISABLED)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(c.getString(R.string.notif_disabled_title))
                    .setContentText(c.getString(R.string.notif_disabled_text, name))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(c.getString(R.string.notif_disabled_big, name, reason)))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )

            // اعلان ۲: در نسخه‌ی بعدی چه چیزهایی اضافه شده
            val changes = p.reqChanges
            val list = if (changes.isEmpty()) c.getString(R.string.notif_news_empty)
            else changes.joinToString("\n") { "• $it" }
            nm.notify(
                ID_NEWS,
                NotificationCompat.Builder(c, CH_NEWS)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(c.getString(R.string.notif_news_title, name))
                    .setContentText(changes.firstOrNull() ?: c.getString(R.string.notif_news_empty))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(list))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            // دسترسی اعلان داده نشده؛ صفحه‌ی بروزرسانی همچنان نمایش داده می‌شود
        }
    }
}
