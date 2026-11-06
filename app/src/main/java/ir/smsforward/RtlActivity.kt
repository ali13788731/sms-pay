package ir.smsforward

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * پایه‌ی همه‌ی صفحه‌ها: زبان فارسی و جهت راست‌به‌چپ را صریحاً روی Context صفحه می‌گذارد،
 * تا حتی اگر زبان گوشی انگلیسی باشد، عنوان‌ها، منوها و پنجره‌ها راست‌چین باشند.
 */
abstract class RtlActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val cfg = Configuration(newBase.resources.configuration)
        val fa = Locale("fa")
        cfg.setLocale(fa)
        cfg.setLayoutDirection(fa)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
}
