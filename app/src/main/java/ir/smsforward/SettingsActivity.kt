package ir.smsforward

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
import ir.smsforward.databinding.ActivitySettingsBinding

/** تنظیمات برنامه: حالت نمایش، اعلان‌ها و پایداری در پس‌زمینه. */
class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override val rootView: View get() = b.root

    private val themeIds get() = listOf(b.themeAuto.id, b.themeLight.id, b.themeDark.id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdge(b.root, b.topBar, b.scroll)
        prefs = Prefs(this)

        b.themeGroup.check(themeIds[prefs.themeMode])
        b.themeGroup.setOnCheckedStateChangeListener { _, ids ->
            val mode = ids.firstOrNull()?.let { themeIds.indexOf(it) } ?: return@setOnCheckedStateChangeListener
            if (mode < 0 || mode == prefs.themeMode) return@setOnCheckedStateChangeListener
            prefs.themeMode = mode
            App.applyTheme(mode) // صفحه خودش دوباره ساخته می‌شود
        }

        b.switchReminder.isChecked = prefs.remindersOn
        b.switchReminder.setOnCheckedChangeListener { _, on -> prefs.remindersOn = on }
        b.switchPromo.isChecked = prefs.promoOn
        b.switchPromo.setOnCheckedChangeListener { _, on -> prefs.promoOn = on }

        b.btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ignoring = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
        b.batteryStatus.setText(if (ignoring) R.string.battery_ok else R.string.battery_not_ok)
        b.batteryStatus.setTextColor(
            if (ignoring) ContextCompat.getColor(this, R.color.success)
            else ContextCompat.getColor(this, R.color.error)
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
