package ir.smsforward

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * وضعیت سیم‌کارت‌ها. «سیم ۱ / سیم ۲» فقط وقتی در برنامه نشان داده می‌شود که گوشی
 * قابلیت دو سیم‌کارت داشته باشد و هر دو سیم‌کارت روشن (فعال) باشند.
 */
object SimInfo {

    class State(val capableDual: Boolean, val active: Set<Int>) {
        /** گوشی دو سیم‌کارته است و دو سیم‌کارت فعال دارد */
        val multi: Boolean get() = capableDual && active.size >= 2
    }

    fun state(c: Context): State {
        val sm = try {
            c.getSystemService(SubscriptionManager::class.java)
        } catch (e: Exception) {
            null
        }
        val max = try {
            sm?.activeSubscriptionInfoCountMax ?: 1
        } catch (e: Exception) {
            1
        }
        val capable = max >= 2
        val active = HashSet<Int>()
        val hasPerm = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            try {
                sm?.activeSubscriptionInfoList?.forEach { if (it.simSlotIndex >= 0) active.add(it.simSlotIndex) }
            } catch (e: Exception) {
                // دسترسی یا سرویس در دسترس نیست؛ فرض می‌کنیم فقط یک سیم فعال است
            }
        } else if (capable) {
            // تا وقتی دسترسی داده نشده نمی‌دانیم کدام سیم روشن است
            active.add(0)
            active.add(1)
        }
        return State(capable, active)
    }

    fun multi(c: Context): Boolean = state(c).multi
}
