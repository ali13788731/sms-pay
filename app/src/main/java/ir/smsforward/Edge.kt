package ir.smsforward

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * نمایش تمام‌صفحه (Edge-to-Edge) مطابق الزام اندروید ۱۵:
 * فاصله‌ی نوار وضعیت به نوار بالا، و فاصله‌ی نوار ناوبری/کیبورد به پایین‌ترین بخش لیست داده می‌شود.
 */
fun Activity.applyEdgeToEdge(root: View, topBar: View, scroll: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
    val basePadding = scroll.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        root.updatePadding(left = bars.left, right = bars.right)
        topBar.updatePadding(top = bars.top)
        scroll.updatePadding(bottom = basePadding + maxOf(bars.bottom, ime.bottom))
        insets
    }
}

/**
 * مثل [applyEdgeToEdge] برای صفحه‌ای که نوار ثابت پایین (دکمه‌ی ذخیره) دارد:
 * نوار پایین بالای نوار ناوبری می‌ماند و هنگام باز بودن کیبورد بالای آن قرار می‌گیرد.
 */
fun Activity.applyEdgeToEdgeBar(root: View, topBar: View, bottomBar: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
    val base = bottomBar.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        root.updatePadding(left = bars.left, right = bars.right, bottom = if (imeVisible) ime.bottom else 0)
        topBar.updatePadding(top = bars.top)
        bottomBar.updatePadding(bottom = base + if (imeVisible) 0 else bars.bottom)
        insets
    }
}
