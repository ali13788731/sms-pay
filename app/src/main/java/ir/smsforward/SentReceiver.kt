package ir.smsforward

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** نتیجه‌ی واقعی ارسال هر بخش از پیامک را دریافت و در گزارش ثبت می‌کند. */
class SentReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, i: Intent) {
        val label = i.getStringExtra(EXTRA_LABEL) ?: return
        val target = i.getStringExtra(EXTRA_TARGET).orEmpty()
        val rule = i.getStringExtra(EXTRA_RULE).orEmpty()
        val part = i.getIntExtra(EXTRA_PART, 0)
        val total = i.getIntExtra(EXTRA_TOTAL, 1)
        val p = Prefs(c)

        if (resultCode == Activity.RESULT_OK) {
            // برای پیام چندبخشی فقط پس از آخرین بخش یک بار ثبت می‌شود
            if (part == total - 1) p.addLog(label, true, c.getString(R.string.log_sent_to, target, rule))
        } else {
            val reason = when (resultCode) {
                1 -> c.getString(R.string.err_generic)
                2 -> c.getString(R.string.err_radio_off)
                3 -> c.getString(R.string.err_null_pdu)
                4 -> c.getString(R.string.err_no_service)
                else -> c.getString(R.string.err_code, resultCode)
            }
            val suffix = if (total > 1) " (${part + 1}/$total)" else ""
            p.addLog(label, false, c.getString(R.string.log_fail_to, reason + suffix, target, rule))
        }
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_TARGET = "target"
        const val EXTRA_RULE = "rule"
        const val EXTRA_PART = "part"
        const val EXTRA_TOTAL = "total"
    }
}
