package ir.smsforward

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import ir.smsforward.databinding.ActivityMainBinding
import ir.smsforward.databinding.ItemRuleBinding

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    override val rootView: View get() = b.root

    /** true وقتی مقدارها از کد تنظیم می‌شوند تا شنونده‌ها دوباره ذخیره نکنند */
    private var binding = false

    /** تعداد قانون‌هایی که نمایش داده می‌شوند (صفحه‌بندی) */
    private var shown = PAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        applyEdgeToEdge(b.root, b.topBar, b.scroll)
        prefs = Prefs(this)

        b.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked) tryEnable() else {
                // خاموش‌شدن کلید اصلی یعنی همه‌ی قانون‌ها هم غیرفعال می‌شوند
                if (!prefs.disableAll()) {
                    showErrors(R.string.error_title, listOf(getString(R.string.err_save_failed)))
                }
                render()
            }
        }
        b.btnAddRule.setOnClickListener {
            if (!Premium.isActive(this) && prefs.rules().size >= Premium.FREE_MAX_RULES) {
                showPremium(Feature.MULTI_RULE)
            } else {
                openEditor(null, false)
            }
        }
        b.btnMoreRules.setOnClickListener {
            shown += PAGE
            render()
        }
        b.planPill.setOnClickListener { openSubscription() }
        b.cardUpgrade.setOnClickListener { openSubscription() }
        b.btnNews.setOnClickListener { openStore() }

        // دسترسی‌ها همان اول و فقط یک بار در هر اجرای تازه گرفته می‌شوند؛ هر کدام با پنجره‌ی جدا
        if (savedInstanceState == null) b.root.post { if (!isFinishing) askStartupPermissions() }
    }

    override fun onResume() {
        super.onResume()
        Premium.reconcile(this)
        // بی‌صدا از بازار می‌پرسیم؛ این‌طور اطلاعات قبلی اشتراک (بعد از نصب مجدد) هم برمی‌گردد
        LicenseManager.sync(this) {
            if (!isFinishing && !isDestroyed) render()
        }
        // مبلغ و مدت طرح‌ها از بازار به‌روز می‌شود (فقط اگر اطلاعات کهنه باشد)
        LicenseManager.refreshPlans(this) { if (!isFinishing && !isDestroyed) render() }
        // اگر کاربر دسترسی‌ها را از تنظیمات سیستم لغو کرده باشد
        if (prefs.masterWanted && !hasSmsPermissions()) {
            prefs.disableAll()
            snack(R.string.perm_revoked)
        }
        render()
    }

    override fun onTick(remainingMs: Long?) {
        // تایمر پایان اشتراک در صفحه‌ی اول (هر ثانیه)
        val cur = SubscriptionStore.current(this)
        if (remainingMs != null && cur != null) {
            b.planPillText.text =
                getString(R.string.plan_pill_active_timer, cur.plan.title(this), Countdown.format(this, remainingMs))
        }
    }

    override fun onSmsPermissionsDenied() = render()

    override fun onPremiumChanged() = render()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_menu -> {
            MenuSheet.show(
                this, Premium.isActive(this),
                MenuSheet.Actions(
                    onSubscription = { openSubscription() },
                    onLog = { openLog() },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onPrivacy = { showPrivacy() },
                    onAbout = { startActivity(Intent(this, AboutActivity::class.java)) }
                )
            )
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openSubscription() = startActivity(Intent(this, SubscriptionActivity::class.java))

    /** گزارش ارسال‌ها مخصوص اشتراک است */
    private fun openLog() {
        if (Premium.isActive(this)) startActivity(Intent(this, LogActivity::class.java))
        else showPremium(Feature.LOG)
    }

    private fun openStore() {
        val url = prefs.newsUrl
        val i = when {
            LicenseManager.bazaarInstalled(this) ->
                Intent(Intent.ACTION_VIEW, Uri.parse("bazaar://details?id=$packageName")).setPackage("com.farsitel.bazaar")
            url.isNotBlank() -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
            else -> return
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            snack(R.string.upd_no_browser)
        }
    }

    private fun openEditor(id: String?, enableMaster: Boolean) {
        startActivity(Intent(this, RuleActivity::class.java).apply {
            if (id != null) putExtra(RuleActivity.EXTRA_RULE_ID, id)
            if (enableMaster) putExtra(RuleActivity.EXTRA_ENABLE_MASTER, true)
        })
    }

    // ------------------------------------------------------------------ دسترسی‌های ابتدای برنامه

    /**
     * برای هر دسترسی یک پنجره‌ی جدا: توضیح ← پنجره‌ی خود گوشی ← دسترسی بعدی.
     * دسترسی‌هایی که برای همیشه رد شده‌اند یا کاربر اختیاری‌شان را رد کرده دوباره پرسیده نمی‌شوند.
     */
    private fun askStartupPermissions() {
        val steps = Perms.startup(Premium.isActive(this)).filter {
            !granted(it.permission) && !permBlocked(it) && !(!it.required && prefs.permSkipped(it.permission))
        }
        if (steps.isEmpty()) return
        runPermissionFlow(steps) { render() }
    }

    // ------------------------------------------------------------------ نمایش

    private fun render() {
        val premium = Premium.isActive(this)
        val multi = SimInfo.multi(this)
        val on = prefs.enabled
        val rules = prefs.rules()
        val runCount = Premium.runnable(this, rules).size

        binding = true
        b.switchEnabled.isChecked = on
        binding = false

        b.statusSubtitle.text = when {
            !on -> getString(R.string.status_off)
            runCount == 0 -> getString(R.string.status_no_active)
            else -> getString(R.string.status_on, Fa.num(runCount))
        }

        renderPlan(premium)

        // نسخه‌ی جدید اختیاری
        val newsOn = prefs.newsCode > UpdateChecker.installedCode(this) && UpdateGate.state(this) == UpdateGate.State.OK
        b.cardNews.isVisible = newsOn
        if (newsOn) {
            val name = prefs.newsName.ifBlank { prefs.newsCode.toString() }
            b.newsTitle.text = getString(R.string.news_title, name)
            val ch = prefs.newsChanges
            b.newsText.text = if (ch.isEmpty()) getString(R.string.promo_update_text)
            else getString(R.string.news_text, ch.joinToString("\n") { "• $it" })
        }

        // تا وقتی ارسال خودکار روشن نشده، قانون‌ها اصلاً نمایش داده نمی‌شوند
        b.rulesSection.isVisible = on
        b.rulesHint.isVisible = !on

        val locked = !premium && rules.size >= Premium.FREE_MAX_RULES
        b.btnAddRule.setIconResource(if (locked) R.drawable.ic_lock else R.drawable.ic_add)
        renderRules(rules, on, premium, multi)
    }

    private fun renderPlan(premium: Boolean) {
        if (premium) {
            val cur = SubscriptionStore.current(this)
            b.planPillIcon.setImageResource(R.drawable.ic_verified)
            val left = Countdown.remaining(this)
            b.planPillText.text = if (cur != null && left != null) {
                getString(R.string.plan_pill_active_timer, cur.plan.title(this), Countdown.format(this, left))
            } else getString(R.string.plan_pill_active_simple)
            b.cardUpgrade.isVisible = false
        } else {
            b.planPillIcon.setImageResource(R.drawable.ic_crown)
            b.planPillText.setText(R.string.plan_pill_free)
            b.cardUpgrade.isVisible = true
            b.upgradeText.text = getString(R.string.buy_text, Plan.summary(this))
        }
    }

    private fun featureOf(u: Use?): Feature = when (u) {
        Use.SIM_RECV -> Feature.SIM_RECV
        Use.SIM_SEND -> Feature.SIM_SEND
        Use.SENDERS -> Feature.SENDERS
        Use.MULTI_TARGET -> Feature.TARGETS
        Use.TEMPLATE -> Feature.TEMPLATE
        else -> Feature.MULTI_RULE
    }

    private fun blockMessage(block: Block): String {
        val uses = block.uses.joinToString("، ") { getString(it.label) }
        return when (block.kind) {
            Block.Kind.EXPIRED -> getString(R.string.block_expired, uses)
            Block.Kind.PREMIUM_USE -> getString(R.string.block_premium_use, uses)
            Block.Kind.EXTRA_RULE -> getString(R.string.block_extra_rule)
            Block.Kind.USER_OFF -> getString(R.string.block_user_off)
        }
    }

    private fun renderRules(rules: List<Rule>, masterOn: Boolean, premium: Boolean, multi: Boolean) {
        b.emptyRules.isVisible = rules.isEmpty()
        b.rulesList.removeAllViews()
        val visible = rules.take(shown)
        b.btnMoreRules.isVisible = rules.size > visible.size
        b.btnMoreRules.text = getString(R.string.rules_more, Fa.num(rules.size - visible.size))
        visible.forEachIndexed { index, rule ->
            val row = ItemRuleBinding.inflate(layoutInflater, b.rulesList, false)
            row.ruleName.text = rule.displayName(this, index)

            val senders = if (rule.allSenders) getString(R.string.sum_all_senders)
            else getString(R.string.sum_n_senders, Fa.num(rule.senders.size))
            row.ruleSenders.text = getString(R.string.sum_from, senders)

            val shownTargets = rule.targets.take(3).joinToString("، ") { bidi(it) }
            val more = if (rule.targets.size > 3) getString(R.string.sum_more, Fa.num(rule.targets.size - 3)) else ""
            row.ruleTargets.text = getString(R.string.sum_to, Fa.num(rule.targets.size), shownTargets + more)

            // سیم ۱ / سیم ۲ فقط وقتی گوشی دو سیم‌کارته و هر دو سیم روشن است (یا قانون از آن‌ها استفاده می‌کند)
            val simInfo = multi || rule.recvSim != 0 || rule.sendSim != 0
            row.ruleSims.isVisible = simInfo
            if (simInfo) {
                row.ruleSims.text = getString(R.string.sum_sims, simName(rule.recvSim, true), simName(rule.sendSim, false))
            }

            val block = Premium.blockOf(this, rule, rules, premium, multi)
            row.blockBox.isVisible = block != null
            if (block != null) {
                row.blockText.text = blockMessage(block)
                val feat = when (block.kind) {
                    Block.Kind.USER_OFF -> Feature.TOGGLE
                    Block.Kind.EXPIRED -> null
                    else -> featureOf(block.uses.firstOrNull())
                }
                row.btnBlockBuy.setOnClickListener { showPremium(feat) }
            }

            binding = true
            row.ruleSwitch.isChecked = if (premium) rule.enabled else (block == null)
            binding = false
            row.ruleSwitch.isEnabled = masterOn
            row.root.alpha = if (row.ruleSwitch.isChecked) 1f else 0.7f
            if (premium) {
                row.ruleSwitch.setOnCheckedChangeListener { _, checked ->
                    if (binding) return@setOnCheckedChangeListener
                    val wasOn = prefs.enabled
                    if (!prefs.setRuleEnabled(rule.id, checked)) {
                        showErrors(R.string.error_title, listOf(getString(R.string.err_save_failed)))
                    }
                    if (wasOn && !prefs.enabled) snack(R.string.auto_off_no_rules)
                    render()
                }
            } else {
                // روشن/خاموش‌کردن قانون مخصوص اشتراک است
                row.ruleSwitch.setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) showPremium(Feature.TOGGLE)
                    true
                }
            }
            row.ruleDelete.contentDescription = getString(R.string.rule_delete)
            row.ruleDelete.setOnClickListener { confirmDelete(rule, rule.displayName(this, index)) }
            // ویرایش قانون مخصوص اشتراک است؛ حذف همیشه آزاد است
            row.root.setOnClickListener {
                if (premium) openEditor(rule.id, false) else showPremium(Feature.EDIT_RULE)
            }
            b.rulesList.addView(row.root)
        }
    }

    private fun confirmDelete(rule: Rule, name: String) {
        AppDialog.confirm(this, name, getString(R.string.rule_delete_confirm), getString(R.string.rule_delete), dangerous = true) {
            val wasOn = prefs.enabled
            if (!prefs.deleteRule(rule.id)) {
                showErrors(R.string.error_title, listOf(getString(R.string.err_save_failed)))
            }
            if (wasOn && !prefs.enabled) snack(R.string.auto_off_no_rules)
            render()
        }
    }

    private companion object {
        const val PAGE = 10
    }

    private fun simName(v: Int, recv: Boolean): String = when (v) {
        1 -> getString(R.string.chip_sim1)
        2 -> getString(R.string.chip_sim2)
        else -> getString(if (recv) R.string.chip_all_sims else R.string.chip_send_same)
    }

    // ------------------------------------------------------------------ عملیات

    /**
     * روشن‌کردن ارسال خودکار: اگر قانونی وجود دارد همان‌ها برمی‌گردند؛
     * وگرنه صفحه‌ی «قانون جدید» باز می‌شود و کلید فقط با ذخیره‌شدن آن روشن می‌شود.
     */
    private fun tryEnable() {
        withSmsPermissions {
            if (!prefs.enableWithExisting()) openEditor(null, true)
            render()
        }
        render() // تا ذخیره‌شدن قانون، کلید در حالت خاموش می‌ماند
    }
}
