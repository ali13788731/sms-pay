package ir.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val p = Prefs(c)
        if (!p.enabled) return // شامل قفل بروزرسانی اجباری هم هست

        // اگر مدت زیادی نسخه بررسی نشده، قبل از ارسال یک بار بررسی می‌کنیم
        if (UpdateChecker.isStale(c)) {
            val pending = goAsync()
            val app = c.applicationContext
            Thread {
                try {
                    UpdateChecker.check(app)
                    process(app, i)
                } catch (e: Exception) {
                    // ارسال پیامک نباید به‌خاطر خطای بررسی نسخه خراب شود
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            process(c, i)
        }
    }

    private fun process(c: Context, i: Intent) {
        val p = Prefs(c)
        if (!p.enabled) return
        // اگر اشتراک تمام شده، پیش از هر کاری قانون‌ها با وضعیت اشتراک همگام می‌شوند
        Premium.reconcile(c)
        val all = p.rules()
        // با اشتراک: همه‌ی قانون‌های فعال. بدون اشتراک: فقط یک قانونِ فعالِ بدون امکانات ویژه
        val runnable = Premium.runnable(c, all)
        if (runnable.isEmpty()) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)
        if (msgs.isNullOrEmpty()) return
        val sender = msgs[0].originatingAddress ?: ""
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val subId = i.getIntExtra("subscription", -1)
        val multi = SimInfo.multi(c)
        var slot = Forwarder.slotOf(c, subId) // از صفر؛ -1 یعنی نامشخص
        if (slot < 0 && !multi) slot = 0 // گوشی تک‌سیم‌کارته: همیشه همان سیم ۱

        val sent = HashSet<String>()
        all.forEachIndexed { index, r ->
            val rule = runnable.firstOrNull { it.id == r.id } ?: return@forEachIndexed

            // فیلتر سیم‌کارت
            if (rule.recvSim == 1 && slot != 0) return@forEachIndexed
            if (rule.recvSim == 2 && slot != 1) return@forEachIndexed

            // فیلتر فرستنده
            if (!rule.allSenders && rule.senders.none { Forwarder.same(sender, it) }) return@forEachIndexed

            // جلوگیری از حلقه: پیامی که از یکی از مقصدها آمده به همان مقصد برنمی‌گردد
            val targets = rule.targets.filterNot { Forwarder.same(sender, it) }
            if (targets.isEmpty()) return@forEachIndexed

            Forwarder.forward(
                c, rule.copy(targets = targets), rule.displayName(c, index),
                sender, body, subId, slot, dedupe = sent
            )
        }
    }
}
