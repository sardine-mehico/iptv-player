package io.github.sardinemehico.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.repo.CategoryRow
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Categories are few (tens to hundreds), so they are held in memory. */
class CategoryAdapter(
    private val onFocused: (Int) -> Unit,
    private val onClicked: (Int) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<CategoryRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selected = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_category, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position].name
        holder.text.isActivated = position == selected
    }

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
        init {
            text.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) onFocused(bindingAdapterPosition)
            }
            text.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClicked(bindingAdapterPosition)
            }
        }
    }
}

/**
 * Channel list (or, with [layout] = row_poster, a movie/series poster grid) backed by SQLite pages of [PAGE] rows. Only a few pages stay in memory, so a
 * 50,000-channel "All" list costs the same as a 50-channel one. Rows not loaded yet show a
 * placeholder and fill in when their page arrives; the UI thread never waits.
 */
class PagedEntryAdapter(
    private val scope: CoroutineScope,
    private val onClicked: (Int, EntryRow) -> Unit,
    private val layout: Int = R.layout.row_channel,
) : RecyclerView.Adapter<PagedEntryAdapter.VH>() {

    private var loader: (suspend (offset: Int, limit: Int) -> List<EntryRow>)? = null
    private var total = 0
    private var generation = 0
    private val pages = HashMap<Int, List<EntryRow>>()
    private val loading = HashSet<Int>()

    /** Item id of the stream now playing, highlighted in the list. */
    var playingItemId: String? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, total, PAYLOAD_STATE)
        }

    init {
        setHasStableIds(true)
    }

    fun reset(count: Int, load: suspend (offset: Int, limit: Int) -> List<EntryRow>) {
        generation++
        total = count
        loader = load
        pages.clear()
        loading.clear()
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): EntryRow? = pages[position / PAGE]?.getOrNull(position % PAGE)

    fun setFavourite(position: Int, favourite: Boolean) {
        val page = position / PAGE
        val rows = pages[page] ?: return
        val i = position % PAGE
        if (i !in rows.indices) return
        pages[page] = rows.toMutableList().also { it[i] = it[i].copy(favourite = favourite) }
        notifyItemChanged(position)
    }

    override fun getItemCount() = total
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
            holder.itemView.isActivated = rowAt(position)?.itemId == playingItemId && playingItemId != null
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.num?.text = (position + 1).toString()
        val row = rowAt(position)
        if (row == null) {
            holder.name.text = ""
            holder.fav?.visibility = View.GONE
            holder.logo.load(null)
            holder.itemView.isActivated = false
            request(position / PAGE)
            return
        }
        holder.name.text = row.name
        holder.fav?.visibility = if (row.favourite) View.VISIBLE else View.GONE
        holder.logo.load(row.logo)
        holder.itemView.isActivated = row.itemId == playingItemId
    }

    private fun request(page: Int) {
        if (page in pages || page in loading) return
        val load = loader ?: return
        val gen = generation
        loading += page
        scope.launch {
            val rows = try {
                load(page * PAGE, PAGE)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) loading -= page
                return@launch
            }
            if (gen != generation) return@launch
            loading -= page
            pages[page] = rows
            evictAround(page)
            notifyItemRangeChanged(page * PAGE, rows.size)
        }
    }

    private fun evictAround(center: Int) {
        if (pages.size <= MAX_PAGES) return
        val far = pages.keys.sortedByDescending { kotlin.math.abs(it - center) }
        for (k in far.take(pages.size - MAX_PAGES)) pages.remove(k)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView? = view.findViewById(R.id.num)
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val fav: TextView? = view.findViewById(R.id.fav)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                rowAt(pos)?.let { onClicked(pos, it) }
            }
        }
    }

    companion object {
        const val PAGE = 100
        private const val MAX_PAGES = 8
        private const val PAYLOAD_STATE = "state"
    }
}
