package ir.smsforward

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val p = Prefs(this)
        // کل برنامه فارسی و راست‌چین است (پنجره‌ها، عنوان‌ها، منو و پیام‌های سیستمی هم)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
        applyTheme(p.themeMode)

        if (p.firstRun == 0L) p.firstRun = System.currentTimeMillis()
        UpdateGate.touch(this)
        // بعد از نصب نسخه‌ی جدید، الزام بروزرسانی ذخیره‌شده پاک می‌شود
        if (p.reqCode != 0 && p.reqCode <= UpdateChecker.installedCode(this)) p.clearRequiredUpdate()
        UpdateNotifier.ensureChannels(this)
        UpdateJobService.schedule(this)
        LicenseNotifier.ensureChannel(this)
        LicenseJobService.schedule(this)
        NewsJobService.schedule(this)
        // وضعیت قانون‌ها با وضعیت اشتراک هماهنگ می‌شود (پایان اشتراک ← غیرفعال‌شدن قانون‌های ویژه)
        try {
            Premium.reconcile(this)
        } catch (e: Exception) {
        }

        // تا بروزرسانی اجباری نصب نشده، هیچ صفحه‌ای (جز صفحه‌ی بروزرسانی) باز نمی‌ماند.
        // اشتراک دیگر قفل برنامه نیست: برنامه رایگان است و فقط امکانات ویژه قفل دارند.
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) = gate(a)
            override fun onActivityStarted(a: Activity) = gate(a)
            override fun onActivityResumed(a: Activity) {
                // حالت خودکار: با گذشت ساعت روز/شب عوض می‌شود
                if (Prefs(a).themeMode == 0) applyTheme(0)
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun gate(a: Activity) {
        if (a is UpdateActivity || a is SplashActivity || a.isFinishing) return
        UpdateGate.touch(a)
        if (UpdateGate.state(a) != UpdateGate.State.OK) {
            a.startActivity(Intent(a, UpdateActivity::class.java))
            a.finish()
        }
    }

    companion object {
        /** 0=خودکار (طبق ساعت)، 1=روشن، 2=تاریک */
        fun applyTheme(mode: Int) = AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> if (isNightNow()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        /** شب: ۷ عصر تا ۶ صبح (ساعت گوشی) */
        private fun isNightNow(): Boolean {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return h >= 19 || h < 6
        }
    }
}
