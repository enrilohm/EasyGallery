package com.example.easygallery.gallery

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.easygallery.R

class TimelineFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private lateinit var adapter: GalleryAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GalleryAdapter(
            onFolderClick = {},
            onImageClick = { item, _ ->
                val paths = adapter.currentPaths()
                val index = paths.indexOf(item.path)
                if (index >= 0) ImageDetailActivity.open(requireContext(), paths, index)
                else ImageDetailActivity.open(requireContext(), listOf(item.path), 0)
            },
            onSelectionChanged = { _ ->
                requireActivity().invalidateOptionsMenu()
            }
        )

        val cols = savedColumns()
        val layoutManager = GridLayoutManager(requireContext(), cols)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = 1
        }

        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { viewModel.reload(requireContext().contentResolver) }

        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad, pad, pad, bars.bottom + pad)
            insets
        }

        viewModel.filteredEntries.observe(viewLifecycleOwner) { entries ->
            val items = entries.map { GalleryItem.Image(it.uri, it.path) }
            adapter.updateItems(items)
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = "Timeline"
            setDisplayHomeAsUpEnabled(false)
        }
    }

    fun isInSelectionMode() = ::adapter.isInitialized && adapter.inSelectionMode

    fun resolveSelectedEntries(): List<Pair<String, String>> {
        val keys = adapter.selectedKeys()
        return viewModel.filteredEntries.value
            ?.filter { keys.contains(it.path) }
            ?.map { it.path to java.io.File(it.path).name }
            ?: emptyList()
    }

    private fun savedColumns() =
        requireContext().getSharedPreferences("gallery_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("columns", 3)
}
