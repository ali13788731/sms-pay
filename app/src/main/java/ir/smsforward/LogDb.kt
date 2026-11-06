package ir.smsforward

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * گزارش ارسال‌ها در SQLite: سریع، قابل جستجو و صفحه‌بندی‌شده.
 * فقط فرستنده، زمان، وضعیت و یادداشت ذخیره می‌شود؛ متن پیامک‌ها هرگز.
 */
class LogDb private constructor(private val app: Context) : SQLiteOpenHelper(app, "log.db", null, 1) {

    class Entry(val id: Long, val time: Long, val sender: String, val ok: Boolean, val note: String)

    /** [status]: 0 همه، 1 موفق، 2 ناموفق. [since]: فقط ردیف‌های جدیدتر از این زمان (0 = همه). */
    class Filter(
        val query: String = "",
        val status: Int = 0,
        val since: Long = 0L,
        val newestFirst: Boolean = true
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, " +
                "sender TEXT NOT NULL, ok INTEGER NOT NULL, note TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_log_t ON log(t)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(sender: String, ok: Boolean, note: String) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("t", System.currentTimeMillis())
            put("sender", sender)
            put("ok", if (ok) 1 else 0)
            put("note", note)
        }
        db.insert("log", null, v)
        // فقط آخرین [MAX_ROWS] مورد نگه داشته می‌شود
        db.execSQL("DELETE FROM log WHERE id <= (SELECT MAX(id) FROM log) - $MAX_ROWS")
    }

    fun clear() {
        writableDatabase.delete("log", null, null)
    }

    private fun where(f: Filter): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        val q = Fa.latinDigits(f.query.trim())
        if (q.isNotEmpty()) {
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            parts.add("(sender LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\')")
            args.add(like)
            args.add(like)
        }
        if (f.status == 1) parts.add("ok = 1") else if (f.status == 2) parts.add("ok = 0")
        if (f.since > 0L) {
            parts.add("t >= ?")
            args.add(f.since.toString())
        }
        val sql = if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
        return sql to args.toTypedArray()
    }

    fun count(f: Filter): Int {
        val (w, args) = where(f)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM log$w", args).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** شماره‌ی صفحه از صفر شروع می‌شود. */
    fun page(f: Filter, page: Int, size: Int): List<Entry> {
        val (w, args) = where(f)
        val order = if (f.newestFirst) "DESC" else "ASC"
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT id, t, sender, ok, note FROM log$w ORDER BY t $order, id $order LIMIT $size OFFSET ${page * size}",
            args
        ).use {
            while (it.moveToNext()) {
                out.add(Entry(it.getLong(0), it.getLong(1), it.getString(2), it.getInt(3) == 1, it.getString(4)))
            }
        }
        return out
    }

    /** (کل، موفق، ناموفق) بدون در نظر گرفتن فیلتر */
    fun totals(): Triple<Int, Int, Int> {
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(ok),0) FROM log", null).use {
            if (!it.moveToFirst()) return Triple(0, 0, 0)
            val all = it.getInt(0)
            val ok = it.getInt(1)
            return Triple(all, ok, all - ok)
        }
    }

    /** گزارش‌های نسخه‌های قبل (SharedPreferences) یک بار به پایگاه‌داده منتقل می‌شود. */
    private fun migrateOld() {
        val sp = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val raw = sp.getString("log2", null) ?: return
        try {
            val arr = JSONArray(raw)
            val db = writableDatabase
            db.beginTransaction()
            try {
                // قدیمی‌ترین اول، تا ترتیب شناسه‌ها درست باشد
                for (i in arr.length() - 1 downTo 0) {
                    val o = arr.getJSONObject(i)
                    val v = ContentValues().apply {
                        put("t", o.getLong("t"))
                        put("sender", o.getString("s"))
                        put("ok", if (o.getBoolean("ok")) 1 else 0)
                        put("note", o.optString("n"))
                    }
                    db.insert("log", null, v)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            // گزارش قدیمی خراب بود؛ نادیده گرفته می‌شود
        }
        sp.edit().remove("log2").apply()
    }

    companion object {
        const val MAX_ROWS = 5000

        @Volatile private var inst: LogDb? = null

        fun get(c: Context): LogDb = inst ?: synchronized(this) {
            inst ?: LogDb(c.applicationContext).also {
                inst = it
                it.migrateOld()
            }
        }
    }
}
