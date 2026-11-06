package ir.smsforward

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.smsforward.Sources.Kind
import ir.smsforward.databinding.DialogPickerBinding
import ir.smsforward.databinding.ItemPickBinding

/**
 * پنجره‌ی انتخاب با جستجو و ورود دستی.
 * - [multi] = false: با لمس یک شماره، همان برگردانده می‌شود (شماره‌ی مقصد).
 * - [multi] = true: چند مورد تیک می‌خورد و با «تأیید» کل لیست برگردانده می‌شود (فرستنده‌ها).
 * [ensure] پیش از نمایش هر منبع، دسترسی لازم را (در صورت نیاز) می‌گیرد و بعد کار را ادامه می‌دهد.
 */
class PickerDialog(
    private val activity: AppCompatActivity,
    private val title: String,
    private val multi: Boolean,
    private val kinds: List<Kind>,
    private val start: Kind,
    initial: List<String>,
    private val ensure: (Kind, () -> Unit) -> Unit,
    private val onResult: (List<String>) -> Unit
) {

    private class Row(val title: String, val subtitle: String, val value: String, val manual: Boolean)

    private val b = DialogPickerBinding.inflate(LayoutInflater.from(activity))
    private val selected = ArrayList(initial)
    private var kind = start
    private var data: List<Sources.Entry> = emptyList()
    private var rows: List<Row> = emptyList()
    private var loadToken = 0
    private var loading = false
    private var dialog: AlertDialog? = null
    private val adapter = RowAdapter()

    fun show() {
        b.list.layoutManager = LinearLayoutManager(activity)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        b.tabs.isVisible = kinds.size > 1
        b.tabContacts.isVisible = Kind.CONTACT in kinds
        b.tabSms.isVisible = Kind.SMS in kinds
        b.tabs.check(if (start == Kind.CONTACT) b.tabContacts.id else b.tabSms.id)
        b.tabs.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            kind = if (id == b.tabContacts.id) Kind.CONTACT else Kind.SMS
            loadKind()
        }
        b.searchInput.doAfterTextChanged { refresh() }
        b.stateText.setOnClickListener { if (!Sources.has(activity, kind)) loadKind() }

        // عنوان صریحاً راست‌چین (عنوان پیش‌فرض پنجره‌ی متریال ممکن است چپ‌چین شود)
        val dp = activity.resources.displayMetrics.density
        val titleView = android.widget.TextView(activity).apply {
            text = title
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            gravity = android.view.Gravity.START
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val builder = MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_App_Dialog)
            .setCustomTitle(titleView)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
        if (multi) {
            builder.setPositiveButton(R.string.ok) { _, _ -> onResult(selected.toList()) }
        }
        val d = builder.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.setOnDismissListener { loadToken++ }
        dialog = d
        d.show()
        updateCount()
        loadKind()
    }

    private fun updateCount() {
        if (!multi) return
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text =
            activity.getString(R.string.picker_done_count, selected.size)
    }

    private fun loadKind() {
        val token = ++loadToken
        data = emptyList()
        loading = Sources.has(activity, kind)
        refresh()
        val k = kind
        ensure(k) {
            if (token == loadToken && dialog?.isShowing == true) {
                loading = true
                refresh()
                Sources.load(activity, k, force = true) { list ->
                    if (token == loadToken && dialog?.isShowing == true) {
                        loading = false
                        data = list
                        refresh()
                    }
                }
            }
        }
    }

    private fun isSelected(v: String) = selected.any { Forwarder.same(it, v) }

    private fun refresh() {
        val q = b.searchInput.text?.toString().orEmpty().trim()
        val list = ArrayList<Row>()

        // ردیف ورود دستی
        if (q.isNotEmpty()) {
            val value = if (Forwarder.isNumericLike(q)) Forwarder.normalizeTarget(q) else q
            val valid = if (multi) true else Forwarder.isValidTarget(value)
            val exists = data.any { Forwarder.same(it.value, value) } || (multi && isSelected(value))
            if (valid && !exists) {
                val label = activity.getString(
                    if (multi) R.string.picker_add_manual else R.string.picker_use_manual, q
                )
                list.add(Row(label, activity.getString(R.string.picker_manual_sub), value, true))
            }
        }

        // موارد دستیِ قبلی که در این منبع نیستند هم دیده شوند تا بشود برش‌داشت
        if (multi) {
            selected.filter { s -> data.none { Forwarder.same(it.value, s) } }
                .filter { q.isEmpty() || it.lowercase().contains(q.lowercase()) }
                .forEach { list.add(Row(it, activity.getString(R.string.picker_manual_sub), it, false)) }
        }

        data.filter { Sources.matches(it, q) }
            .forEach { list.add(Row(it.title, it.subtitle, it.value, false)) }

        rows = list
        adapter.notifyDataSetChanged()

        val msg = when {
            !Sources.has(activity, kind) -> R.string.picker_perm_needed
            loading -> R.string.picker_loading
            list.isEmpty() && q.isEmpty() ->
                if (kind == Kind.CONTACT) R.string.picker_empty_contacts else R.string.picker_empty_sms
            list.isEmpty() -> R.string.picker_no_match
            else -> 0
        }
        b.stateText.isVisible = msg != 0
        if (msg != 0) b.stateText.setText(msg)
    }

    private fun onRowClicked(pos: Int) {
        val row = rows.getOrNull(pos) ?: return
        if (!multi) {
            onResult(listOf(row.value))
            dialog?.dismiss()
            return
        }
        if (isSelected(row.value)) selected.removeAll { Forwarder.same(it, row.value) }
        else selected.add(row.value)
        updateCount()
        if (row.manual) b.searchInput.setText("") else adapter.notifyItemChanged(pos)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(val v: ItemPickBinding) : RecyclerView.ViewHolder(v.root)

        @Suppress("DEPRECATION")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vh = VH(ItemPickBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            vh.v.root.setOnClickListener {
                val p = vh.adapterPosition
                if (p != RecyclerView.NO_POSITION) onRowClicked(p)
            }
            return vh
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = rows[position]
            holder.v.title.text = r.title
            holder.v.subtitle.text = r.subtitle
            holder.v.check.isVisible = multi
            holder.v.check.isChecked = multi && isSelected(r.value)
            holder.v.root.visibility = View.VISIBLE
        }
    }
}
