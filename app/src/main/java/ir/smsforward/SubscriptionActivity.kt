package ir.smsforward

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ir.smsforward.databinding.ActivitySubscriptionBinding
import ir.smsforward.databinding.ItemDetailRowBinding
import ir.smsforward.databinding.ItemHistoryBinding

/**
 * «اشتراک من»: وضعیت (رایگان/ویژه)، طرح، تاریخ خرید و انقضا، مبلغ، تاریخچه، خرید و بازیابی از کافه‌بازار.
 * بازار تاریخ انقضا و مبلغ را به برنامه نمی‌دهد؛ این دو از «زمان خرید + مدت طرح» و قیمت طرح محاسبه می‌شوند.
 */
class SubscriptionActivity : BaseActivity() {

    private lateinit var b: ActivitySubscriptionBinding
    override val rootView: View get() = b.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdge(b.root, b.topBar, b.scroll)

        b.btnYearly.setOnClickListener { buy(Plan.YEARLY) }
        b.btnMonthly.setOnClickListener { buy(Plan.MONTHLY) }
        b.btnRestore.setOnClickListener { restore() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        LicenseManager.refreshPlans(this) { if (!isFinishing && !isDestroyed) render() }
    }

    override fun onPremiumChanged() = render()

    /** ردیف «زمان باقی‌مانده» که هر ثانیه به‌روز می‌شود */
    private var leftValue: android.widget.TextView? = null

    override fun onTick(remainingMs: Long?) {
        val cur = SubscriptionStore.current(this)
        if (remainingMs == null || cur == null) return
        val t = Countdown.format(this, remainingMs)
        b.statusSub.text = getString(R.string.sub_status_premium_timer, cur.plan.title(this), t)
        leftValue?.text = t
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ نمایش

    private fun render() {
        val active = Premium.isActive(this)
        val cur = SubscriptionStore.current(this)
        val now = System.currentTimeMillis()

        // وضعیت
        if (active && cur != null) {
            val exp = SubscriptionStore.expiry(cur, true, now)
            val left = Fa.daysLeft(exp, now)
            b.statusTitle.text = getString(R.string.sub_status_premium)
            b.statusSub.text = getString(R.string.sub_status_premium_sub, cur.plan.title(this), Fa.num(left))
        } else if (active) {
            b.statusTitle.text = getString(R.string.sub_status_premium)
            b.statusSub.text = getString(R.string.sub_status_premium_unknown)
        } else if (cur != null) {
            b.statusTitle.text = getString(R.string.sub_status_ended)
            b.statusSub.text = getString(R.string.sub_status_ended_sub, Fa.date(cur.endedAt.takeIf { it > 0 } ?: now))
        } else {
            b.statusTitle.text = getString(R.string.sub_status_free)
            b.statusSub.text = getString(R.string.sub_status_free_sub)
        }
        b.statusIcon.setImageResource(if (active) R.drawable.ic_crown else R.drawable.ic_star)

        // جزئیات
        b.detailsList.removeAllViews()
        leftValue = null
        b.detailsCard.isVisible = cur != null
        if (cur != null) {
            val exp = SubscriptionStore.expiry(cur, active, now)
            row(R.string.sub_d_plan, cur.plan.title(this))
            row(R.string.sub_d_duration, cur.plan.duration(this))
            row(R.string.sub_d_bought, getString(R.string.sub_d_bought_value, cur.plan.title(this)))
            row(R.string.sub_d_purchase_date, Fa.dateTime(cur.purchaseTime))
            row(if (active) R.string.sub_d_expiry else R.string.sub_d_ended_on, Fa.dateTime(exp))
            if (active) row(R.string.sub_d_left, Countdown.format(this, exp - now))
            row(R.string.sub_d_paid, cur.plan.priceText(this))
            if (cur.orderId.isNotBlank()) row(R.string.sub_d_order, cur.orderId, ltr = true)
        }

        // خرید
        b.plansCard.isVisible = true
        b.plansTitle.setText(if (active) R.string.sub_plans_renew else R.string.sub_plans)
        val saving = Plan.yearlySavingPercent(this)
        b.btnYearly.text =
            if (saving != null) getString(R.string.sub_buy_plan_saving, Plan.YEARLY.title(this), Plan.YEARLY.priceText(this), Fa.num(saving))
            else getString(R.string.sub_buy_plan, Plan.YEARLY.title(this), Plan.YEARLY.priceText(this))
        b.btnMonthly.text = getString(R.string.sub_buy_plan, Plan.MONTHLY.title(this), Plan.MONTHLY.priceText(this))
        // با اشتراک فعال، خرید دوباره لازم نیست (بازار خودش تمدید می‌کند)
        b.plansCard.isVisible = !active

        // تاریخچه
        val events = SubscriptionStore.events(this)
        b.historyEmpty.isVisible = events.isEmpty()
        b.historyList.removeAllViews()
        events.forEach { e ->
            val r = ItemHistoryBinding.inflate(LayoutInflater.from(this), b.historyList, false)
            val ended = e.type == SubscriptionStore.Event.ENDED
            r.histTitle.text = getString(
                when (e.type) {
                    SubscriptionStore.Event.RENEW -> R.string.sub_h_renew
                    SubscriptionStore.Event.ENDED -> R.string.sub_h_ended
                    else -> R.string.sub_h_purchase
                },
                e.plan.title(this)
            )
            r.histSub.text = Fa.dateTime(e.time)
            r.histPrice.text = if (ended || e.price <= 0L) "" else Fa.toman(e.price)
            val tint = ContextCompat.getColor(this, if (ended) R.color.error else R.color.success)
            r.histIconBox.backgroundTintList = ColorStateList.valueOf(tint).withAlpha(36)
            r.histIcon.setImageResource(if (ended) R.drawable.ic_close else R.drawable.ic_receipt)
            r.histIcon.imageTintList = ColorStateList.valueOf(tint)
            b.historyList.addView(r.root)
        }
    }

    private fun row(label: Int, value: String, ltr: Boolean = false) {
        val r = ItemDetailRowBinding.inflate(LayoutInflater.from(this), b.detailsList, false)
        r.label.setText(label)
        r.value.text = value
        if (ltr) r.value.textDirection = View.TEXT_DIRECTION_LTR
        if (label == R.string.sub_d_left) leftValue = r.value
        b.detailsList.addView(r.root)
    }

    // ------------------------------------------------------------------ عملیات

    private fun setBusy(busy: Boolean, text: Int = 0) {
        b.btnYearly.isEnabled = !busy
        b.btnMonthly.isEnabled = !busy
        b.btnRestore.isEnabled = !busy
        b.buyStatus.isVisible = busy
        if (busy && text != 0) b.buyStatus.setText(text)
    }

    private fun buy(plan: Plan) {
        startSubscribe(
            plan,
            onConnecting = { setBusy(true, R.string.prem_connecting) },
            onResult = { r ->
                setBusy(false)
                when (r) {
                    SubscribeController.Result.SUCCESS -> {
                        snack(R.string.prem_success)
                        render()
                    }
                    SubscribeController.Result.CANCELLED -> {}
                    SubscribeController.Result.NO_BAZAAR -> AppDialog.info(
                        this, R.drawable.ic_warning, getString(R.string.error_title), getString(R.string.prem_no_bazaar)
                    )
                    SubscribeController.Result.NO_KEY, SubscribeController.Result.FAILED -> AppDialog.info(
                        this, R.drawable.ic_warning, getString(R.string.error_title), getString(R.string.prem_failed)
                    )
                }
            }
        )
    }

    /** بازیابی: وضعیت اشتراک دوباره از کافه‌بازار خوانده می‌شود (مثلاً بعد از نصب مجدد یا عوض‌کردن گوشی). */
    private fun restore() {
        if (!LicenseManager.bazaarInstalled(this)) {
            AppDialog.info(this, R.drawable.ic_warning, getString(R.string.error_title), getString(R.string.prem_no_bazaar))
            return
        }
        setBusy(true, R.string.sub_restoring)
        LicenseManager.refresh(this) { o ->
            setBusy(false)
            Premium.reconcile(this)
            render()
            when (o) {
                LicenseManager.Outcome.ACTIVE -> snack(R.string.sub_restore_ok)
                LicenseManager.Outcome.INACTIVE -> snack(R.string.sub_restore_none)
                LicenseManager.Outcome.ERROR -> snack(R.string.sub_restore_error)
            }
        }
    }
}
