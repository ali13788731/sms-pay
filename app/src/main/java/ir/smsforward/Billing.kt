package ir.smsforward

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.ConnectionState
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** اطلاعات یک اشتراک فعال که بازار برمی‌گرداند */
/**
 * اطلاعات یک اشتراک فعال که بازار برمی‌گرداند. [expiresAt] تاریخ پایان اشتراک است، دقیقاً همان که بازار
 * در داده‌ی خرید می‌فرستد (۰ = بازار در این پاسخ تاریخ پایان نداده است).
 */
class SubInfo(val plan: Plan, val purchaseTime: Long, val orderId: String, val expiresAt: Long = 0L)

/** خواندن تاریخ پایان اشتراک از متن خام خرید بازار (کلیدهای شبیه expiry / validUntil / endTime) */
object BazaarExpiry {
    /** متن JSON خام خرید؛ با reflection تا اگر نسخه‌ی کتابخانه فیلد را نداشت بیلد خراب نشود */
    fun rawJson(purchase: Any): String = try {
        purchase.javaClass.getMethod("getOriginalJson").invoke(purchase) as? String ?: ""
    } catch (e: Exception) {
        ""
    }

    fun parse(json: String, purchaseTime: Long): Long {
        if (json.isBlank()) return 0L
        return try {
            find(org.json.JSONObject(json), purchaseTime)
        } catch (e: Exception) {
            0L
        }
    }

    private fun find(o: org.json.JSONObject, purchaseTime: Long): Long {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val lk = k.lowercase()
            val v = o.opt(k)
            if (v is org.json.JSONObject) {
                val n = find(v, purchaseTime)
                if (n > 0) return n
                continue
            }
            if (!(lk.contains("expir") || lk.contains("validuntil") || lk.contains("valid_until") ||
                    lk.contains("enddate") || lk.contains("end_date") || lk.contains("endtime") || lk.contains("end_time"))) continue
            val n = toMillis(v)
            if (n > purchaseTime) return n
        }
        return 0L
    }

    private fun toMillis(v: Any?): Long {
        val n = when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: parseDate(v)
            else -> 0L
        }
        if (n <= 0L) return 0L
        return if (n < 100_000_000_000L) n * 1000 else n // ثانیه یا میلی‌ثانیه
    }

    private fun parseDate(s: String): Long {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss")
        for (p in patterns) {
            try {
                val f = java.text.SimpleDateFormat(p, java.util.Locale.US)
                if (p.endsWith("'Z'")) f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return (f.parse(s.trim())?.time ?: continue) / 1000 // به ثانیه برمی‌گردد تا toMillis یکسان رفتار کند
            } catch (e: Exception) {
                // الگوی بعدی
            }
        }
        return 0L
    }
}

/**
 * پوشش نازک روی Poolakey. همه‌ی callbackها روی نخ اصلی می‌آیند و هر کدام حداکثر یک بار صدا زده می‌شوند.
 * تأیید امضای RSA پاسخ‌های بازار (جلوگیری از پاسخ جعلی) توسط خود Poolakey انجام می‌شود.
 */
class BazaarBilling(private val context: Context) {

    sealed class Query {
        class Active(val info: SubInfo) : Query()
        object Inactive : Query()
        class Error(val cause: Throwable?) : Query()
    }

    interface SubscribeCallback {
        fun onSuccess(info: SubInfo)
        fun onCancel()
        fun onFail()
    }

    private val main = Handler(Looper.getMainLooper())
    private var connection: Connection? = null
    private var payment: Payment? = null

    val keyMissing: Boolean get() = BuildConfig.BAZAAR_RSA_KEY.isBlank()

    /** یک بار مصرف + مهلت: اگر بازار پاسخ ندهد بعد از [TIMEOUT_MS] خطا اعلام می‌شود. */
    private inner class Once(onTimeout: () -> Unit) {
        private val fired = AtomicBoolean(false)
        private val timer = Runnable { if (fired.compareAndSet(false, true)) onTimeout() }

        init { main.postDelayed(timer, TIMEOUT_MS) }

        /** true یعنی نوبتِ شما بود؛ تنها یک بار true می‌دهد */
        fun take(): Boolean {
            val ok = fired.compareAndSet(false, true)
            if (ok) main.removeCallbacks(timer)
            return ok
        }
    }

    fun connect(onReady: () -> Unit, onFail: (Throwable) -> Unit) {
        if (keyMissing) {
            onFail(IllegalStateException("BAZAAR_RSA_KEY is empty"))
            return
        }
        val p = Payment(
            context = context,
            config = PaymentConfiguration(
                localSecurityCheck = SecurityCheck.Enable(rsaPublicKey = BuildConfig.BAZAAR_RSA_KEY)
            )
        )
        payment = p
        val once = Once { onFail(IllegalStateException("connect timeout")) }
        connection = p.connect {
            connectionSucceed { if (once.take()) onReady() }
            connectionFailed { e -> if (once.take()) onFail(e) }
            disconnected { }
        }
    }

    fun disconnect() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // قبلاً قطع شده
        }
        connection = null
        payment = null
    }

    private fun ready(): Payment? =
        if (connection?.getState() == ConnectionState.Connected) payment else null

    /**
     * آیا اشتراک این حساب بازار در همین لحظه فعال است؟ اگر هر دو طرح موجود باشد، جدیدترین خرید برمی‌گردد.
     * این همان «بازیابی داده‌ی قبلی» است: بعد از نصب مجدد یا گوشی دیگر، تاریخ خرید و طرح از بازار برمی‌گردد.
     */
    fun querySubscription(cb: (Query) -> Unit) {
        val p = ready() ?: return cb(Query.Error(IllegalStateException("not connected")))
        val once = Once { cb(Query.Error(IllegalStateException("query timeout"))) }
        p.getSubscribedProducts {
            querySucceed { list ->
                if (once.take()) {
                    val mine = list.mapNotNull { pi ->
                        Plan.bySku(pi.productId)?.let { SubInfo(it, pi.purchaseTime, pi.orderId, BazaarExpiry.parse(BazaarExpiry.rawJson(pi), pi.purchaseTime)) }
                    }
                    val best = mine.maxByOrNull { it.purchaseTime }
                    cb(if (best != null) Query.Active(best) else Query.Inactive)
                }
            }
            queryFailed { e -> if (once.take()) cb(Query.Error(e)) }
        }
    }

    /**
     * عنوان، توضیح و مبلغ طرح‌ها را از پیشخان بازار می‌خواند (همان چیزی که برای محصول اشتراک
     * در بازار تعریف شده). در صورت خطا یا مهلت، null برمی‌گردد و مقدار قبلی می‌ماند.
     */
    fun queryPlans(cb: (List<Pair<Plan, BazaarSku>>?) -> Unit) {
        val p = ready() ?: return cb(null)
        val once = Once { cb(null) }
        try {
            p.getSubscriptionSkuDetails(Plan.values().map { it.sku }) {
                getSkuDetail { list ->
                    if (once.take()) {
                        cb(list.mapNotNull { d ->
                            Plan.bySku(d.sku)?.let { it to BazaarSku(d.sku, d.title, d.description, d.price) }
                        })
                    }
                }
                getSkuDetailFailed { if (once.take()) cb(null) }
            }
        } catch (e: Exception) {
            if (once.take()) cb(null)
        }
    }

    /** صفحه‌ی پرداخت بازار را برای طرح انتخاب‌شده باز می‌کند. */
    fun subscribe(registry: ActivityResultRegistry, plan: Plan, payload: String, cb: SubscribeCallback) {
        val p = ready() ?: return cb.onFail()
        val once = AtomicBoolean(false)
        fun one(block: () -> Unit) { if (once.compareAndSet(false, true)) block() }
        p.subscribeProduct(
            registry = registry,
            request = PurchaseRequest(productId = plan.sku, payload = payload)
        ) {
            purchaseFlowBegan { }
            failedToBeginFlow { one { cb.onFail() } }
            purchaseSucceed { info ->
                one {
                    val bought = Plan.bySku(info.productId)
                    if (bought != null) cb.onSuccess(SubInfo(bought, info.purchaseTime, info.orderId, BazaarExpiry.parse(BazaarExpiry.rawJson(info), info.purchaseTime))) else cb.onFail()
                }
            }
            purchaseCanceled { one { cb.onCancel() } }
            purchaseFailed { one { cb.onFail() } }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}

/** بررسی اشتراک در بازار و به‌روزکردن وضعیت محلی؛ برای صفحه‌ی اصلی، کار پس‌زمینه و غیره. */
object LicenseManager {
    enum class Outcome { ACTIVE, INACTIVE, ERROR }

    private val main = Handler(Looper.getMainLooper())

    /** فقط روی نخ اصلی به آن دسترسی داریم */
    private var running = false
    private var lastTry = 0L
    private const val RETRY_GAP_MS = 10L * 60 * 1000

    fun bazaarInstalled(c: Context): Boolean = try {
        c.packageManager.getPackageInfo("com.farsitel.bazaar", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    fun refresh(c: Context, done: (Outcome) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refresh(c, done) }
            return
        }
        val app = c.applicationContext
        if (running) {
            done(Outcome.ERROR) // بررسی دیگری در جریان است
            return
        }
        running = true
        val billing = BazaarBilling(app)
        fun finish(o: Outcome, info: SubInfo?) {
            running = false
            billing.disconnect()
            record(app, o, info)
            done(o)
        }
        billing.connect(
            onReady = {
                // اول مبلغ/مدت طرح‌ها از بازار (اگر نشد، مقدار قبلی می‌ماند)، بعد وضعیت اشتراک
                billing.queryPlans { plans ->
                    if (plans != null) PlanCatalog.store(app, plans)
                    billing.querySubscription { q ->
                        when (q) {
                            is BazaarBilling.Query.Active -> finish(Outcome.ACTIVE, q.info)
                            is BazaarBilling.Query.Inactive -> finish(Outcome.INACTIVE, null)
                            is BazaarBilling.Query.Error -> finish(Outcome.ERROR, null)
                        }
                    }
                }
            },
            onFail = { finish(Outcome.ERROR, null) }
        )
    }

    private var plansRunning = false

    /**
     * فقط طرح‌ها (عنوان، توضیح، مبلغ، مدت) را از بازار می‌خواند و ذخیره می‌کند؛ مثلاً هنگام باز شدن
     * پنجره‌ی خرید. اگر اطلاعات تازه داریم یا بازار نصب نیست کاری نمی‌کند. [done] فقط بعد از یک تلاش واقعی
     * روی نخ اصلی صدا زده می‌شود تا صفحه دوباره رسم شود.
     */
    fun refreshPlans(c: Context, force: Boolean = false, done: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refreshPlans(c, force, done) }
            return
        }
        val app = c.applicationContext
        if (plansRunning || !bazaarInstalled(app)) return
        if (!force && (PlanCatalog.isFresh(app) || !PlanCatalog.shouldTry())) return
        plansRunning = true
        val billing = BazaarBilling(app)
        fun finish(list: List<Pair<Plan, BazaarSku>>?) {
            plansRunning = false
            billing.disconnect()
            if (list != null) {
                PlanCatalog.store(app, list)
                done()
            }
        }
        billing.connect(
            onReady = { billing.queryPlans { finish(it) } },
            onFail = { finish(null) }
        )
    }

    /**
     * همگام‌سازی بی‌صدا با بازار: اگر تا حالا هیچ وضعیتی ثبت نشده (اولین اجرا یا نصب مجدد) یا
     * از آخرین تأیید مدتی گذشته، از بازار می‌پرسد و اطلاعات قبلی اشتراک (طرح، تاریخ خرید) برمی‌گردد.
     * [onChanged] بعد از هر بررسی صدا زده می‌شود.
     */
    fun sync(c: Context, onChanged: () -> Unit) {
        Premium.reconcile(c)
        val s = LicenseStore.read(c)
        if (s != null) {
            val age = LicenseStore.ageMs(c, s)
            if (age != null && age < LicenseGate.REFRESH_EVERY_MS) return
        }
        // اگر بازار در دسترس نیست هر بار که صفحه باز می‌شود دوباره تلاش نکن
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < RETRY_GAP_MS) return
        lastTry = now
        refresh(c) { onChanged() }
    }

    /** بعد از یک خرید موفق (پاسخ بازار قبلاً با امضای RSA توسط Poolakey تأیید شده است) */
    fun onPurchased(c: Context, info: SubInfo) {
        val app = c.applicationContext
        record(app, Outcome.ACTIVE, info)
        LicenseJobService.schedule(app)
    }

    private fun record(app: Context, o: Outcome, info: SubInfo?) {
        when (o) {
            Outcome.ACTIVE -> {
                LicenseStore.recordVerified(app, true, info?.expiresAt ?: 0L)
                if (info != null) SubscriptionStore.onActive(app, info.plan, info.purchaseTime, info.orderId, info.expiresAt)
                // در لحظه‌ی پایان اشتراک (حتی اگر برنامه بسته باشد) قفل‌شدن و بررسی دوباره از بازار
                if (info != null && info.expiresAt > System.currentTimeMillis()) LicenseJobService.scheduleAt(app, info.expiresAt)
                Premium.reconcile(app)
                LicenseNotifier.cancelAll(app)
            }
            Outcome.INACTIVE -> {
                LicenseStore.recordVerified(app, false)
                SubscriptionStore.onInactive(app)
                Premium.reconcile(app)
                Reminders.run(app)
            }
            // خطای موقت (بدون اینترنت، بازار بسته): وضعیت قبلی دست‌نخورده می‌ماند؛
            // فقط اگر مهلت ۷ روزه تمام شده باشد قانون‌ها همگام می‌شوند
            Outcome.ERROR -> {
                Premium.reconcile(app)
                Reminders.run(app)
            }
        }
    }
}

/**
 * اتصال به بازار و باز کردن صفحه‌ی پرداخت از داخل یک Activity. هر Activity یک نمونه دارد
 * و باید در onDestroy آن [destroy] را صدا بزند.
 */
class SubscribeController(private val activity: ComponentActivity) {
    enum class Result { SUCCESS, CANCELLED, FAILED, NO_BAZAAR, NO_KEY }

    private var billing: BazaarBilling? = null

    var busy = false
        private set

    fun start(plan: Plan, onConnecting: () -> Unit, onResult: (Result) -> Unit) {
        if (busy) return
        val app = activity.applicationContext
        if (BuildConfig.BAZAAR_RSA_KEY.isBlank()) return onResult(Result.NO_KEY)
        if (!LicenseManager.bazaarInstalled(app)) return onResult(Result.NO_BAZAAR)
        busy = true
        onConnecting()
        billing?.disconnect()
        val bl = BazaarBilling(activity)
        billing = bl

        fun done(r: Result) {
            busy = false
            bl.disconnect()
            if (billing === bl) billing = null
            onResult(r)
        }

        bl.connect(
            onReady = {
                bl.subscribe(activity.activityResultRegistry, plan, UUID.randomUUID().toString(),
                    object : BazaarBilling.SubscribeCallback {
                        override fun onSuccess(info: SubInfo) {
                            LicenseManager.onPurchased(app, info)
                            done(Result.SUCCESS)
                        }

                        override fun onCancel() = done(Result.CANCELLED)
                        override fun onFail() = done(Result.FAILED)
                    })
            },
            onFail = { done(Result.FAILED) }
        )
    }

    fun destroy() {
        billing?.disconnect()
        billing = null
        busy = false
    }
}
