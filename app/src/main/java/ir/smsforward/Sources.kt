package ir.smsforward

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * منابع پیشنهاد شماره: مخاطبین گوشی و فرستنده‌های صندوق پیامک (شامل نام‌های متنی مثل DigiKala).
 * خواندن فقط در لحظه‌ی نیاز و فقط در حافظه انجام می‌شود؛ هیچ‌چیز از اینجا ذخیره نمی‌شود.
 */
object Sources {

    enum class Kind { CONTACT, SMS }

    /** [title] عنوان نمایشی، [value] مقداری که داخل فیلد نوشته می‌شود، [subtitle] توضیح زیر عنوان */
    data class Entry(val title: String, val value: String, val subtitle: String, val kind: Kind)

    private const val MAX_SMS_ROWS = 5000

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var contactsCache: List<Entry>? = null
    @Volatile private var smsCache: List<Entry>? = null

    fun permission(kind: Kind): String = when (kind) {
        Kind.CONTACT -> Manifest.permission.READ_CONTACTS
        Kind.SMS -> Manifest.permission.READ_SMS
    }

    fun has(c: Context, kind: Kind): Boolean =
        ContextCompat.checkSelfPermission(c, permission(kind)) == PackageManager.PERMISSION_GRANTED

    /** نتیجه‌ی آخرین بارگذاری (یا خالی)؛ برای پیشنهاد خودکار هنگام تایپ */
    fun cached(kind: Kind): List<Entry> =
        (if (kind == Kind.CONTACT) contactsCache else smsCache) ?: emptyList()

    fun clearCache() {
        contactsCache = null
        smsCache = null
    }

    /** بارگذاری در پس‌زمینه؛ [done] روی نخ اصلی صدا زده می‌شود. */
    fun load(c: Context, kind: Kind, force: Boolean, done: (List<Entry>) -> Unit) {
        val app = c.applicationContext
        if (!has(app, kind)) {
            if (kind == Kind.CONTACT) contactsCache = null else smsCache = null
            done(emptyList())
            return
        }
        val hit = if (kind == Kind.CONTACT) contactsCache else smsCache
        if (hit != null && !force) {
            done(hit)
            return
        }
        io.execute {
            val list = try {
                if (kind == Kind.CONTACT) readContacts(app) else readSmsSenders(app)
            } catch (e: Exception) {
                emptyList()
            }
            if (kind == Kind.CONTACT) contactsCache = list else smsCache = list
            main.post { done(list) }
        }
    }

    // ------------------------------------------------------------ مخاطبین

    private fun readContacts(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        c.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            null, null,
            "${Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0)?.trim().orEmpty()
                val number = Forwarder.normalizeTarget(cur.getString(1).orEmpty())
                if (number.isEmpty()) continue
                // یک مخاطب با چند شماره، چند ردیف می‌شود؛ تکراری‌های دقیق حذف می‌شوند
                if (!seen.add(name.lowercase() + "|" + Forwarder.key(number))) continue
                out.add(Entry(name.ifEmpty { number }, number, number, Kind.CONTACT))
            }
        }
        return out
    }

    // ------------------------------------------------------------ فرستنده‌های پیامک

    private class Agg(val display: String, var count: Int, val last: Long)

    private fun readSmsSenders(c: Context): List<Entry> {
        val map = LinkedHashMap<String, Agg>() // به ترتیب جدیدترین پیام
        c.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            null, null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cur ->
            var rows = 0
            while (cur.moveToNext() && rows++ < MAX_SMS_ROWS) {
                val addr = cur.getString(0)?.trim().orEmpty()
                if (addr.isEmpty()) continue
                val k = Forwarder.key(addr)
                val a = map[k]
                if (a == null) map[k] = Agg(addr, 1, cur.getLong(1)) else a.count++
            }
        }
        return map.values.map {
            val value = if (Forwarder.isNumericLike(it.display)) Forwarder.normalizeTarget(it.display) else it.display
            Entry(
                it.display, value,
                "${it.count} پیام · آخرین: ${Template.date(it.last)}",
                Kind.SMS
            )
        }
    }

    // ------------------------------------------------------------ جستجو

    private fun digitsOf(s: String) = Forwarder.normalizeTarget(s).filter { it in '0'..'9' }

    fun matches(e: Entry, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val lq = q.lowercase()
        if (e.title.lowercase().contains(lq) || e.value.lowercase().contains(lq)) return true
        if (!Forwarder.isNumericLike(q)) return false
        val d = digitsOf(q)
        val ed = digitsOf(e.value)
        return ed.contains(d) || (d.length > 1 && d.startsWith("0") && ed.contains(d.drop(1)))
    }
}
