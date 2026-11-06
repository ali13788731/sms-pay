package ir.smsforward

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ir.smsforward.databinding.ActivityLogBinding
import ir.smsforward.databinding.ItemLogBinding
import java.util.Calendar

/**
 * گزارش ارسال‌ها (مخصوص اشتراک): جستجو، فیلتر وضعیت و زمان، ترتیب و صفحه‌بندی.
 * هر بار فقط یک صفحه از پایگاه‌داده خوانده می‌شود، پس با هزاران ردیف هم روان است.
 */
class LogActivity : RtlActivity() {

    private lateinit var b: ActivityLogBinding
    private lateinit var db: LogDb
    private val adapter by lazy { Adapter() }

    private var query = ""
    private var status = 0          // 0 همه، 1 موفق، 2 ناموفق
    private var sinceKind = 0       // 0 همه، 1 امروز، 2 هفت روز، 3 سی روز
    private var newestFirst = true
    private var page = 0
    private var pages = 1

    private val searchRunnable = Runnable {
        query = b.searchInput.text?.toString().orEmpty()
        page = 0
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Premium.isActive(this)) {
            finish()
            return
        }
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdgeBar(b.root, b.topBar, b.pager)
        db = LogDb.get(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        b.searchInput.doAfterTextChanged {
            b.searchInput.removeCallbacks(searchRunnable)
            b.searchInput.postDelayed(searchRunnable, 300)
        }
        b.searchInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                b.searchInput.removeCallbacks(searchRunnable)
                searchRunnable.run()
                true
            } else false
        }
        b.statusGroup.setOnCheckedStateChangeListener { _, ids ->
            status = when (ids.firstOrNull()) {
                b.statusOk.id -> 1
                b.statusFail.id -> 2
                else -> 0
            }
            page = 0
            render()
        }
        b.timeGroup.setOnCheckedStateChangeListener { _, ids ->
            sinceKind = when (ids.firstOrNull()) {
                b.timeToday.id -> 1
                b.time7.id -> 2
                b.time30.id -> 3
                else -> 0
            }
            page = 0
            render()
        }
        b.btnSort.setOnClickListener {
            newestFirst = !newestFirst
            b.btnSort.setText(if (newestFirst) R.string.log_sort_newest else R.string.log_sort_oldest)
            page = 0
            render()
        }
        // «قبلی» = صفحه‌ی جدیدتر/قبلی در ترتیب نمایش
        b.btnPrev.setOnClickListener { if (page > 0) { page--; render() } }
        b.btnNext.setOnClickListener { if (page < pages - 1) { page++; render() } }
    }

    private val expiryTicker by lazy { ExpiryTicker(this, onExpired = { finish() }) }

    override fun onPause() {
        expiryTicker.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        expiryTicker.start()
        if (!Premium.isActive(this)) {
            finish()
            return
        }
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_clear) return super.onOptionsItemSelected(item)
        AppDialog.confirm(
            this, getString(R.string.log_clear), getString(R.string.log_clear_confirm),
            getString(R.string.log_clear), dangerous = true
        ) {
            db.clear()
            page = 0
            render()
        }
        return true
    }

    private fun since(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return when (sinceKind) {
            1 -> cal.timeInMillis
            2 -> cal.timeInMillis - 6 * Fa.DAY
            3 -> cal.timeInMillis - 29 * Fa.DAY
            else -> 0L
        }
    }

    private fun render() {
        val f = LogDb.Filter(query, status, since(), newestFirst)
        val total = db.count(f)
        pages = maxOf(1, (total + PAGE - 1) / PAGE)
        page = page.coerceIn(0, pages - 1)
        val rows = if (total == 0) emptyList() else db.page(f, page, PAGE)
        adapter.submit(rows)
        if (rows.isNotEmpty()) b.list.scrollToPosition(0)

        val (all, _, _) = db.totals()
        val filtered = query.isNotBlank() || status != 0 || sinceKind != 0
        b.emptyBox.isVisible = total == 0
        b.list.isVisible = total > 0
        b.emptyText.setText(
            when {
                all == 0 -> R.string.log_empty
                else -> R.string.log_empty_filtered
            }
        )
        b.summaryText.text = if (filtered) getString(R.string.log_summary_filtered, Fa.num(total), Fa.num(all))
        else getString(R.string.log_summary, Fa.num(all))
        b.pager.isVisible = total > 0
        b.pageText.text = getString(R.string.log_page, Fa.num(page + 1), Fa.num(pages))
        b.btnPrev.isEnabled = page > 0
        b.btnNext.isEnabled = page < pages - 1
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<LogDb.Entry> = emptyList()

        // lazy: Adapter در لحظه‌ی ساخت Activity (قبل از attachBaseContext) ساخته می‌شود و
        // خواندن رنگ در همان لحظه باعث کرش می‌شد؛ اینجا اولین بار هنگام نمایش ردیف‌ها خوانده می‌شود.
        private val okColor by lazy { ContextCompat.getColor(this@LogActivity, R.color.success) }
        private val errColor by lazy { ContextCompat.getColor(this@LogActivity, R.color.error) }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<LogDb.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemLogBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val v = h.v
            val tint = if (e.ok) okColor else errColor
            v.statusBox.backgroundTintList = ColorStateList.valueOf(tint).withAlpha(36)
            v.statusIcon.setImageResource(if (e.ok) R.drawable.ic_check else R.drawable.ic_close)
            v.statusIcon.imageTintList = ColorStateList.valueOf(tint)
            v.title.text = e.sender
            // شماره‌ها (مثل 0912***4567 یا +98…) همیشه چپ‌به‌راست
            val first = e.sender.firstOrNull()
            v.title.textDirection =
                if (first != null && (first.isDigit() || first == '+')) View.TEXT_DIRECTION_LTR
                else View.TEXT_DIRECTION_ANY_RTL
            v.note.text = e.note
            v.note.setTextColor(if (e.ok) v.note.currentTextColor else errColor)
            v.time.text = Template.dateTime(e.time)
        }
    }

    private companion object {
        const val PAGE = 20
    }
}
