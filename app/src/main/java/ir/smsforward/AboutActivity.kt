package ir.smsforward

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import ir.smsforward.databinding.ActivityAboutBinding
import ir.smsforward.databinding.ItemCompareRowBinding

/** «درباره‌ی برنامه»: معرفی، مقایسه‌ی رایگان و ویژه، طرح‌ها و حریم خصوصی. */
class AboutActivity : BaseActivity() {

    private lateinit var b: ActivityAboutBinding
    override val rootView: View get() = b.root

    /** کلید ردیف‌ها؛ رشته‌ها: about_cmp_<key>_t / _free / _prem */
    private class Cmp(val title: Int, val free: Int, val prem: Int)

    private val rows = listOf(
        Cmp(R.string.about_cmp_rules_t, R.string.about_cmp_rules_free, R.string.about_cmp_rules_prem),
        Cmp(R.string.about_cmp_simrecv_t, R.string.about_cmp_simrecv_free, R.string.about_cmp_simrecv_prem),
        Cmp(R.string.about_cmp_senders_t, R.string.about_cmp_senders_free, R.string.about_cmp_senders_prem),
        Cmp(R.string.about_cmp_targets_t, R.string.about_cmp_targets_free, R.string.about_cmp_targets_prem),
        Cmp(R.string.about_cmp_template_t, R.string.about_cmp_template_free, R.string.about_cmp_template_prem),
        Cmp(R.string.about_cmp_simsend_t, R.string.about_cmp_simsend_free, R.string.about_cmp_simsend_prem),
        Cmp(R.string.about_cmp_name_t, R.string.about_cmp_name_free, R.string.about_cmp_name_prem),
        Cmp(R.string.about_cmp_toggle_t, R.string.about_cmp_toggle_free, R.string.about_cmp_toggle_prem),
        Cmp(R.string.about_cmp_edit_t, R.string.about_cmp_edit_free, R.string.about_cmp_edit_prem),
        Cmp(R.string.about_cmp_log_t, R.string.about_cmp_log_free, R.string.about_cmp_log_prem)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdge(b.root, b.topBar, b.scroll)

        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        b.versionText.text = getString(R.string.about_version, Fa.digits(ver))

        rows.forEach { r ->
            val v = ItemCompareRowBinding.inflate(LayoutInflater.from(this), b.compareList, false)
            v.cmpTitle.setText(r.title)
            v.cmpFree.setText(r.free)
            v.cmpPremium.setText(r.prem)
            b.compareList.addView(v.root)
        }

        renderPlans()
        LicenseManager.refreshPlans(this) { if (!isFinishing && !isDestroyed) renderPlans() }
        b.btnSub.setOnClickListener {
            startActivity(android.content.Intent(this, SubscriptionActivity::class.java))
        }
        b.btnPrivacy.setOnClickListener { showPrivacy() }
    }

    /** فهرست طرح‌ها دقیقاً از روی تعریف آن‌ها در کافه‌بازار (عنوان، مبلغ، مدت، توضیح) */
    private fun renderPlans() {
        val saving = Plan.yearlySavingPercent(this)
        val lines = Plan.values().joinToString("\n") { p ->
            val i = p.info(this)
            val sb = StringBuilder("• ").append(i.title).append(": ").append(i.priceText)
                .append(" · ").append(p.duration(this))
            if (p == Plan.YEARLY && saving != null) sb.append(" (").append(getString(R.string.about_plan_saving, Fa.num(saving))).append(")")
            if (i.description.isNotBlank()) sb.append("\n   ").append(i.description)
            sb.toString()
        }
        b.plansText.text = getString(R.string.about_plans, lines)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
