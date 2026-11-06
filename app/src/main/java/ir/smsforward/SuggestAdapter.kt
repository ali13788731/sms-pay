package ir.smsforward

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import ir.smsforward.databinding.ItemSuggestBinding

/** پیشنهاد خودکار هنگام تایپ؛ با انتخاب هر مورد، [Sources.Entry.value] در فیلد نوشته می‌شود. */
class SuggestAdapter(
    private val ctx: Context,
    private var all: List<Sources.Entry> = emptyList(),
    private val maxItems: Int = 8
) : BaseAdapter(), Filterable {

    private var shown: List<Sources.Entry> = emptyList()

    fun setData(list: List<Sources.Entry>) {
        all = list
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int): Sources.Entry = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = if (convertView == null) ItemSuggestBinding.inflate(LayoutInflater.from(ctx), parent, false)
        else ItemSuggestBinding.bind(convertView)
        val e = shown[position]
        row.title.text = e.title
        row.subtitle.text = e.subtitle
        return row.root
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString().orEmpty().trim()
            val res = if (q.isEmpty()) emptyList()
            else all.filter { Sources.matches(it, q) }.distinctBy { it.title + "|" + Forwarder.key(it.value) }.take(maxItems)
            return FilterResults().also {
                it.values = res
                it.count = res.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            shown = (results.values as? List<Sources.Entry>) ?: emptyList()
            if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? Sources.Entry)?.value ?: ""
    }
}

/** جداکننده‌ی فرستنده‌ها در فیلد لیست: , ، ; و خط جدید */
class SenderTokenizer : android.widget.MultiAutoCompleteTextView.Tokenizer {
    private fun isSep(c: Char) = c == ',' || c == '،' || c == ';' || c == '\n'

    override fun findTokenStart(text: CharSequence, cursor: Int): Int {
        var i = cursor
        while (i > 0 && !isSep(text[i - 1])) i--
        while (i < cursor && text[i] == ' ') i++
        return i
    }

    override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
        var i = cursor
        while (i < text.length) {
            if (isSep(text[i])) return i
            i++
        }
        return text.length
    }

    override fun terminateToken(text: CharSequence): CharSequence {
        var i = text.length
        while (i > 0 && text[i - 1] == ' ') i--
        if (i > 0 && isSep(text[i - 1])) return text
        return "$text, "
    }
}
