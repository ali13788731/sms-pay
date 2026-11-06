package ir.smsforward

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.util.concurrent.atomic.AtomicInteger

/** منطق مشترک: اعتبارسنجی شماره، تشخیص سیم‌کارت و ارسال پیامک. */
object Forwarder {
    private val requestCodes = AtomicInteger(1000)

    // ------------------------------------------------------------ شماره‌ها

    /** حذف فاصله و علامت‌ها و تبدیل ارقام فارسی/عربی به لاتین؛ فقط + ابتدای شماره حفظ می‌شود. */
    fun normalizeTarget(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            when {
                ch == '+' && sb.isEmpty() -> sb.append('+')
                ch in '0'..'9' -> sb.append(ch)
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
            }
        }
        return sb.toString()
    }

    fun isValidTarget(normalized: String): Boolean =
        Regex("^\\+?[0-9]{3,15}$").matches(normalized)

    private fun digits(s: String): String = normalizeTarget(s).filter { it in '0'..'9' }

    /** رشته فقط شامل رقم و علامت‌های رایج شماره باشد (نه نام فرستنده‌ی متنی مثل DigiKala) */
    fun isNumericLike(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || digits(t).isEmpty()) return false
        return t.all {
            it in '0'..'9' || it in '۰'..'۹' || it in '٠'..'٩' || it in "+-()./ \u00A0\u200C\u200E\u200F"
        }
    }

    /** کلید مقایسه: شماره‌ها با ۱۰ رقم آخر (0098 / +98 / 0 اول یکسان‌اند)، نام‌ها بدون حساسیت به حروف */
    fun key(s: String): String =
        if (isNumericLike(s)) "n:" + digits(s).takeLast(10) else "t:" + s.trim().lowercase()

    /** مقایسه‌ی شماره‌ها یا نام فرستنده‌های متنی */
    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /** شکستن لیست فرستنده‌ها (جداکننده: , ، ; و خط جدید) */
    fun parseList(raw: String): List<String> =
        raw.split(",", "،", "\n", ";").map { it.trim() }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------ سیم‌کارت

    fun slotOf(c: Context, subId: Int): Int = try {
        if (subId < 0) -1 else
            c.getSystemService(SubscriptionManager::class.java)
                .getActiveSubscriptionInfo(subId)?.simSlotIndex ?: -1
    } catch (e: Exception) {
        -1
    }

    private fun subOfSlot(c: Context, slot: Int): Int = try {
        c.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId ?: -1
    } catch (e: Exception) {
        -1
    }

    @Suppress("DEPRECATION")
    private fun smsManager(c: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            val base = c.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }

    // ------------------------------------------------------------ ارسال

    /**
     * پیام را طبق قالبِ قانون ساخته و به همه‌ی مقصدهای آن قانون می‌فرستد.
     * نتیجه‌ی واقعی ارسال هر مقصد بعداً توسط [SentReceiver] در گزارش ثبت می‌شود.
     * [dedupe]: اگر چند قانون یک پیام یکسان را به یک شماره بفرستند، فقط یک بار ارسال می‌شود.
     * خروجی: تعداد مقصدهایی که ارسال برایشان آغاز شد.
     */
    fun forward(
        c: Context, rule: Rule, ruleName: String, sender: String, body: String,
        subId: Int, slot: Int, test: Boolean = false, dedupe: MutableSet<String>? = null
    ): Int {
        val p = Prefs(c)
        val label = if (test) c.getString(R.string.log_test) else sender
        var started = 0
        val multi = SimInfo.multi(c)
        val text = try {
            // شماره‌ی سیم همیشه نوشته می‌شود (گوشی تک‌سیم: سیم ۱)
            val simLabel = when {
                slot >= 0 -> (slot + 1).toString()
                !multi -> "1"
                else -> c.getString(R.string.sim_unknown)
            }
            // قالب پیش‌فرض بسته به تک‌سیم/دو‌سیم خودش را تطبیق می‌دهد؛ قالب سفارشی (اشتراک) دست‌نخورده می‌ماند
            val tpl = if (Template.isDefault(rule.template)) Template.defaultFor(multi) else rule.template
            Template.render(tpl, sender, body, simLabel, System.currentTimeMillis())
        } catch (e: Exception) {
            p.addLog(label, false, c.getString(R.string.err_render, e.message ?: e.javaClass.simpleName))
            return 0
        }
        val sendSub = when (rule.sendSim) {
            1 -> subOfSlot(c, 0)
            2 -> subOfSlot(c, 1)
            else -> subId
        }
        for (target in rule.targets) {
            try {
                if (dedupe != null && !dedupe.add(key(target) + "|" + sendSub + "|" + text.hashCode())) continue
                val sm = smsManager(c, sendSub)
                val parts = sm.divideMessage(text)
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) sentIntents.add(sentIntent(c, label, target, ruleName, i, parts.size))
                sm.sendMultipartTextMessage(target, null, parts, sentIntents, null)
                started++
            } catch (e: Exception) {
                p.addLog(
                    label, false,
                    c.getString(R.string.log_fail_to, e.message ?: e.javaClass.simpleName, target, ruleName)
                )
            }
        }
        return started
    }

    private fun sentIntent(
        c: Context, label: String, target: String, rule: String, part: Int, total: Int
    ): PendingIntent {
        val i = Intent(c, SentReceiver::class.java)
            .putExtra(SentReceiver.EXTRA_LABEL, label)
            .putExtra(SentReceiver.EXTRA_TARGET, target)
            .putExtra(SentReceiver.EXTRA_RULE, rule)
            .putExtra(SentReceiver.EXTRA_PART, part)
            .putExtra(SentReceiver.EXTRA_TOTAL, total)
        return PendingIntent.getBroadcast(
            c, requestCodes.incrementAndGet(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )
    }
}
