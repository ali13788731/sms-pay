package ir.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(c: Context) {
    private val ctx = c.applicationContext
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /**
     * کلید اصلی؛ وقتی خاموش باشد هیچ قانونی اجرا نمی‌شود.
     * فقط وقتی true است که کاربر روشنش کرده باشد و قفل بروزرسانی اجباری باز باشد.
     * (اشتراک کلید اصلی را قفل نمی‌کند؛ فقط امکانات ویژه محدود می‌شوند.)
     */
    var enabled: Boolean
        get() = masterWanted && UpdateGate.state(ctx) == UpdateGate.State.OK
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** خواسته‌ی خودِ کاربر (کلید روشن است)، بدون در نظر گرفتن قفل‌ها. با برگشتن اشتراک، ارسال خودکار ادامه می‌یابد. */
    val masterWanted: Boolean get() = sp.getBoolean("enabled", false)

    /** حالت نمایش: 0=خودکار (طبق ساعت)، 1=روشن، 2=تاریک */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("themeMode", v).apply()

    // ---------------------------------------------------------------- قانون‌ها

    fun rules(): List<Rule> = synchronized(LOCK) {
        val raw = sp.getString("rules", null) ?: return@synchronized migrateLegacy()
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun rule(id: String): Rule? = rules().firstOrNull { it.id == id }

    /**
     * commit همزمان تا خطای ذخیره‌سازی قابل تشخیص باشد؛ true یعنی ذخیره شد.
     * قاعده: ارسال خودکار فقط وقتی روشن می‌ماند که حداقل یک قانون (فعال یا غیرفعال) وجود داشته باشد؛
     * غیرفعال‌کردن قانون‌ها کلید اصلی را خاموش نمی‌کند.
     */
    fun saveRules(list: List<Rule>, masterOn: Boolean? = null): Boolean = synchronized(LOCK) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val on = (masterOn ?: sp.getBoolean("enabled", false)) && list.isNotEmpty()
        sp.edit().putString("rules", arr.toString()).putBoolean("enabled", on).commit()
    }

    /** اگر شناسه موجود باشد جایگزین می‌شود، وگرنه به انتهای لیست اضافه می‌شود. */
    fun upsertRule(r: Rule, enableMaster: Boolean = false): Boolean = synchronized(LOCK) {
        val all = rules()
        val i = all.indexOfFirst { it.id == r.id }
        val next = if (i >= 0) all.toMutableList().also { it[i] = r } else all + r
        saveRules(next, if (enableMaster) true else null)
    }

    /**
     * خاموش‌کردن ارسال خودکار: کلید اصلی و همه‌ی قانون‌ها با هم غیرفعال می‌شوند.
     * شناسه‌ی قانون‌هایی که فعال بودند نگه داشته می‌شود تا با روشن‌کردن دوباره برگردند.
     */
    fun disableAll(): Boolean = synchronized(LOCK) {
        val all = rules()
        val active = all.filter { it.enabled }.map { it.id }
        val ok = saveRules(all.map { it.copy(enabled = false) }, false)
        if (ok && active.isNotEmpty()) sp.edit().putString("restore_ids", JSONArray(active).toString()).commit()
        ok
    }

    /**
     * روشن‌کردن ارسال خودکار با قانون‌های موجود: قانون‌هایی که قبل از خاموش‌شدن فعال بودند
     * دوباره فعال می‌شوند. اگر هیچ قانونی وجود نداشته باشد false برمی‌گرداند.
     */
    fun enableWithExisting(): Boolean = synchronized(LOCK) {
        val all = rules()
        if (all.isEmpty()) return@synchronized false
        val ids = try {
            val a = JSONArray(sp.getString("restore_ids", "[]"))
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val restored = all.map { if (it.id in ids) it.copy(enabled = true) else it }
        saveRules(restored, true)
    }

    fun deleteRule(id: String): Boolean = synchronized(LOCK) { saveRules(rules().filterNot { it.id == id }) }

    fun setRuleEnabled(id: String, on: Boolean): Boolean = synchronized(LOCK) {
        saveRules(rules().map { if (it.id == id) it.copy(enabled = on) else it })
    }

    /** آیا پنجره‌ی سیستم برای این دسترسی حداقل یک بار نشان داده شده است */
    fun permAsked(permission: String): Boolean = sp.getBoolean("asked_$permission", false)

    fun setPermAsked(permission: String) = sp.edit().putBoolean("asked_$permission", true).apply()

    /** کاربر دسترسی اختیاری را «رد کرد» (بدون نشان‌دادن پنجره‌ی سیستم)؛ در شروع برنامه دوباره نمی‌پرسیم. */
    fun permSkipped(permission: String): Boolean = sp.getBoolean("skipped_$permission", false)

    fun setPermSkipped(permission: String) = sp.edit().putBoolean("skipped_$permission", true).apply()

    // ---------------------------------------------------------------- اشتراک و قانون‌ها

    /** آخرین وضعیت اشتراکی که قانون‌ها بر اساس آن همگام شده‌اند */
    var premiumWas: Boolean
        get() = sp.getBoolean("premium_was", false)
        set(v) = sp.edit().putBoolean("premium_was", v).apply()

    private fun restoreIds(): Set<String> = try {
        val a = JSONArray(sp.getString("restore_ids", "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun putRestoreIds(ids: Collection<String>) {
        sp.edit().putString("restore_ids", JSONArray(ids.toList()).toString()).commit()
    }

    /**
     * پایان اشتراک. فقط قانون‌هایی که (قبل از پایان) فعال بودند بررسی می‌شوند:
     * - قانونی که از امکانات ویژه استفاده می‌کرد غیرفعال و با دلیلش علامت می‌خورد.
     * - اولین قانونِ بدون امکانات ویژه فعال می‌ماند (نسخه‌ی رایگان یک قانون دارد)؛
     *   قانون‌های فعالِ بدون امکانات ویژه‌ی بعدی هم غیرفعال می‌شوند.
     * - قانونی که خود کاربر خاموش کرده بود دست نمی‌خورد.
     * قانونی که فقط به‌خاطر خاموش‌بودن کلید اصلی غیرفعال بود هم «فعال بوده» حساب می‌شود.
     */
    fun applyExpiryGuard(multiSim: Boolean): Boolean = synchronized(LOCK) {
        val all = rules()
        if (all.isEmpty()) return@synchronized true
        val restore = restoreIds()
        val keepRestore = restore.toMutableSet()
        var freeKept = false
        val next = all.map { r ->
            val wasOn = r.enabled || r.id in restore
            if (!wasOn) return@map r
            val u = Premium.uses(r, multiSim)
            when {
                u.isNotEmpty() -> {
                    keepRestore.remove(r.id)
                    r.copy(enabled = false, lock = Rule.LOCK_EXPIRED, lockedUses = u.map { it.code })
                }
                !freeKept -> {
                    freeKept = true
                    r
                }
                else -> {
                    keepRestore.remove(r.id)
                    r.copy(enabled = false, lock = Rule.LOCK_EXPIRED, lockedUses = listOf(Use.EXTRA_RULE.code))
                }
            }
        }
        val ok = saveRules(next)
        if (ok) putRestoreIds(keepRestore)
        ok
    }

    /** تمدید اشتراک: قانون‌هایی که با پایان اشتراک غیرفعال شده بودند برمی‌گردند. */
    fun restoreAfterRenewal(): Boolean = synchronized(LOCK) {
        val all = rules()
        val locked = all.filter { it.lock.isNotEmpty() }
        if (locked.isEmpty()) return@synchronized true
        val masterOn = sp.getBoolean("enabled", false)
        val next = all.map {
            if (it.lock.isEmpty()) it else it.copy(enabled = masterOn, lock = "", lockedUses = emptyList())
        }
        val ok = saveRules(next)
        // اگر کلید اصلی خاموش است، با روشن‌شدنش این قانون‌ها هم روشن شوند
        if (ok && !masterOn) putRestoreIds(restoreIds() + locked.map { it.id })
        ok
    }

    // ---------------------------------------------------------------- اعلان‌ها

    /** اعلان‌های تازه‌ها و پیشنهادها */
    var promoOn: Boolean
        get() = sp.getBoolean("promo_on", true)
        set(v) = sp.edit().putBoolean("promo_on", v).apply()

    /** یادآوری پایان اشتراک */
    var remindersOn: Boolean
        get() = sp.getBoolean("reminders_on", true)
        set(v) = sp.edit().putBoolean("reminders_on", v).apply()

    /** یادآوری «به‌زودی تمام می‌شود»: برای کدام انقضا و کدام آستانه (روز) ارسال شده */
    var remExpiry: Long
        get() = sp.getLong("rem_exp", 0L)
        set(v) = sp.edit().putLong("rem_exp", v).apply()

    var remThreshold: Int
        get() = sp.getInt("rem_t", Int.MAX_VALUE)
        set(v) = sp.edit().putInt("rem_t", v).apply()

    /** آخرین یادآوری «اشتراک تمام شده» و تعداد آن‌ها (برای فاصله‌ی تکرار) */
    var expiredLast: Long
        get() = sp.getLong("exp_last", 0L)
        set(v) = sp.edit().putLong("exp_last", v).apply()

    var expiredCount: Int
        get() = sp.getInt("exp_count", 0)
        set(v) = sp.edit().putInt("exp_count", v).apply()

    /** زمان خریدِ اشتراکی که برایش اعلان «تمام شد» رفته است */
    var expiredNotifiedFor: Long
        get() = sp.getLong("exp_for", 0L)
        set(v) = sp.edit().putLong("exp_for", v).apply()

    /** با خرید/تمدید، شمارنده‌های یادآوری از نو شروع می‌شوند. */
    fun expiredNotifyReset() {
        sp.edit().remove("exp_last").remove("exp_count").remove("exp_for")
            .remove("rem_exp").remove("rem_t").apply()
    }

    var promoLast: Long
        get() = sp.getLong("promo_last", 0L)
        set(v) = sp.edit().putLong("promo_last", v).apply()

    var promoIndex: Int
        get() = sp.getInt("promo_idx", 0)
        set(v) = sp.edit().putInt("promo_idx", v).apply()

    // ---------------------------------------------------------------- نسخه‌ی اختیاری (غیراجباری)

    /** نسخه‌ی جدیدتری که منتشر شده ولی نصبش اجباری نیست؛ برای اعلان «با بروزرسانی این امکانات اضافه می‌شود» */
    var newsCode: Int
        get() = sp.getInt("news_code", 0)
        set(v) = sp.edit().putInt("news_code", v).apply()

    val newsName: String get() = sp.getString("news_name", "") ?: ""
    val newsUrl: String get() = sp.getString("news_url", "") ?: ""
    val newsChanges: List<String>
        get() = try {
            val a = JSONArray(sp.getString("news_changes", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

    fun saveNews(code: Int, name: String, url: String, changes: List<String>) {
        sp.edit().putInt("news_code", code).putString("news_name", name).putString("news_url", url)
            .putString("news_changes", JSONArray(changes).toString()).apply()
    }

    fun clearNews() {
        sp.edit().remove("news_code").remove("news_name").remove("news_url").remove("news_changes").apply()
    }

    // ---------------------------------------------------------------- بروزرسانی اجباری

    /** کمترین نسخه‌ای که از این به بعد اجازه‌ی کار دارد (از سرور گرفته و ذخیره شده است) */
    var reqCode: Int
        get() = sp.getInt("upd_code", 0)
        set(v) = sp.edit().putInt("upd_code", v).apply()

    val reqName: String get() = sp.getString("upd_name", "") ?: ""
    val reqReason: String get() = sp.getString("upd_reason", "") ?: ""
    val reqUrl: String get() = sp.getString("upd_url", "") ?: ""
    val reqChanges: List<String>
        get() = try {
            val a = JSONArray(sp.getString("upd_changes", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

    /** نسخه‌ای که برایش اعلان فرستاده شده، تا اعلان تکراری نرود */
    var notifiedCode: Int
        get() = sp.getInt("upd_notified", 0)
        set(v) = sp.edit().putInt("upd_notified", v).apply()

    fun saveRequiredUpdate(code: Int, name: String, reason: String, url: String, changes: List<String>) {
        sp.edit()
            .putInt("upd_code", code)
            .putString("upd_name", name)
            .putString("upd_reason", reason)
            .putString("upd_url", url)
            .putString("upd_changes", JSONArray(changes).toString())
            .commit()
    }

    fun clearRequiredUpdate() {
        sp.edit().remove("upd_code").remove("upd_name").remove("upd_reason")
            .remove("upd_url").remove("upd_changes").apply()
    }

    /** آخرین بررسی موفق نسخه (میلی‌ثانیه) */
    var lastOk: Long
        get() = sp.getLong("upd_last_ok", 0L)
        set(v) = sp.edit().putLong("upd_last_ok", v).apply()

    /** آخرین تلاش برای بررسی، موفق یا ناموفق */
    var lastTry: Long
        get() = sp.getLong("upd_last_try", 0L)
        set(v) = sp.edit().putLong("upd_last_try", v).apply()

    var firstRun: Long
        get() = sp.getLong("first_run", 0L)
        set(v) = sp.edit().putLong("first_run", v).apply()

    /** بیشترین زمانی که ساعت گوشی دیده شده؛ برای تشخیص عقب‌بردن ساعت */
    var lastSeen: Long
        get() = sp.getLong("last_seen", 0L)
        set(v) = sp.edit().putLong("last_seen", v).apply()

    fun markCheckOk(now: Long) {
        sp.edit().putLong("upd_last_ok", now).putLong("upd_last_try", now).putLong("last_seen", now).commit()
    }

    /** تنظیمات نسخه‌های قبل (یک شماره، یک فیلتر) به یک قانون تبدیل می‌شود. */
    private fun migrateLegacy(): List<Rule> {
        val target = sp.getString("target", "") ?: ""
        val list = if (target.isBlank()) emptyList() else listOf(
            Rule(
                name = ctx.getString(R.string.rule_default_name, 1),
                recvSim = sp.getInt("recvSim", 0).coerceIn(0, 2),
                allSenders = sp.getInt("filterMode", 0) != 1,
                senders = Forwarder.parseList(sp.getString("filterList", "") ?: ""),
                targets = listOf(target),
                sendSim = sp.getInt("sendSim", 0).coerceIn(0, 2),
                template = sp.getString("template", null)
                    ?: if (sp.getBoolean("header", true)) Template.DEFAULT else "{body}"
            )
        )
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("rules", arr.toString())
            .remove("target").remove("recvSim").remove("filterMode").remove("filterList")
            .remove("sendSim").remove("template").remove("header")
            .apply()
        return list
    }

    // ---------------------------------------------------------------- گزارش
    // فقط فرستنده، زمان و وضعیت ذخیره می‌شود؛ متن پیام‌ها هرگز ذخیره نمی‌شود.
    // گزارش‌ها در پایگاه‌داده‌ی SQLite نگه‌داری می‌شوند تا صفحه‌بندی و جستجو سریع باشد ([LogDb]).

    fun addLog(sender: String, ok: Boolean, note: String) {
        try {
            LogDb.get(ctx).add(sender, ok, note)
        } catch (e: Exception) {
            // ثبت گزارش نباید ارسال پیامک را خراب کند
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
