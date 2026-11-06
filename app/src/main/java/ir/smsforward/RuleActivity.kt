package ir.smsforward

import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import ir.smsforward.databinding.ActivityRuleBinding

/**
 * ساخت و ویرایش یک قانون. هیچ‌چیز خودکار ذخیره نمی‌شود؛ فقط با دکمه‌ی «ذخیره»،
 * و اگر خطایی باشد، همه‌ی خطاها با هم و دقیق نمایش داده می‌شود.
 */
class RuleActivity : BaseActivity() {

    private lateinit var b: ActivityRuleBinding
    private lateinit var prefs: Prefs

    override val rootView: View get() = b.root

    private var editing: Rule? = null
    private var ruleId = ""
    private var initialSnapshot = ""
    private var ruleEnabled = true

    /** اشتراک فعال است؟ (در onCreate/onResume/بعد از خرید به‌روز می‌شود) */
    private var premium = false

    /** گوشی دو سیمکارتِ روشن دارد؟ فقط آن‌وقت بخش‌های سیم ۱/۲ نشان داده می‌شود */
    private var multi = false
    private var reverting = false

    /** از کلید «ارسال خودکار» آمده‌ایم: ذخیره‌ی این قانون، ارسال خودکار را روشن می‌کند */
    private var enableMaster = false

    private lateinit var targetAdapter: SuggestAdapter
    private lateinit var senderAdapter: SuggestAdapter

    private val recvIds get() = listOf(b.recvAll.id, b.recvSim1.id, b.recvSim2.id)
    private val sendIds get() = listOf(b.sendSame.id, b.sendSim1.id, b.sendSim2.id)
    private val filterIds get() = listOf(b.filterAll.id, b.filterListed.id)

    private enum class Field { NAME, SENDERS, TARGETS, TEMPLATE }

    /** نتیجه‌ی خواندن فرم: قانون ساخته‌شده و خطاها (به تفکیک بخش) */
    private class Parsed(val rule: Rule, val errors: Map<Field, List<String>>) {
        val all get() = errors.values.flatten()
    }

    // ------------------------------------------------------------------ چرخه‌ی حیات

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRuleBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdgeBar(b.root, b.topBar, b.bottomBar)
        prefs = Prefs(this)
        premium = Premium.isActive(this)
        multi = SimInfo.multi(this)

        enableMaster = intent.getBooleanExtra(EXTRA_ENABLE_MASTER, false)
        val id = intent.getStringExtra(EXTRA_RULE_ID)
        editing = id?.let { prefs.rule(it) }
        // ویرایش قانون و ساخت قانونِ دوم فقط با اشتراک است (حذف آزاد است و در صفحه‌ی اصلی هم هست)
        if (!premium && (editing != null || prefs.rules().size >= Premium.FREE_MAX_RULES)) {
            // صفحه‌ی اصلی پنجره‌ی اشتراک را نشان می‌دهد؛ اینجا فقط راه را می‌بندیم
            finish()
            return
        }
        ruleId = editing?.id ?: java.util.UUID.randomUUID().toString()
        supportActionBar?.setTitle(if (editing == null) R.string.rule_new else R.string.rule_edit)

        setupSuggestions()
        fill(editing ?: Rule(id = ruleId, recvSim = if (!premium && multi) 1 else 0, template = Template.defaultFor(multi)))
        setupListeners()
        applyLocks()
        initialSnapshot = snapshot()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        val now = Premium.isActive(this)
        if (now != premium) onPremiumChanged()
        refreshSuggestions()
    }

    override fun onPremiumChanged() {
        premium = Premium.isActive(this)
        multi = SimInfo.multi(this)
        applyLocks()
        // اشتراک وسط ویرایش تمام شد: ویرایش قانون و قانون‌های بیشتر از یکی بسته می‌شود
        if (!premium && (editing != null || prefs.rules().size >= Premium.FREE_MAX_RULES)) finish()
    }

    override fun onOptionalPermissionGranted() = refreshSuggestions()

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            confirmLeave()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDelete() {
        AppDialog.confirm(
            this, getString(R.string.rule_delete), getString(R.string.rule_delete_confirm),
            getString(R.string.rule_delete), dangerous = true
        ) {
            val wasOn = prefs.enabled
            if (prefs.deleteRule(ruleId)) {
                if (wasOn && !prefs.enabled) {
                    android.widget.Toast.makeText(this, R.string.auto_off_no_rules, android.widget.Toast.LENGTH_LONG).show()
                }
                finish()
            } else showErrors(R.string.error_title, listOf(getString(R.string.err_save_failed)))
        }
    }

    // ------------------------------------------------------------------ پر کردن فرم و شنونده‌ها

    private fun fill(r: Rule) {
        ruleEnabled = r.enabled || !premium
        b.nameInput.setText(r.name)
        b.switchRule.isChecked = ruleEnabled
        b.recvGroup.check(recvIds[r.recvSim.coerceIn(0, 2)])
        b.filterGroup.check(filterIds[if (r.allSenders || (!premium && !Premium.FREE_SPECIFIC_SENDERS)) 0 else 1])
        b.sendersInput.setText(r.senders.joinToString(", "))
        b.targetsInput.setText(r.targets.joinToString(", "))
        b.templateInput.setText(if (premium) r.template else Template.defaultFor(multi))
        b.sendGroup.check(sendIds[if (premium) r.sendSim.coerceIn(0, 2) else 0])
        renderEnabled()
        renderFilter()
        renderPreview()
    }

    private fun setupListeners() {
        b.switchRule.setOnCheckedChangeListener { _, checked ->
            ruleEnabled = checked
            renderEnabled()
        }
        b.filterGroup.setOnCheckedStateChangeListener { g, ids ->
            if (!premium && !reverting && !Premium.FREE_SPECIFIC_SENDERS && ids.firstOrNull() == b.filterListed.id) {
                revert(g, b.filterAll.id)
                showPremium(Feature.SENDERS)
            }
            renderFilter()
        }
        b.recvGroup.setOnCheckedStateChangeListener { g, ids ->
            val id = ids.firstOrNull()
            if (!premium && !reverting && multi && id != null && id != b.recvSim1.id) {
                revert(g, b.recvSim1.id)
                showPremium(Feature.SIM_RECV)
            }
        }
        b.sendGroup.setOnCheckedStateChangeListener { g, ids ->
            val id = ids.firstOrNull()
            if (!premium && !reverting && id != null && id != b.sendSame.id) {
                revert(g, b.sendSame.id)
                showPremium(Feature.SIM_SEND)
            }
        }

        // بخش‌های قفل: لمس = پنجره‌ی اشتراک (کلیک هم برای دسترس‌پذیری)
        lockView(b.nameInput, Feature.EDIT_RULE)
        lockView(b.switchRule, Feature.TOGGLE)
        lockView(b.templateInput, Feature.TEMPLATE)
        lockView(b.btnPickTargets, Feature.TARGETS)
        b.freeNote.setOnClickListener { showPremium(null) }

        // پاک شدن خطای هر فیلد با شروع ویرایش
        b.nameInput.doAfterTextChanged { b.nameLayout.error = null }
        b.sendersInput.doAfterTextChanged { b.sendersLayout.error = null }
        b.targetsInput.doAfterTextChanged { b.targetsLayout.error = null }
        b.templateInput.doAfterTextChanged {
            b.templateLayout.error = null
            renderPreview()
        }

        mapOf(
            b.varSender to "{sender}",
            b.varBody to "{body}",
            b.varSim to "{sim}",
            b.varTime to "{time}",
            b.varDate to "{date}"
        ).forEach { (chip, token) ->
            chip.setOnClickListener {
                if (premium) insertToken(token) else showPremium(Feature.TEMPLATE)
            }
        }
        b.btnResetTemplate.setOnClickListener { b.templateInput.setText(Template.defaultFor(multi)) }
        b.btnTestSend.setOnClickListener { sendTest() }

        b.btnPickTargets.setOnClickListener {
            if (premium) openPicker(forTargets = true, start = Sources.Kind.CONTACT) else showPremium(Feature.TARGETS)
        }
        b.btnPickContacts.setOnClickListener { openPicker(forTargets = false, start = Sources.Kind.CONTACT) }
        b.btnPickSms.setOnClickListener { openPicker(forTargets = false, start = Sources.Kind.SMS) }

        b.btnSave.setOnClickListener { save() }
        b.btnDelete.isVisible = editing != null
        b.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun revert(g: com.google.android.material.chip.ChipGroup, id: Int) {
        reverting = true
        g.check(id)
        reverting = false
    }

    /** روی دیدِ قفل، لمس پنجره‌ی اشتراک را باز می‌کند و ویرایش را نمی‌گذارد. */
    private fun lockView(v: View, feature: Feature) {
        v.setOnTouchListener { _, e ->
            if (premium) false
            else {
                if (e.action == MotionEvent.ACTION_UP) showPremium(feature)
                true
            }
        }
        v.setOnClickListener { if (!premium) showPremium(feature) }
    }

    private fun badge(v: View, show: Boolean) {
        v.isVisible = show
    }

    /**
     * وضعیت قفل بخش‌ها را با وضعیت اشتراک هماهنگ می‌کند (دوباره‌صدازدن بی‌خطر است).
     * رایگان: نام خودکار، همیشه روشن، سیم ۱، فرستنده «همه»، یک شماره‌ی تایپی، قالب پیش‌فرض، ارسال با همان سیم.
     */
    private fun applyLocks() {
        val free = !premium
        b.freeNote.isVisible = free

        // بخش‌های «دریافت از سیم» و «ارسال با» همیشه نمایش داده می‌شوند
        b.recvSection.isVisible = true
        b.sendCard.isVisible = true
        // «ارسال با» مخصوص اشتراک است: برای رایگان کم‌رنگ و قفل (لمس = پنجره‌ی اشتراک)
        b.sendCard.alpha = if (free) 0.6f else 1f

        badge(b.badgeInfo, free)
        badge(b.badgeRecv, free)
        badge(b.badgeSenders, free && !Premium.FREE_SPECIFIC_SENDERS)
        badge(b.badgeTargets, free)
        badge(b.badgeTemplate, free)
        badge(b.badgeSend, free)

        // نام
        b.nameInput.isFocusable = !free
        b.nameInput.isFocusableInTouchMode = !free
        b.nameInput.isCursorVisible = !free
        b.nameLayout.hint = getString(if (free) R.string.hint_rule_name_auto else R.string.hint_rule_name)
        b.nameLayout.alpha = if (free) 0.6f else 1f

        // کلید روشن/خاموش
        if (free) {
            ruleEnabled = true
            b.switchRule.isChecked = true
        }
        b.switchRule.alpha = if (free) 0.6f else 1f

        // قالب
        b.templateInput.isFocusable = !free
        b.templateInput.isFocusableInTouchMode = !free
        b.templateInput.isCursorVisible = !free
        b.templateCard.alpha = if (free) 0.6f else 1f
        b.btnResetTemplate.isVisible = !free
        if (free) {
            val def = Template.defaultFor(multi)
            if (b.templateInput.text.toString() != def) b.templateInput.setText(def)
        }

        // مقصد: یک شماره‌ی تایپی؛ مخاطبین قفل
        b.btnPickTargets.alpha = if (free) 0.6f else 1f
        b.targetsLayout.setHelperText(getString(if (free) R.string.helper_targets_free else R.string.helper_targets))
        if (free) {
            b.targetsInput.setAdapter(null as SuggestAdapter?)
        } else {
            b.targetsInput.setAdapter(targetAdapter)
            refreshSuggestions()
        }

        // فرستنده: برای رایگان «همه»
        if (free && !Premium.FREE_SPECIFIC_SENDERS) b.filterGroup.check(b.filterAll.id)
        // سیم‌ها
        if (free && multi) {
            revert(b.recvGroup, b.recvSim1.id)
            revert(b.sendGroup, b.sendSame.id)
        }
        renderEnabled()
        renderFilter()
    }

    /** کلید این قانون فقط وقتی کار می‌کند که ارسال خودکار روشن باشد (یا با همین قانون روشن شود) */
    private fun switchUsable() = enableMaster || prefs.enabled

    /** وقتی قانون خاموش است، همه‌ی بخش‌های زیرمجموعه خاموش و کم‌رنگ می‌شوند. */
    private fun renderEnabled() {
        val usable = switchUsable()
        b.switchRule.isEnabled = usable || !premium
        b.ruleStatus.setText(
            when {
                !usable -> R.string.rule_status_master_off
                ruleEnabled -> R.string.rule_status_on
                else -> R.string.rule_status_off
            }
        )
        // وقتی ارسال خودکار خاموش است قانون همیشه غیرفعال است ولی باید قابل ویرایش بماند
        val bodyOn = ruleEnabled || !usable
        setTreeEnabled(b.ruleBody, bodyOn)
        b.ruleBody.alpha = if (bodyOn) 1f else 0.45f
    }

    private fun setTreeEnabled(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setTreeEnabled(v.getChildAt(i), on)
    }

    private fun renderFilter() {
        val listed = b.filterGroup.checkedChipId == b.filterListed.id
        b.sendersLayout.isVisible = listed
        b.filterPickRow.isVisible = listed
    }

    private fun renderPreview() {
        val text = Template.render(
            b.templateInput.text?.toString().orEmpty(),
            getString(R.string.test_sender), getString(R.string.test_body), "1", System.currentTimeMillis()
        )
        b.previewText.text = text
        val (parts, chars) = Template.segments(text)
        b.previewMeta.text = getString(R.string.preview_meta, chars, parts)
    }

    private fun insertToken(token: String) {
        val et = b.templateInput
        val editable = et.text ?: return
        val start = maxOf(0, minOf(et.selectionStart, et.selectionEnd))
        val end = maxOf(0, maxOf(et.selectionStart, et.selectionEnd))
        editable.replace(start, end, token)
        et.requestFocus()
    }

    // ------------------------------------------------------------------ پیشنهاد و انتخاب

    private fun setupSuggestions() {
        targetAdapter = SuggestAdapter(this)
        b.targetsInput.setAdapter(targetAdapter)
        b.targetsInput.setTokenizer(SenderTokenizer())

        senderAdapter = SuggestAdapter(this)
        b.sendersInput.setAdapter(senderAdapter)
        b.sendersInput.setTokenizer(SenderTokenizer())
    }

    private fun refreshSuggestions() {
        Sources.load(this, Sources.Kind.CONTACT, force = false) { updateSuggestionData() }
        Sources.load(this, Sources.Kind.SMS, force = false) { updateSuggestionData() }
    }

    private fun updateSuggestionData() {
        val contacts = Sources.cached(Sources.Kind.CONTACT)
        targetAdapter.setData(contacts)
        senderAdapter.setData(contacts + Sources.cached(Sources.Kind.SMS))
    }

    private fun openPicker(forTargets: Boolean, start: Sources.Kind) {
        withOptionalPermission(start) {
            val input = if (forTargets) b.targetsInput else b.sendersInput
            PickerDialog(
                this,
                getString(if (forTargets) R.string.picker_title_target else R.string.picker_title_filter),
                multi = true,
                kinds = if (forTargets) listOf(Sources.Kind.CONTACT)
                else listOf(Sources.Kind.CONTACT, Sources.Kind.SMS),
                start = start,
                initial = Forwarder.parseList(input.text?.toString().orEmpty()),
                ensure = ::withOptionalPermission
            ) { values ->
                input.setText(values.joinToString(", "))
                input.setSelection(input.length())
            }.show()
        }
    }

    // ------------------------------------------------------------------ خواندن و اعتبارسنجی

    /** وضعیت خام فرم، برای تشخیص تغییرات ذخیره‌نشده */
    private fun snapshot(): String = listOf(
        b.nameInput.text.toString(), ruleEnabled, b.recvGroup.checkedChipId, b.filterGroup.checkedChipId,
        b.sendersInput.text.toString(), b.targetsInput.text.toString(),
        b.templateInput.text.toString(), b.sendGroup.checkedChipId
    ).joinToString("\u0001")

    /** فرم را می‌خواند و همه‌ی خطاها را (نه فقط اولین خطا) جمع می‌کند. */
    private fun parse(): Parsed {
        val errors = LinkedHashMap<Field, MutableList<String>>()
        fun err(f: Field, msg: String) = errors.getOrPut(f) { ArrayList() }.add(msg)

        val name = if (premium) b.nameInput.text.toString().trim() else ""

        // فرستنده‌ها
        val allSenders = b.filterGroup.checkedChipId != b.filterListed.id
        val senders = Forwarder.parseList(b.sendersInput.text.toString())
            .distinctBy { Forwarder.key(it) }
        if (!allSenders && senders.isEmpty()) err(Field.SENDERS, getString(R.string.err_senders_empty))

        // مقصدها: هر شماره جدا بررسی می‌شود
        val rawTargets = Forwarder.parseList(b.targetsInput.text.toString())
        val targets = ArrayList<String>()
        if (rawTargets.isEmpty()) err(Field.TARGETS, getString(R.string.err_targets_empty))
        if (!premium && rawTargets.size > Premium.FREE_MAX_TARGETS) err(Field.TARGETS, getString(R.string.err_targets_free_one))
        rawTargets.forEach { raw ->
            val n = Forwarder.normalizeTarget(raw)
            when {
                !Forwarder.isValidTarget(n) -> err(Field.TARGETS, getString(R.string.err_target_invalid, bidi(raw)))
                targets.any { Forwarder.same(it, n) } -> err(Field.TARGETS, getString(R.string.err_target_dup, bidi(raw)))
                else -> targets.add(n)
            }
        }
        if (!allSenders) {
            targets.forEach { t ->
                if (senders.any { Forwarder.same(it, t) }) {
                    err(Field.TARGETS, getString(R.string.err_target_is_sender, bidi(t)))
                }
            }
        }

        // قالب
        val template = b.templateInput.text.toString()
        val known = Template.TOKENS.toSet()
        Regex("\\{[^{}]*\\}").findAll(template).map { it.value }.distinct().forEach {
            if (it !in known) err(Field.TEMPLATE, getString(R.string.err_template_unknown, it))
        }
        if (template.isBlank()) err(Field.TEMPLATE, getString(R.string.err_template_empty))
        else if (!template.contains("{body}")) err(Field.TEMPLATE, getString(R.string.err_template_no_body))

        // بخش سیم نشان داده نشده (گوشی تک‌سیم): مقدار قبلی دست‌نخورده می‌ماند
        val recvSim = if (b.recvSection.isVisible) recvIds.indexOf(b.recvGroup.checkedChipId).coerceAtLeast(0)
        else editing?.recvSim ?: 0
        val sendSim = if (b.sendCard.isVisible) sendIds.indexOf(b.sendGroup.checkedChipId).coerceAtLeast(0)
        else editing?.sendSim ?: 0
        val rule = Rule(
            id = ruleId, name = name, enabled = ruleEnabled || !premium,
            recvSim = recvSim,
            allSenders = allSenders, senders = senders, targets = targets,
            sendSim = sendSim,
            template = template
        )
        return Parsed(rule, errors)
    }

    /** خطاها را زیر هر فیلد و همه را با هم در یک پنجره نشان می‌دهد. */
    private fun showFieldErrors(p: Parsed, only: Set<Field>? = null) {
        fun set(f: Field, layout: com.google.android.material.textfield.TextInputLayout) {
            val m = if (only == null || f in only) p.errors[f] else null
            layout.error = m?.joinToString("\n")
        }
        set(Field.NAME, b.nameLayout)
        set(Field.SENDERS, b.sendersLayout)
        set(Field.TARGETS, b.targetsLayout)
        set(Field.TEMPLATE, b.templateLayout)
        val sections = linkedMapOf(
            Field.NAME to R.string.section_info, Field.SENDERS to R.string.section_which,
            Field.TARGETS to R.string.section_targets, Field.TEMPLATE to R.string.section_template
        )
        val lines = ArrayList<String>()
        p.errors.forEach { (f, msgs) ->
            if (only == null || f in only) msgs.forEach { lines += getString(R.string.err_in_section, getString(sections.getValue(f)), it) }
        }
        showErrors(R.string.error_title, lines)
    }

    // ------------------------------------------------------------------ عملیات

    private fun save() {
        val parsed = try {
            parse()
        } catch (e: Exception) {
            showErrors(R.string.error_title, listOf(getString(R.string.err_unexpected, e.message ?: e.javaClass.simpleName)))
            return
        }
        if (parsed.all.isNotEmpty()) {
            showFieldErrors(parsed)
            return
        }
        if (!premium) {
            // دفاع دوم: هر چیزی که از امکانات ویژه باشد ذخیره نمی‌شود
            val used = Premium.uses(parsed.rule, multi)
            if (used.isNotEmpty()) {
                showPremium(featureOf(used.first()))
                return
            }
        }
        if (enableMaster && !ruleEnabled) {
            showErrors(R.string.error_title, listOf(getString(R.string.err_first_rule_enabled)))
            return
        }
        try {
            val existing = prefs.rules()
            val wasOn = prefs.enabled
            var rule = parsed.rule
            // وقتی ارسال خودکار خاموش است هیچ قانونی فعال نمی‌ماند (مگر قانون اولی که آن را روشن می‌کند)
            if (!enableMaster && !wasOn) rule = rule.copy(enabled = false)
            if (rule.name.isBlank()) {
                // نام خالی: همان نام پیش‌فرضِ شماره‌دار ذخیره می‌شود
                val index = existing.indexOfFirst { it.id == rule.id }.let { if (it >= 0) it else existing.size }
                rule = rule.copy(name = getString(R.string.rule_default_name, index + 1))
            }
            if (!prefs.upsertRule(rule, enableMaster)) {
                showErrors(R.string.error_title, listOf(getString(R.string.err_save_failed)))
                return
            }
            if (wasOn && !prefs.enabled) {
                android.widget.Toast.makeText(this, R.string.auto_off_no_rules, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            showErrors(R.string.error_title, listOf(getString(R.string.err_unexpected, e.message ?: e.javaClass.simpleName)))
            return
        }
        initialSnapshot = snapshot()
        android.widget.Toast.makeText(this, R.string.rule_saved, android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun featureOf(u: Use): Feature = when (u) {
        Use.SIM_RECV -> Feature.SIM_RECV
        Use.SIM_SEND -> Feature.SIM_SEND
        Use.SENDERS -> Feature.SENDERS
        Use.MULTI_TARGET -> Feature.TARGETS
        Use.TEMPLATE -> Feature.TEMPLATE
        Use.EXTRA_RULE -> Feature.MULTI_RULE
    }

    private fun sendTest() {
        val parsed = parse()
        // برای ارسال آزمایشی فقط مقصدها و قالب لازم است
        val relevant = setOf(Field.TARGETS, Field.TEMPLATE)
        if (parsed.errors.any { it.key in relevant }) {
            showFieldErrors(parsed, relevant)
            return
        }
        withSmsPermissions {
            val name = parsed.rule.name.ifBlank { getString(R.string.rule_default_name, 1) }
            val n = Forwarder.forward(
                this, parsed.rule, name, getString(R.string.test_sender), getString(R.string.test_body),
                subId = -1, slot = 0, test = true
            )
            if (n > 0) snack(R.string.test_done) else showErrors(R.string.error_title, listOf(getString(R.string.err_test_failed)))
        }
    }

    private fun confirmLeave() {
        if (snapshot() == initialSnapshot) {
            finish()
            return
        }
        AppDialog(this).icon(R.drawable.ic_warning)
            .title(R.string.unsaved_title)
            .message(R.string.unsaved_msg)
            .positive(R.string.unsaved_discard) { finish() }
            .negative(R.string.unsaved_keep)
            .destructive()
            .show()
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_ENABLE_MASTER = "enable_master"
    }
}
