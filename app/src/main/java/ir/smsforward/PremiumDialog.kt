package ir.smsforward

import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.smsforward.databinding.DialogPremiumBinding
import ir.smsforward.databinding.ItemPerkBinding

/**
 * پنجره‌ی «این امکان مخصوص اشتراک است»: امکان لمس‌شده را بالا نشان می‌دهد، بقیه‌ی امکانات ویژه را
 * با آیکون و توضیح خوش‌خوان می‌گوید، طرح ماهانه/سالانه را نشان می‌دهد و با یک دکمه به کافه‌بازار وصل می‌شود.
 */
object PremiumDialog {

    fun show(activity: BaseActivity, feature: Feature?, onPurchased: () -> Unit = {}) {
        val b = DialogPremiumBinding.inflate(LayoutInflater.from(activity))
        b.subtitle.text = if (feature != null) activity.getString(feature.hint)
        else activity.getString(R.string.prem_subtitle_generic)

        // امکانی که کاربر لمس کرده اول می‌آید و مشخص می‌شود
        val ordered = Feature.values().toList().let { all ->
            if (feature == null) all else listOf(feature) + all.filter { it != feature }
        }
        ordered.forEach { f ->
            val row = ItemPerkBinding.inflate(LayoutInflater.from(activity), b.perkList, false)
            row.perkIcon.setImageResource(f.icon)
            row.perkTitle.setText(f.title)
            row.perkDesc.setText(f.desc)
            if (f == feature) row.perkRoot.setBackgroundResource(R.drawable.bg_lock_badge)
            b.perkList.addView(row.root)
        }

        var plan = Plan.YEARLY

        /** عنوان، مبلغ و توضیح هر طرح همان است که در کافه‌بازار تعریف شده */
        fun bindPlans() {
            val y = Plan.YEARLY.info(activity)
            val m = Plan.MONTHLY.info(activity)
            val saving = Plan.yearlySavingPercent(activity)
            b.yearlyBadge.visibility = if (saving != null) View.VISIBLE else View.INVISIBLE
            if (saving != null) b.yearlyBadge.text = activity.getString(R.string.plan_badge_best, Fa.num(saving))
            b.yearlyTitle.text = y.title
            b.yearlyPrice.text = y.priceText
            val perMonth = Plan.yearlyPerMonth(activity)
            b.yearlySub.text = y.description.ifBlank {
                if (perMonth > 0) activity.getString(R.string.plan_per_month, Fa.num(perMonth))
                else activity.getString(R.string.sub_days, Fa.num(y.days))
            }
            b.monthlyTitle.text = m.title
            b.monthlyPrice.text = m.priceText
            b.monthlySub.text = m.description.ifBlank { activity.getString(R.string.sub_days, Fa.num(m.days)) }
        }
        bindPlans()

        fun renderPlans() {
            val on = ContextCompat.getColor(activity, R.color.premium)
            val off = com.google.android.material.color.MaterialColors.getColor(
                b.root, com.google.android.material.R.attr.colorOutlineVariant
            )
            val dp = activity.resources.displayMetrics.density
            b.planYearly.strokeColor = if (plan == Plan.YEARLY) on else off
            b.planYearly.strokeWidth = ((if (plan == Plan.YEARLY) 2.5f else 1f) * dp).toInt()
            b.planMonthly.strokeColor = if (plan == Plan.MONTHLY) on else off
            b.planMonthly.strokeWidth = ((if (plan == Plan.MONTHLY) 2.5f else 1f) * dp).toInt()
            b.btnBuy.text = activity.getString(R.string.prem_buy, plan.title(activity))
        }
        renderPlans()
        b.planYearly.setOnClickListener { plan = Plan.YEARLY; renderPlans() }
        b.planMonthly.setOnClickListener { plan = Plan.MONTHLY; renderPlans() }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_App_Dialog)
            .setView(b.root)
            .create()

        // اگر اطلاعات طرح‌ها هنوز از بازار خوانده نشده یا کهنه است، همین‌جا به‌روز می‌شود
        LicenseManager.refreshPlans(activity) {
            if (dialog.isShowing) {
                bindPlans()
                renderPlans()
            }
        }

        b.btnLater.setOnClickListener { dialog.dismiss() }
        b.btnBuy.setOnClickListener {
            activity.startSubscribe(
                plan,
                onConnecting = {
                    b.btnBuy.isEnabled = false
                    b.planYearly.isEnabled = false
                    b.planMonthly.isEnabled = false
                    b.status.visibility = View.VISIBLE
                    b.status.setTextColor(ContextCompat.getColor(activity, R.color.brand))
                    b.status.setText(R.string.prem_connecting)
                },
                onResult = { r ->
                    if (r == SubscribeController.Result.SUCCESS) {
                        Toast.makeText(activity, R.string.prem_success, Toast.LENGTH_LONG).show()
                        if (dialog.isShowing) dialog.dismiss()
                        onPurchased()
                    } else if (dialog.isShowing) {
                        b.btnBuy.isEnabled = true
                        b.planYearly.isEnabled = true
                        b.planMonthly.isEnabled = true
                        b.status.visibility = View.VISIBLE
                        b.status.setTextColor(ContextCompat.getColor(activity, R.color.error))
                        b.status.setText(
                            when (r) {
                                SubscribeController.Result.CANCELLED -> R.string.lic_cancelled
                                SubscribeController.Result.NO_BAZAAR -> R.string.prem_no_bazaar
                                SubscribeController.Result.NO_KEY -> R.string.lic_sub_error_key
                                else -> R.string.prem_failed
                            }
                        )
                    }
                }
            )
        }
        dialog.show()
    }
}
