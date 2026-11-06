package ir.smsforward

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.smsforward.databinding.DialogAppBinding

/**
 * پنجره‌ی استاندارد برنامه: کاملاً راست‌چین (همه‌ی متن‌ها و دکمه‌ها)، با آیکون، عنوان، متن و دکمه‌های زیر هم.
 * همه‌ی پنجره‌های برنامه (دسترسی‌ها، خطاها، تأییدها، حریم خصوصی…) از همین‌جا ساخته می‌شوند.
 */
class AppDialog(private val ctx: Context) {
    private var iconRes = R.drawable.ic_info
    private var titleText: CharSequence = ""
    private var messageText: CharSequence = ""
    private var stepText: CharSequence? = null
    private var pos: Pair<CharSequence, () -> Unit>? = null
    private var neg: Pair<CharSequence, () -> Unit>? = null
    private var neu: Pair<CharSequence, () -> Unit>? = null
    private var cancelable = true
    private var onCancel: (() -> Unit)? = null
    private var destructive = false

    fun icon(@DrawableRes r: Int) = apply { iconRes = r }
    fun title(t: CharSequence) = apply { titleText = t }
    fun title(@StringRes r: Int) = apply { titleText = ctx.getString(r) }
    fun message(t: CharSequence) = apply { messageText = t }
    fun message(@StringRes r: Int) = apply { messageText = ctx.getString(r) }
    fun step(t: CharSequence?) = apply { stepText = t }
    fun cancelable(v: Boolean) = apply { cancelable = v }
    fun onCancel(f: () -> Unit) = apply { onCancel = f }

    /** دکمه‌ی اصلی (پر) */
    fun positive(label: CharSequence, action: () -> Unit = {}) = apply { pos = label to action }
    fun positive(@StringRes r: Int, action: () -> Unit = {}) = positive(ctx.getString(r), action)

    /** دکمه‌ی دوم (متنی، پایین‌ترین) */
    fun negative(label: CharSequence, action: () -> Unit = {}) = apply { neg = label to action }
    fun negative(@StringRes r: Int, action: () -> Unit = {}) = negative(ctx.getString(r), action)

    /** دکمه‌ی میانی (تونال) */
    fun neutral(label: CharSequence, action: () -> Unit = {}) = apply { neu = label to action }
    fun neutral(@StringRes r: Int, action: () -> Unit = {}) = neutral(ctx.getString(r), action)

    /** دکمه‌ی اصلی قرمز (حذف و کارهای برگشت‌ناپذیر) */
    fun destructive() = apply { destructive = true }

    fun show(): AlertDialog {
        val b = DialogAppBinding.inflate(LayoutInflater.from(ctx))
        b.icon.setImageResource(iconRes)
        b.title.text = titleText
        b.title.visibility = if (titleText.isEmpty()) View.GONE else View.VISIBLE
        b.message.text = messageText
        b.message.visibility = if (messageText.isEmpty()) View.GONE else View.VISIBLE
        if (stepText != null) {
            b.step.text = stepText
            b.step.visibility = View.VISIBLE
        }
        val d = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_App_Dialog)
            .setView(b.root)
            .setCancelable(cancelable)
            .create()

        fun bind(btn: com.google.android.material.button.MaterialButton, p: Pair<CharSequence, () -> Unit>?) {
            if (p == null) {
                btn.visibility = View.GONE
                return
            }
            btn.text = p.first
            btn.visibility = View.VISIBLE
            btn.setOnClickListener {
                d.dismiss()
                p.second()
            }
        }
        bind(b.btnPositive, pos ?: (ctx.getString(R.string.ok) to {}))
        bind(b.btnNeutral, neu)
        bind(b.btnNegative, neg)
        if (destructive) {
            b.btnPositive.setBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.error_solid))
            b.btnPositive.setTextColor(0xFFFFFFFF.toInt())
        }
        d.setOnCancelListener { onCancel?.invoke() }
        d.show()
        return d
    }

    companion object {
        /** پیام ساده با یک دکمه‌ی «تأیید» */
        fun info(ctx: Context, @DrawableRes icon: Int, title: CharSequence, message: CharSequence) {
            AppDialog(ctx).icon(icon).title(title).message(message).show()
        }

        /** فهرست خطاها (هر خطا در یک خط) */
        fun errors(ctx: Context, title: String, errors: List<String>) {
            AppDialog(ctx).icon(R.drawable.ic_warning)
                .title("$title (${Fa.num(errors.size)})")
                .message(errors.joinToString("\n\n") { "• $it" })
                .show()
        }

        /** پرسش با دو دکمه */
        fun confirm(
            ctx: Context, title: CharSequence, message: CharSequence, yes: CharSequence,
            dangerous: Boolean = false, onYes: () -> Unit
        ) {
            val d = AppDialog(ctx).icon(if (dangerous) R.drawable.ic_delete else R.drawable.ic_info)
                .title(title).message(message)
                .positive(yes, onYes)
                .negative(ctx.getString(R.string.cancel))
            if (dangerous) d.destructive()
            d.show()
        }
    }
}
