package ir.smsforward

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.BidiFormatter
import com.google.android.material.snackbar.Snackbar

/** یک مرحله از درخواست دسترسی: پنجره‌ی توضیح مخصوص خودش، بعد پنجره‌ی خود سیستم. */
class PermStep(
    val permission: String,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val message: Int,
    val required: Boolean
)

object Perms {
    val RECEIVE = PermStep(Manifest.permission.RECEIVE_SMS, R.drawable.ic_inbox, R.string.perm_recv_title, R.string.perm_recv_msg, true)
    val SEND = PermStep(Manifest.permission.SEND_SMS, R.drawable.ic_send, R.string.perm_send_title, R.string.perm_send_msg, true)
    val PHONE = PermStep(Manifest.permission.READ_PHONE_STATE, R.drawable.ic_sim, R.string.perm_phone_title, R.string.perm_phone_msg, false)
    val NOTIFY = PermStep(Manifest.permission.POST_NOTIFICATIONS, R.drawable.ic_bell, R.string.perm_notif_title, R.string.perm_notif_msg, false)
    val CONTACTS = PermStep(Manifest.permission.READ_CONTACTS, R.drawable.ic_contacts, R.string.perm_contacts_title, R.string.perm_contacts_message, false)
    val READ_SMS = PermStep(Manifest.permission.READ_SMS, R.drawable.ic_message, R.string.perm_sms_title, R.string.perm_sms_message, false)

    /** دسترسی‌های اصلی ارسال خودکار */
    fun sms(): List<PermStep> = listOf(RECEIVE, SEND, PHONE)

    /**
     * دسترسی‌های ابتدای برنامه؛ هر کدام پنجره‌ی جدا دارد.
     * مخاطبین و فرستنده‌های صندوق هم همین‌جا (برای همه) پرسیده می‌شوند؛ فقط اگر کاربر رد کرده باشد
     * در لحظه‌ی استفاده دوباره درخواست می‌شود.
     */
    fun startup(premium: Boolean): List<PermStep> = buildList {
        addAll(sms())
        if (Build.VERSION.SDK_INT >= 33) add(NOTIFY)
        // مخاطبین و صندوق پیامک هم همان اول پرسیده می‌شود تا بعداً دوباره پرسیده نشود
        add(CONTACTS)
        add(READ_SMS)
    }
}

/** منطق مشترک صفحه‌ها: دسترسی‌های مرحله‌ای (با توضیح فارسی)، پنجره‌ی پریمیوم، پیام کوتاه و کمکی‌های نمایش. */
abstract class BaseActivity : RtlActivity() {

    protected abstract val rootView: View

    /** خرید اشتراک از داخل همین صفحه (اتصال به کافه‌بازار) */
    private val subscriber by lazy { SubscribeController(this) }

    private class Flow(val steps: List<PermStep>, val onFinished: (Boolean) -> Unit) {
        var index = 0
        var shown = 0
        val denied = ArrayList<PermStep>()
        val blocked = ArrayList<PermStep>()
    }

    private var flow: Flow? = null
    private var current: PermStep? = null

    /** وقتی کاربر دسترسی‌های پیامک را نداد یا انصراف داد */
    protected open fun onSmsPermissionsDenied() {}

    /** وقتی دسترسی اختیاری مخاطبین/پیامک داده شد */
    protected open fun onOptionalPermissionGranted() {}

    /** بعد از خرید موفق اشتراک */
    protected open fun onPremiumChanged() {}

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val f = flow ?: return@registerForActivityResult
            val step = current ?: return@registerForActivityResult
            current = null
            Prefs(this).setPermAsked(step.permission)
            if (!ok) {
                f.denied.add(step)
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, step.permission)) f.blocked.add(step)
            }
            advance(f)
        }

    /** هر ثانیه صدا زده می‌شود (برای تایمر)؛ null یعنی اشتراک فعال نیست یا تاریخ پایانش معلوم نیست */
    protected open fun onTick(remainingMs: Long?) {}

    /** تایمر هر صفحه؛ لحظه‌ی پایان اشتراک را وسط کار هم تشخیص می‌دهد */
    private val expiryTicker by lazy {
        ExpiryTicker(this, ::onTick) {
            // اشتراک همین الان تمام شد: قفل‌کردن قانون‌ها و صفحه، بعد پرسیدن از بازار (شاید تمدید شده باشد)
            Premium.reconcile(this)
            onPremiumChanged()
            snack(R.string.prem_expired_now)
            LicenseManager.refresh(this) {
                if (!isFinishing && !isDestroyed) {
                    Premium.reconcile(this)
                    onPremiumChanged()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        expiryTicker.start()
    }

    override fun onPause() {
        expiryTicker.stop()
        super.onPause()
    }

    override fun onDestroy() {
        subscriber.destroy()
        super.onDestroy()
    }

    protected fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    protected fun hasSmsPermissions() =
        granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS)

    // ------------------------------------------------------------------ دسترسی‌های مرحله‌ای

    /** دسترسی برای همیشه رد شده و دیگر پنجره‌ی سیستم نمایش داده نمی‌شود */
    protected fun permBlocked(step: PermStep): Boolean =
        !granted(step.permission) && Prefs(this).permAsked(step.permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, step.permission)

    /**
     * برای هر دسترسیِ نداده‌شده اول یک پنجره‌ی توضیح مخصوص همان دسترسی می‌آید؛ با «ادامه» پنجره‌ی خود
     * گوشی باز می‌شود و بعد از پاسخ، پنجره‌ی دسترسی بعدی می‌آید، تا همه تمام شود.
     * [onFinished] با true صدا زده می‌شود اگر همه‌ی دسترسی‌های «الزامی» داده شده باشند.
     */
    protected fun runPermissionFlow(steps: List<PermStep>, onFinished: (Boolean) -> Unit) {
        val missing = steps.filter { !granted(it.permission) }
        if (missing.isEmpty()) {
            onFinished(true)
            return
        }
        // اگر دسترسی‌های الزامیِ مانده همه برای همیشه رد شده‌اند، فقط راه تنظیمات می‌ماند
        val reqMissing = missing.filter { it.required }
        if (reqMissing.isNotEmpty() && reqMissing.all { permBlocked(it) }) {
            showSettingsDialog()
            onFinished(false)
            return
        }
        val f = Flow(missing.filter { !permBlocked(it) }, onFinished)
        flow = f
        advance(f)
    }

    private fun advance(f: Flow) {
        if (f.index >= f.steps.size) {
            finishFlow(f)
            return
        }
        val st = f.steps[f.index++]
        if (granted(st.permission)) { // مثلاً با دسترسی هم‌گروه خودکار داده شده
            advance(f)
            return
        }
        f.shown++
        AppDialog(this)
            .icon(st.icon)
            .step(getString(R.string.perm_step, Fa.num(f.shown), Fa.num(f.steps.size)))
            .title(st.title)
            .message(st.message)
            .cancelable(false)
            .positive(R.string.perm_allow) {
                current = st
                permLauncher.launch(st.permission)
            }
            .negative(if (st.required) R.string.perm_later else R.string.perm_skip) {
                if (!st.required) Prefs(this).setPermSkipped(st.permission)
                f.denied.add(st)
                advance(f)
            }
            .show()
    }

    private fun finishFlow(f: Flow) {
        flow = null
        val requiredDenied = f.denied.any { it.required }
        if (requiredDenied) {
            if (f.blocked.any { it.required }) showSettingsDialog() else snack(R.string.perm_denied)
        }
        f.onFinished(!requiredDenied)
    }

    /** دسترسی‌های پیامک را (در صورت نیاز) مرحله‌ای می‌گیرد و بعد کار را ادامه می‌دهد. */
    protected fun withSmsPermissions(onGranted: () -> Unit) {
        if (hasSmsPermissions()) {
            onGranted()
            return
        }
        runPermissionFlow(Perms.sms()) { ok ->
            if (ok && hasSmsPermissions()) onGranted() else onSmsPermissionsDenied()
        }
    }

    /** دسترسی اختیاری مخاطبین/پیامک؛ پنجره‌ی توضیح فارسی مخصوص خودش دارد. */
    protected fun withOptionalPermission(kind: Sources.Kind, onGranted: () -> Unit) {
        if (Sources.has(this, kind)) {
            onGranted()
            return
        }
        val step = if (kind == Sources.Kind.CONTACT) Perms.CONTACTS else Perms.READ_SMS
        if (permBlocked(step)) {
            showSettingsDialog(R.string.perm_optional_settings_msg)
            return
        }
        runPermissionFlow(listOf(step)) {
            if (granted(step.permission)) {
                onGranted()
                onOptionalPermissionGranted()
            } else {
                snack(R.string.perm_denied_optional)
            }
        }
    }

    protected fun showSettingsDialog(message: Int = R.string.perm_settings_msg) {
        AppDialog(this)
            .icon(R.drawable.ic_settings)
            .title(R.string.perm_settings_title)
            .message(message)
            .positive(R.string.open_settings) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .negative(R.string.cancel)
            .show()
    }

    // ------------------------------------------------------------------ اشتراک

    /** پنجره‌ی «مخصوص اشتراک» برای یک امکان. */
    fun showPremium(feature: Feature?) {
        PremiumDialog.show(this, feature) { onPremiumChanged() }
    }

    fun startSubscribe(plan: Plan, onConnecting: () -> Unit, onResult: (SubscribeController.Result) -> Unit) {
        subscriber.start(plan, onConnecting, onResult)
    }

    // ------------------------------------------------------------------ پنجره‌ها و پیام‌ها

    /** فهرست کامل خطاها در یک پنجره (هر خطا در یک خط) */
    protected fun showErrors(title: Int, errors: List<String>) =
        AppDialog.errors(this, getString(title), errors)

    protected fun showPrivacy() {
        AppDialog(this)
            .icon(R.drawable.ic_shield)
            .title(R.string.privacy_title)
            .message(R.string.privacy_text)
            .show()
    }

    /** شماره‌ها داخل متن راست‌به‌چپ جابه‌جا نمایش داده نشوند */
    protected fun bidi(s: String): String = BidiFormatter.getInstance(true).unicodeWrap(s)

    protected fun snack(resId: Int) = snack(getString(resId))

    protected fun snack(text: String) {
        val s = Snackbar.make(rootView, text, Snackbar.LENGTH_LONG)
        s.view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        s.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            maxLines = 4
        }
        s.show()
    }
}
