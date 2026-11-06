package ir.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * وضعیت اجازه‌ی کارکرد برنامه.
 * - UPDATE: نسخه‌ی جدیدتری منتشر شده؛ این نسخه هیچ کاری نمی‌کند (رابط کاربری و ارسال پیامک).
 * - ONLINE: مدت زیادی بررسی نسخه انجام نشده (یا ساعت گوشی عقب برده شده)؛ باید یک بار آنلاین بررسی شود.
 */
object UpdateGate {
    enum class State { OK, UPDATE, ONLINE }

    /** بیشترین زمانی که برنامه بدون یک بررسی موفق نسخه اجازه‌ی کار دارد */
    const val MAX_OFFLINE_MS = 7L * 24 * 60 * 60 * 1000
    private const val CLOCK_SLACK_MS = 6L * 60 * 60 * 1000

    fun state(c: Context): State {
        val p = Prefs(c)
        // ذخیره‌شده از سرور؛ با دستکاری ساعت یا شماره‌ی نسخه در تنظیمات عوض نمی‌شود
        if (p.reqCode > UpdateChecker.installedCode(c)) return State.UPDATE
        if (!UpdateChecker.configured()) return State.OK
        val now = System.currentTimeMillis()
        val base = maxOf(p.lastOk, p.firstRun)
        if (base == 0L) return State.OK
        if (now + CLOCK_SLACK_MS < p.lastSeen || now - base > MAX_OFFLINE_MS) return State.ONLINE
        return State.OK
    }

    /** بیشترین زمان دیده‌شده‌ی ساعت را نگه می‌دارد تا عقب‌بردن ساعت تشخیص داده شود. */
    fun touch(c: Context) {
        val p = Prefs(c)
        val now = System.currentTimeMillis()
        if (now > p.lastSeen) p.lastSeen = now
    }
}

object UpdateChecker {
    enum class Result { UP_TO_DATE, UPDATE, OPTIONAL, OFFLINE, ERROR, SKIPPED }

    private const val STALE_MS = 12L * 60 * 60 * 1000
    private const val RETRY_GAP_MS = 30L * 60 * 1000
    private const val MAX_BODY = 64 * 1024

    /** تا وقتی آدرس واقعی در build.gradle.kts تنظیم نشده، بررسی بروزرسانی خاموش است. */
    fun configured(): Boolean {
        val u = BuildConfig.UPDATE_URL
        return u.startsWith("https://") && !u.contains("YOUR_")
    }

    fun installedCode(c: Context): Int = try {
        androidx.core.content.pm.PackageInfoCompat
            .getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)).toInt()
    } catch (e: Exception) {
        BuildConfig.VERSION_CODE
    }

    fun installedName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
    } catch (e: Exception) {
        BuildConfig.VERSION_NAME
    }

    /** برای بررسی پس‌زمینه هنگام رسیدن پیامک: مدت زیادی از آخرین بررسی گذشته است؟ */
    fun isStale(c: Context): Boolean {
        if (!configured()) return false
        val p = Prefs(c)
        val now = System.currentTimeMillis()
        return now - p.lastOk > STALE_MS && now - p.lastTry > RETRY_GAP_MS
    }

    private class HttpFail(code: Int) : Exception("HTTP $code")

    /** باید روی نخ پس‌زمینه صدا زده شود. */
    fun check(c: Context): Result {
        if (!configured()) return Result.SKIPPED
        val ctx = c.applicationContext
        val p = Prefs(ctx)
        p.lastTry = System.currentTimeMillis()

        val body = try {
            fetch(BuildConfig.UPDATE_URL)
        } catch (e: IOException) {
            return Result.OFFLINE
        } catch (e: Exception) {
            return Result.ERROR
        }

        val o: JSONObject
        val code: Int
        try {
            o = JSONObject(body)
            code = o.getInt("versionCode")
        } catch (e: Exception) {
            return Result.ERROR
        }
        p.markCheckOk(System.currentTimeMillis())

        if (code <= installedCode(ctx)) {
            p.clearNews()
            return Result.UP_TO_DATE
        }

        // "mandatory": false → بروزرسانی اختیاری؛ برنامه قفل نمی‌شود و فقط اعلان «با بروزرسانی این امکانات اضافه می‌شود» می‌رود
        if (!o.optBoolean("mandatory", true)) {
            val link = o.optString("downloadUrl").trim()
            p.saveNews(
                code = code,
                name = o.optString("versionName").trim(),
                url = if (link.startsWith("https://") || link.startsWith("http://")) link else "",
                changes = strings(o.optJSONArray("changelog"))
            )
            return Result.OPTIONAL
        }

        // اگر قبلاً نسخه‌ی بالاتری ثبت شده، با کمتر شدن عدد در سرور برنامه آزاد نمی‌شود
        if (code >= p.reqCode) {
            val link = o.optString("downloadUrl").trim()
            p.saveRequiredUpdate(
                code = code,
                name = o.optString("versionName").trim(),
                reason = o.optString("reason").trim(),
                url = if (link.startsWith("https://") || link.startsWith("http://")) link else "",
                changes = strings(o.optJSONArray("changelog"))
            )
        }
        // نسخه‌ی قدیمی غیرفعال می‌شود: کلید اصلی و همه‌ی قانون‌ها
        p.disableAll()
        if (p.notifiedCode < p.reqCode) {
            p.notifiedCode = p.reqCode
            UpdateNotifier.notifyUpdate(ctx)
        }
        return Result.UPDATE
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw HttpFail(code)
            return conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                while (true) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    if (buf.size() > MAX_BODY) throw HttpFail(-1)
                }
                buf.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }
}
