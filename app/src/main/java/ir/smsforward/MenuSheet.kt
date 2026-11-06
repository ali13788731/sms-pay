package ir.smsforward

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import ir.smsforward.databinding.ItemMenuRowBinding
import ir.smsforward.databinding.SheetMenuBinding

/** منوی اصلی برنامه: یک صفحه‌ی کشویی از پایین با گزینه‌های مرتب و آیکون‌دار. */
object MenuSheet {

    class Actions(
        val onSubscription: () -> Unit,
        val onLog: () -> Unit,
        val onSettings: () -> Unit,
        val onPrivacy: () -> Unit,
        val onAbout: () -> Unit
    )

    fun show(activity: Activity, premium: Boolean, a: Actions) {
        val dialog = BottomSheetDialog(activity)
        val sb = SheetMenuBinding.inflate(activity.layoutInflater)
        val inflater = LayoutInflater.from(activity)

        fun row(
            @DrawableRes icon: Int, title: String, hint: String, badge: Boolean = false,
            highlight: Boolean = false, action: () -> Unit
        ) {
            val r = ItemMenuRowBinding.inflate(inflater, sb.menuList, false)
            r.rowIcon.setImageResource(icon)
            r.rowTitle.text = title
            r.rowHint.text = hint
            r.rowBadge.visibility = if (badge) View.VISIBLE else View.GONE
            if (highlight) {
                r.rowRoot.setBackgroundResource(R.drawable.bg_lock_badge)
                r.rowIconBox.setBackgroundResource(R.drawable.bg_icon_white)
                r.rowIcon.imageTintList =
                    android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(activity, R.color.on_premium_container))
            }
            r.rowRoot.setOnClickListener {
                dialog.dismiss()
                action()
            }
            sb.menuList.addView(r.root)
        }

        // بخش اشتراک: اگر اشتراک نخریده باشد «خرید اشتراک» نوشته می‌شود
        if (premium) {
            row(R.drawable.ic_verified, activity.getString(R.string.menu_sub_mine), activity.getString(R.string.menu_sub_mine_hint), action = a.onSubscription)
        } else {
            row(R.drawable.ic_crown, activity.getString(R.string.menu_sub_buy), activity.getString(R.string.menu_sub_buy_hint), highlight = true, action = a.onSubscription)
        }
        row(R.drawable.ic_list, activity.getString(R.string.log_title), activity.getString(R.string.log_row_hint), badge = !premium, action = a.onLog)
        row(R.drawable.ic_settings, activity.getString(R.string.settings_title), activity.getString(R.string.menu_settings_hint), action = a.onSettings)
        row(R.drawable.ic_shield, activity.getString(R.string.menu_privacy), activity.getString(R.string.menu_privacy_hint), action = a.onPrivacy)
        row(R.drawable.ic_info, activity.getString(R.string.menu_about), activity.getString(R.string.menu_about_hint), action = a.onAbout)

        dialog.setContentView(sb.root)
        dialog.show()
    }
}
