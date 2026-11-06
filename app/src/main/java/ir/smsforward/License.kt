package ir.smsforward

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * وضعیت اشتراک (پریمیوم). فقط وضعیت «آخرین تأیید موفق از کافه‌بازار» را محلی نگه می‌دارد.
 * State.OK یعنی اشتراک فعال است؛ State.LOCKED یعنی نسخه‌ی رایگان (برنامه قفل نمی‌شود،
 * فقط امکانات ویژه محدود می‌شوند؛ [Premium] را ببینید).
 *
 * - منبع حقیقت خودِ بازار است (دوره‌ی رایگان هم از بازار می‌آید و به حساب بازار وابسته است).
 * - این وضعیت با کلید غیرقابل‌استخراج Android Keystore امضا (HMAC) می‌شود؛ ویرایش دستی
 *   فایل تنظیمات یا کپی‌کردنش روی گوشی دیگر باطل می‌شود و برنامه قفل می‌ماند تا دوباره از بازار تأیید شود.
 * - اگر بازار برای مدتی در دسترس نباشد (بدون اینترنت)، تا [GRACE_MS] بعد از آخرین تأیید برنامه کار می‌کند.
 *   گذشت زمان با ساعت «از زمان روشن‌شدن» سنجیده می‌شود، پس عوض‌کردن ساعت گوشی اثری ندارد.
 */
object LicenseGate {
    enum class State { OK, LOCKED }

    /** بیشترین زمانی که برنامه بدون یک تأیید موفق از بازار اجازه‌ی کار دارد */
    const val GRACE_MS = 7L * 24 * 60 * 60 * 1000

    /** وقتی برنامه باز است، اگر از آخرین تأیید بیشتر از این گذشته باشد دوباره از بازار پرسیده می‌شود */
    const val REFRESH_EVERY_MS = 6L * 60 * 60 * 1000

    fun state(c: Context): State {
        val s = LicenseStore.read(c) ?: return State.LOCKED
        if (!s.active) return State.LOCKED
        val age = LicenseStore.ageMs(c, s) ?: return State.LOCKED // null = ساعت عقب برده شده
        if (age > GRACE_MS) return State.LOCKED
        // تاریخ پایانِ اعلام‌شده‌ی بازار: به‌محض رسیدن، همه‌چیز قفل می‌شود (تا تمدید یا خرید دوباره).
        // «اکنون» از لنگر زمانی امضاشده + زمان سپری‌شده حساب می‌شود، نه ساعت دیواری، تا عوض‌کردن ساعت اثری نداشته باشد.
        if (s.expiry > 0L && s.okWall + age >= s.expiry) return State.LOCKED
        return State.OK
    }
}

object LicenseStore {

    /** active: نتیجه‌ی آخرین تأیید بازار؛ بقیه: لنگرهای زمانی همان لحظه */
    class Snap(val active: Boolean, val okWall: Long, val okElapsed: Long, val boot: Int, val expiry: Long = 0L)

    private const val FILE = "lic"
    private const val K_BLOB = "b"
    private const val K_MAC = "m"
    private const val K_SEEN = "seen"
    private const val K_NOTIFIED = "notified_end"
    private const val ALIAS = "sms_license_hmac_v1"
    private const val CLOCK_SLACK_MS = 6L * 60 * 60 * 1000

    @Volatile private var cachedKey: SecretKey? = null
    @Volatile private var memoKey: String? = null
    @Volatile private var memoSnap: Snap? = null

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ خواندن و نوشتن

    /** null یعنی چیزی ذخیره نشده یا امضا معتبر نیست (دستکاری، پاک‌شدن داده، گوشی دیگر). */
    fun read(c: Context): Snap? {
        val s = sp(c)
        val blob = s.getString(K_BLOB, null) ?: return null
        val mac = s.getString(K_MAC, null) ?: return null
        val key = "$blob|$mac"
        if (key == memoKey) return memoSnap
        val expected = try {
            sign(c, blob)
        } catch (e: Exception) {
            return null
        }
        if (!MessageDigest.isEqual(expected.toByteArray(), mac.toByteArray())) return null
        val snap = parse(blob) ?: return null
        memoKey = key
        memoSnap = snap
        return snap
    }

    /** نتیجه‌ی یک تأیید موفق (یا ردِ) بازار را همراه با لنگرهای زمانی ثبت می‌کند. */
    fun recordVerified(c: Context, active: Boolean, expiry: Long = 0L) {
        val now = System.currentTimeMillis()
        val blob = "2|${if (active) 1 else 0}|$now|${SystemClock.elapsedRealtime()}|${bootCount(c)}|${if (active) expiry else 0L}"
        try {
            val mac = sign(c, blob)
            val e = sp(c).edit().putString(K_BLOB, blob).putString(K_MAC, mac).putLong(K_SEEN, now)
            if (active) e.putBoolean(K_NOTIFIED, false)
            e.commit()
        } catch (e: Exception) {
            // امضا ممکن نشد؛ وضعیت قبلی (نامعتبر) باقی می‌ماند و برنامه قفل می‌ماند
        }
        if (active) LicenseNotifier.cancelEnded(c)
    }

    private fun parse(blob: String): Snap? {
        val p = blob.split("|")
        val ok = (p.size == 5 && p[0] == "1") || (p.size == 6 && p[0] == "2")
        if (!ok) return null
        return try {
            Snap(p[1] == "1", p[2].toLong(), p[3].toLong(), p[4].toInt(), if (p.size == 6) p[5].toLong() else 0L)
        } catch (e: NumberFormatException) {
            null
        }
    }

    // ------------------------------------------------------------------ زمان

    /**
     * چند میلی‌ثانیه از آخرین تأیید گذشته است؛ null یعنی ساعت گوشی بعد از روشن‌شدن عقب برده شده.
     * - بدون راه‌اندازی مجدد گوشی: فقط ساعت «از زمان روشن‌شدن» (elapsedRealtime) معیار است و ساعت
     *   دیواری هیچ نقشی ندارد.
     * - بعد از راه‌اندازی مجدد: ساعت دیواری معیار است، ولی اگر از بیشترین زمانِ دیده‌شده عقب‌تر باشد
     *   (عقب‌بردن ساعت) رد می‌شود.
     */
    fun ageMs(c: Context, s: Snap): Long? {
        val nowW = System.currentTimeMillis()
        val nowE = SystemClock.elapsedRealtime()
        val boot = bootCount(c)
        val age: Long
        if (boot != -1 && boot == s.boot && nowE >= s.okElapsed) {
            age = nowE - s.okElapsed
            // زمان دیده‌شده را فقط تا حدی که با ساعت پایدار می‌خواند بالا می‌بریم؛
            // جلو بردن ساعت و برگرداندنش نباید بعداً برنامه را قفل کند
            touchSeen(c, minOf(nowW, s.okWall + age + CLOCK_SLACK_MS))
        } else {
            if (nowW + CLOCK_SLACK_MS < maxOf(seen(c), s.okWall)) return null
            age = nowW - s.okWall
            touchSeen(c, nowW)
        }
        return age
    }

    private fun seen(c: Context): Long = sp(c).getLong(K_SEEN, 0L)

    private fun touchSeen(c: Context, t: Long) {
        if (t > seen(c) + 60_000L) sp(c).edit().putLong(K_SEEN, t).apply()
    }

    private fun bootCount(c: Context): Int = try {
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }

    // ------------------------------------------------------------------ اعلان پایان

    fun endNotified(c: Context): Boolean = sp(c).getBoolean(K_NOTIFIED, false)

    fun setEndNotified(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_NOTIFIED, v).apply()
    }

    // ------------------------------------------------------------------ امضا

    private fun sign(c: Context, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key(c))
        return Base64.encodeToString(mac.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    /**
     * کلید غیرقابل‌استخراج در Android Keystore. در بعضی رام‌های معیوب Keystore خطا می‌دهد؛
     * برای اینکه کاربرِ پرداخت‌کرده قفل نماند، کلیدی وابسته به همین گوشی و برنامه جایگزین می‌شود.
     */
    private fun key(c: Context): SecretKey {
        cachedKey?.let { return it }
        val k = try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
                .apply {
                    init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
                }
                .generateKey()
        } catch (e: Exception) {
            val id = try {
                Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            } catch (e2: Exception) {
                ""
            }
            val seed = MessageDigest.getInstance("SHA-256")
                .digest("sms-lic|${c.packageName}|$id".toByteArray())
            SecretKeySpec(seed, "HmacSHA256")
        }
        cachedKey = k
        return k
    }
}
