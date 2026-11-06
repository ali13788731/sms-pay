package ir.smsforward

import java.util.Locale

/** کمکی‌های نمایش فارسی: ارقام فارسی، پول، تاریخ شمسی و روزهای باقی‌مانده. */
object Fa {
    const val DAY = 24L * 60 * 60 * 1000

    private val FA = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** ارقام لاتین داخل متن را به فارسی تبدیل می‌کند. */
    fun digits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '0'..'9') FA[ch - '0'] else ch)
        return sb.toString()
    }

    /** ارقام فارسی و عربی را به لاتین برمی‌گرداند (برای جستجو). */
    fun latinDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** عدد با جداکننده‌ی هزارگان فارسی: ۲۰۰٬۰۰۰ */
    fun num(n: Long): String = digits(String.format(Locale.US, "%,d", n).replace(",", "٬"))

    fun num(n: Int): String = num(n.toLong())

    fun toman(n: Long): String = num(n) + " تومان"

    fun date(millis: Long): String = digits(Template.date(millis))

    fun dateTime(millis: Long): String = digits(Template.dateTime(millis))

    /** چند روز تا [target] مانده؛ هر بخشی از روز یک روز حساب می‌شود و کمتر از صفر نمی‌شود. */
    fun daysLeft(target: Long, now: Long = System.currentTimeMillis()): Int {
        val d = target - now
        return if (d <= 0) 0 else ((d + DAY - 1) / DAY).toInt()
    }
}
