package ir.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * دو طرح اشتراک (شناسه‌ی محصول هر کدام در build.gradle.kts). عنوان، توضیح، مبلغ و مدت هر طرح
 * از چیزی که در پیشخان کافه‌بازار تعریف شده خوانده می‌شود ([PlanCatalog]) و فقط تا وقتی
 * هیچ‌بار از بازار خوانده نشده مقدار پیش‌فرض نمایش داده می‌شود.
 */
enum class Plan(private val defaultPrice: Long, private val defaultDays: Int) {
    MONTHLY(40_000L, 30),
    YEARLY(200_000L, 365);

    /** شناسه‌ی محصول در پیشخان بازار */
    val sku: String get() = if (this == MONTHLY) BuildConfig.SKU_MONTHLY else BuildConfig.SKU_YEARLY

    internal val fallbackPrice: Long get() = defaultPrice
    internal val fallbackDays: Int get() = defaultDays

    fun info(c: Context): PlanCatalog.Info = PlanCatalog.info(c, this)

    /** عنوان طرح، همان که در بازار نوشته شده */
    fun title(c: Context): String = info(c).title

    /** مدت طرح به متن، مثلاً «۳۰ روز» */
    fun duration(c: Context): String = c.getString(R.string.sub_days, Fa.num(info(c).days))

    fun days(c: Context): Int = info(c).days

    /** مبلغ به صورت عدد (تومان)؛ ۰ یعنی خوانده نشد */
    fun price(c: Context): Long = info(c).price

    /** مبلغ به همان شکلی که بازار نشان می‌دهد */
    fun priceText(c: Context): String = info(c).priceText

    companion object {
        fun bySku(sku: String): Plan? = values().firstOrNull { it.sku == sku }

        /** صرفه‌جویی طرح سالانه نسبت به ماهانه (بر اساس قیمت هر روز) به درصد؛ null اگر صرفه‌ای نیست */
        fun yearlySavingPercent(c: Context): Int? {
            val y = YEARLY.info(c)
            val m = MONTHLY.info(c)
            if (y.price <= 0 || m.price <= 0 || y.days <= m.days) return null
            val monthlyForSamePeriod = m.price.toDouble() * y.days / m.days
            val pct = (100 - y.price * 100 / monthlyForSamePeriod).toInt()
            return pct.takeIf { it in 1..99 }
        }

        /** هزینه‌ی هر ماه (۳۰ روز) در طرح سالانه؛ ۰ یعنی نامشخص */
        fun yearlyPerMonth(c: Context): Long {
            val y = YEARLY.info(c)
            return if (y.price <= 0 || y.days <= 0) 0L else y.price * 30 / y.days
        }

        /** «عنوان مبلغ» همه‌ی طرح‌ها، برای متن‌های معرفی */
        fun summary(c: Context): String =
            values().joinToString(" یا ") { it.title(c) + " " + it.priceText(c) }
    }
}

/**
 * اطلاعات طرح‌ها به‌صورت خوانده‌شده از کافه‌بازار (Poolakey ← getSubscriptionSkuDetails).
 * آخرین مقدار خوانده‌شده در حافظه‌ی گوشی نگه داشته می‌شود تا بدون اینترنت هم نمایش داده شود.
 * بازار فیلد جداگانه‌ای برای «مدت» نمی‌دهد؛ مدت از عنوان/توضیح طرح در پیشخان بازار (مثلاً «۳۰ روزه»،
 * «ماهانه»، «سالانه»، «۳ ماهه») خوانده می‌شود و اگر چیزی پیدا نشد مدت پیش‌فرض همان طرح می‌ماند.
 */
object PlanCatalog {
    class Info(
        val sku: String,
        val title: String,
        val description: String,
        val priceText: String,
        val price: Long,
        val days: Int,
        val fromBazaar: Boolean
    )

    private const val FILE = "plans"
    private const val FRESH_MS = 6L * 60 * 60 * 1000
    private const val RETRY_GAP_MS = 10L * 60 * 1000
    private var lastTry = 0L

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun fallback(c: Context, plan: Plan): Info {
        val title = c.getString(if (plan == Plan.MONTHLY) R.string.plan_monthly else R.string.plan_yearly)
        return Info(plan.sku, title, "", Fa.toman(plan.fallbackPrice), plan.fallbackPrice, plan.fallbackDays, false)
    }

    fun info(c: Context, plan: Plan): Info {
        val raw = sp(c).getString("p_" + plan.name, null) ?: return fallback(c, plan)
        return try {
            val o = JSONObject(raw)
            val fb = fallback(c, plan)
            Info(
                plan.sku,
                o.optString("title").ifBlank { fb.title },
                o.optString("desc"),
                o.optString("priceText").ifBlank { fb.priceText },
                o.optLong("price", 0L),
                o.optInt("days", 0).takeIf { it > 0 } ?: plan.fallbackDays,
                true
            )
        } catch (e: Exception) {
            fallback(c, plan)
        }
    }

    /** نتیجه‌ی خواندن از بازار ذخیره می‌شود */
    fun store(c: Context, items: List<Pair<Plan, BazaarSku>>) {
        if (items.isEmpty()) return
        val e = sp(c).edit()
        items.forEach { (plan, d) ->
            val days = parseDays(d.title + " " + d.description) ?: plan.fallbackDays
            val o = JSONObject()
                .put("title", d.title.trim())
                .put("desc", d.description.trim())
                .put("priceText", Fa.digits(Fa.latinDigits(d.price.trim())))
                .put("price", parsePrice(d.price))
                .put("days", days)
            e.putString("p_" + plan.name, o.toString())
        }
        e.putLong("t", System.currentTimeMillis()).apply()
    }

    /** آیا اطلاعات تازه‌ای داریم و لازم نیست دوباره از بازار بپرسیم؟ */
    fun isFresh(c: Context): Boolean {
        val t = sp(c).getLong("t", 0L)
        return t > 0 && System.currentTimeMillis() - t in 0 until FRESH_MS
    }

    /** برای جلوگیری از تلاش پشت‌سرهم وقتی بازار در دسترس نیست */
    fun shouldTry(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < RETRY_GAP_MS) return false
        lastTry = now
        return true
    }

    /** «۴۰٬۰۰۰ تومان» ← ۴۰۰۰۰ (تومان). اگر مبلغ به ریال نوشته شده باشد به تومان برمی‌گردد. */
    fun parsePrice(text: String): Long {
        val latin = Fa.latinDigits(text)
        val cleaned = latin.replace(Regex("[,٬،.\\s\u200c]"), "")
        val n = Regex("\\d+").find(cleaned)?.value?.toLongOrNull() ?: return 0L
        return if (cleaned.contains("ریال") || cleaned.lowercase().contains("rial")) n / 10 else n
    }

    /** مدت (روز) از متن عنوان/توضیح طرح؛ null اگر چیزی پیدا نشد */
    fun parseDays(text: String): Int? {
        val t = Fa.latinDigits(text).replace('\u200c', ' ').lowercase()
        Regex("(\\d+)\\s*(روز|day)").find(t)?.let { return it.groupValues[1].toInt() }
        Regex("(\\d+)\\s*(هفته|week)").find(t)?.let { return it.groupValues[1].toInt() * 7 }
        Regex("(\\d+)\\s*(ماه|month)").find(t)?.let {
            val n = it.groupValues[1].toInt()
            return if (n == 12) 365 else n * 30
        }
        Regex("(\\d+)\\s*(سال|year)").find(t)?.let { return it.groupValues[1].toInt() * 365 }
        val words = listOf(
            "یک هفته" to 7, "هفتگی" to 7, "weekly" to 7,
            "دو ماه" to 60, "سه ماه" to 90, "چهار ماه" to 120, "شش ماه" to 180,
            "یک ماه" to 30, "ماهانه" to 30, "ماهیانه" to 30, "monthly" to 30,
            "دو سال" to 730, "یک سال" to 365, "سالانه" to 365, "سالیانه" to 365, "yearly" to 365, "annual" to 365
        )
        return words.firstOrNull { t.contains(it.first) }?.second
    }
}

/** جزئیات یک محصول اشتراک آنچنان که بازار می‌دهد */
class BazaarSku(val sku: String, val title: String, val description: String, val price: String)

/** امکانات ویژه‌ی اشتراک؛ هم در پنجره‌ی پریمیوم و هم در «درباره» نمایش داده می‌شوند. */
enum class Feature(val icon: Int, val title: Int, val desc: Int, val hint: Int) {
    MULTI_RULE(R.drawable.ic_rules, R.string.feat_rules_title, R.string.feat_rules_desc, R.string.feat_rules_hint),
    SIM_RECV(R.drawable.ic_sim, R.string.feat_simrecv_title, R.string.feat_simrecv_desc, R.string.feat_simrecv_hint),
    SIM_SEND(R.drawable.ic_send, R.string.feat_simsend_title, R.string.feat_simsend_desc, R.string.feat_simsend_hint),
    SENDERS(R.drawable.ic_label, R.string.feat_senders_title, R.string.feat_senders_desc, R.string.feat_senders_hint),
    TARGETS(R.drawable.ic_group, R.string.feat_targets_title, R.string.feat_targets_desc, R.string.feat_targets_hint),
    TEMPLATE(R.drawable.ic_template, R.string.feat_template_title, R.string.feat_template_desc, R.string.feat_template_hint),
    EDIT_RULE(R.drawable.ic_edit, R.string.feat_edit_title, R.string.feat_edit_desc, R.string.feat_edit_hint),
    TOGGLE(R.drawable.ic_toggle, R.string.feat_toggle_title, R.string.feat_toggle_desc, R.string.feat_toggle_hint),
    LOG(R.drawable.ic_history, R.string.feat_log_title, R.string.feat_log_desc, R.string.feat_log_hint)
}

/** چیزهایی که یک قانون می‌تواند از امکانات ویژه استفاده کند (برای غیرفعال‌سازی هنگام پایان اشتراک). */
enum class Use(val code: String, val label: Int) {
    SIM_RECV("sim_recv", R.string.use_sim_recv),
    SIM_SEND("sim_send", R.string.use_sim_send),
    SENDERS("senders", R.string.use_senders),
    MULTI_TARGET("multi_target", R.string.use_multi_target),
    TEMPLATE("template", R.string.use_template),
    EXTRA_RULE("extra_rule", R.string.use_extra_rule);

    companion object {
        fun byCode(code: String): Use? = values().firstOrNull { it.code == code }
    }
}

/** دلیلی که یک قانون در نسخه‌ی رایگان اجرا نمی‌شود. */
class Block(val kind: Kind, val uses: List<Use>) {
    enum class Kind {
        /** با پایان اشتراک غیرفعال شد (چون از امکانات ویژه استفاده می‌کرد) */
        EXPIRED,
        /** از امکانات ویژه استفاده می‌کند و بدون اشتراک اجرا نمی‌شود */
        PREMIUM_USE,
        /** در نسخه‌ی رایگان فقط یک قانون اجرا می‌شود و این قانون دومی است */
        EXTRA_RULE,
        /** قبلاً (با اشتراک) خاموش شده و روشن‌کردنش نیاز به اشتراک دارد */
        USER_OFF
    }
}

/**
 * قوانین نسخه‌ی رایگان و پریمیوم. همه‌ی بررسی‌ها (رابط، ذخیره، دریافت پیامک) از همین‌جا می‌گذرند
 * تا یک منبع حقیقت باشد.
 */
object Premium {
    const val FREE_MAX_RULES = 1
    const val FREE_MAX_TARGETS = 1

    /** نسخه‌ی رایگان فقط «همه‌ی فرستنده‌ها» دارد؛ فرستنده‌ی مشخص مخصوص اشتراک است. */
    const val FREE_SPECIFIC_SENDERS = false

    fun isActive(c: Context): Boolean = LicenseGate.state(c) == LicenseGate.State.OK

    /** کدام امکانات ویژه در این قانون استفاده شده است. (نام‌گذاری و روشن/خاموش‌کردن «ظاهری» حساب نمی‌شوند.) */
    fun uses(rule: Rule, multiSim: Boolean): Set<Use> {
        val out = LinkedHashSet<Use>()
        val simRecvPremium = if (multiSim) rule.recvSim != 1 else rule.recvSim == 2
        if (simRecvPremium) out.add(Use.SIM_RECV)
        if (rule.sendSim != 0) out.add(Use.SIM_SEND)
        if (!rule.allSenders && !FREE_SPECIFIC_SENDERS) out.add(Use.SENDERS)
        if (rule.targets.size > FREE_MAX_TARGETS) out.add(Use.MULTI_TARGET)
        if (!Template.isDefault(rule.template)) out.add(Use.TEMPLATE)
        return out
    }

    /**
     * قانون‌هایی که همین حالا واقعاً اجرا می‌شوند. با اشتراک: همه‌ی فعال‌ها؛
     * بدون اشتراک: فقط اولین قانونِ فعالی که از امکانات ویژه استفاده نمی‌کند.
     */
    fun runnable(c: Context, rules: List<Rule>): List<Rule> {
        val enabled = rules.filter { it.enabled && it.targets.isNotEmpty() }
        if (isActive(c)) return enabled
        val multi = SimInfo.multi(c)
        return enabled.filter { uses(it, multi).isEmpty() }.take(FREE_MAX_RULES)
    }

    /** چرا این قانون (در نسخه‌ی رایگان) اجرا نمی‌شود؟ null = اجرا می‌شود یا اشتراک فعال است. */
    fun blockOf(c: Context, rule: Rule, all: List<Rule>, premium: Boolean, multi: Boolean): Block? {
        if (premium) return null
        if (rule.lock == Rule.LOCK_EXPIRED) {
            return Block(Block.Kind.EXPIRED, rule.lockedUses.mapNotNull { Use.byCode(it) })
        }
        val u = uses(rule, multi)
        if (u.isNotEmpty()) return Block(Block.Kind.PREMIUM_USE, u.toList())
        if (!rule.enabled) return Block(Block.Kind.USER_OFF, emptyList())
        val first = all.firstOrNull { it.enabled && uses(it, multi).isEmpty() }
        if (first != null && first.id != rule.id) return Block(Block.Kind.EXTRA_RULE, listOf(Use.EXTRA_RULE))
        return null
    }

    /**
     * همگام‌کردن قانون‌ها با وضعیت اشتراک. وقتی اشتراک تمام می‌شود قانون‌هایی که از امکانات ویژه
     * استفاده می‌کردند غیرفعال می‌شوند؛ وقتی دوباره اشتراک فعال شود برمی‌گردند.
     * ارزان و بی‌خطر است و می‌تواند هر بار صدا زده شود.
     */
    fun reconcile(c: Context) {
        val p = Prefs(c)
        val now = isActive(c)
        if (now == p.premiumWas) return
        if (now) p.restoreAfterRenewal() else p.applyExpiryGuard(SimInfo.multi(c))
        p.premiumWas = now
    }
}

/**
 * اطلاعات اشتراک برای نمایش: نوع طرح، تاریخ خرید، انقضا، مبلغ و تاریخچه.
 * (وضعیت «فعال بودن» جای دیگری و با امضای Keystore نگه‌داری می‌شود؛ این‌ها فقط برای نمایش‌اند.)
 *
 * کافه‌بازار تاریخ انقضا را به برنامه نمی‌دهد؛ انقضا از «زمان خرید + مدت طرح» حساب می‌شود،
 * و مدت و مبلغ طرح از تعریف طرح در پیشخان بازار ([PlanCatalog]) می‌آید.
 */
object SubscriptionStore {

    /** [days] مدت طرح هنگام خرید (از بازار) تا تغییر بعدی تعریف طرح، تاریخ انقضای قبلی را عوض نکند */
    /** [expiresAt] تاریخ پایانی که بازار اعلام کرده (۰ = اعلام نشده؛ آن‌وقت از زمان خرید + مدت حساب می‌شود) */
    class Current(
        val plan: Plan, val purchaseTime: Long, val orderId: String, val endedAt: Long, val days: Int,
        val expiresAt: Long = 0L
    ) {
        val ended: Boolean get() = endedAt > 0L
    }

    class Event(val type: String, val time: Long, val plan: Plan, val price: Long, val orderId: String) {
        companion object {
            const val PURCHASE = "purchase"
            const val RENEW = "renew"
            const val ENDED = "ended"
        }
    }

    private const val FILE = "sub"
    private const val MAX_EVENTS = 100

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun current(c: Context): Current? {
        val raw = sp(c).getString("cur", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val plan = Plan.valueOf(o.getString("plan"))
            Current(plan, o.getLong("t"), o.optString("order"), o.optLong("ended", 0L),
                o.optInt("days", 0).takeIf { it > 0 } ?: plan.fallbackDays, o.optLong("exp", 0L))
        } catch (e: Exception) {
            null
        }
    }

    private fun save(c: Context, cur: Current) {
        val o = JSONObject().put("plan", cur.plan.name).put("t", cur.purchaseTime)
            .put("order", cur.orderId).put("ended", cur.endedAt).put("days", cur.days).put("exp", cur.expiresAt)
        sp(c).edit().putString("cur", o.toString()).apply()
    }

    fun events(c: Context): List<Event> {
        val raw = sp(c).getString("hist", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull {
                val o = a.getJSONObject(it)
                try {
                    Event(o.getString("type"), o.getLong("t"), Plan.valueOf(o.getString("plan")), o.optLong("price"), o.optString("order"))
                } catch (e: Exception) {
                    null
                }
            }.sortedByDescending { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addEvent(c: Context, e: Event) {
        val all = events(c)
        // یک رویداد با همین نوع و زمان و سفارش دوباره ثبت نمی‌شود (مثلاً بعد از هر همگام‌سازی با بازار)
        if (all.any { it.type == e.type && it.time == e.time && it.orderId == e.orderId }) return
        val arr = JSONArray()
        (all + e).sortedByDescending { it.time }.take(MAX_EVENTS).forEach {
            arr.put(JSONObject().put("type", it.type).put("t", it.time).put("plan", it.plan.name)
                .put("price", it.price).put("order", it.orderId))
        }
        sp(c).edit().putString("hist", arr.toString()).apply()
    }

    /** بازار می‌گوید اشتراک فعال است (بعد از خرید، تمدید، یا بازیابی بعد از نصب مجدد). */
    fun onActive(c: Context, plan: Plan, purchaseTime: Long, orderId: String, expiresAt: Long = 0L) {
        val cur = current(c)
        val isNew = cur == null || cur.ended || cur.orderId != orderId || cur.purchaseTime != purchaseTime
        if (isNew) {
            val renewal = cur != null && !cur.ended && cur.plan == plan && purchaseTime > cur.purchaseTime
            addEvent(
                c,
                Event(if (renewal) Event.RENEW else Event.PURCHASE, purchaseTime, plan, plan.price(c), orderId)
            )
            save(c, Current(plan, purchaseTime, orderId, 0L, plan.days(c), expiresAt))
            Prefs(c).expiredNotifyReset()
        } else if (cur != null && expiresAt > 0L && cur.expiresAt != expiresAt) {
            // همان خرید، ولی بازار تاریخ پایان را عوض کرده یا تازه اعلام کرده
            save(c, Current(cur.plan, cur.purchaseTime, cur.orderId, 0L, cur.days, expiresAt))
        }
    }

    /** بازار می‌گوید اشتراکی فعال نیست. */
    fun onInactive(c: Context) {
        val cur = current(c) ?: return
        if (cur.ended) return
        val now = System.currentTimeMillis()
        val planned = if (cur.expiresAt > 0L) cur.expiresAt else cur.purchaseTime + cur.days * Fa.DAY
        val endedAt = if (planned in 1..now) planned else now
        addEvent(c, Event(Event.ENDED, endedAt, cur.plan, 0L, cur.orderId))
        save(c, Current(cur.plan, cur.purchaseTime, cur.orderId, endedAt, cur.days, cur.expiresAt))
    }

    /**
     * تاریخ انقضا. اگر اشتراک هنوز فعال است ولی «زمان خرید + مدت» گذشته باشد (تمدید خودکار بازار)،
     * به دوره‌ی بعدی منتقل می‌شود.
     */
    fun expiry(cur: Current, premiumActive: Boolean, now: Long = System.currentTimeMillis()): Long {
        if (cur.ended) return cur.endedAt
        if (cur.expiresAt > 0L) return cur.expiresAt // دقیقاً همان که بازار گفته
        val period = cur.days * Fa.DAY
        var exp = cur.purchaseTime + period
        if (premiumActive) while (exp <= now) exp += period
        return exp
    }
}
