package com.example.easygallery.gallery

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.easygallery.R

class GalleryFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private lateinit var adapter: GalleryAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var currentPath = ""
    private val pathStack = ArrayDeque<String>()
    private var updateJob: Job? = null
    private var dirMap: Map<String, List<GalleryViewModel.ImageEntry>> = emptyMap()
    private val scrollState = mutableMapOf<String, android.os.Parcelable>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            currentPath = pathStack.removeLast()
            if (pathStack.isEmpty()) isEnabled = false
            updateView(restoreScroll = true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        adapter = GalleryAdapter(
            onFolderClick = { folder ->
                layoutManager.onSaveInstanceState()?.let { scrollState[currentPath] = it }
                pathStack.addLast(currentPath)
                currentPath = folder.path
                backCallback.isEnabled = true
                updateView(restoreScroll = false)
            },
            onImageClick = { item, _ ->
                // Resolve the tapped item's position in the current snapshot so the list
                // and index can't diverge if the backing list changed since bind time.
                val paths = adapter.currentPaths()
                val index = paths.indexOf(item.path)
                if (index >= 0) ImageDetailActivity.open(requireContext(), paths, index)
                else ImageDetailActivity.open(requireContext(), listOf(item.path), 0)
            }
        )

        layoutManager = GridLayoutManager(requireContext(), savedColumns())
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.getItemViewType(position) == GalleryAdapter.VIEW_TYPE_FOLDER)
                    layoutManager.spanCount else 1
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
            if (currentPath.isEmpty()) currentPath = viewModel.rootPath
            updateJob?.cancel()
            updateJob = viewLifecycleOwner.lifecycleScope.launch {
                dirMap = withContext(Dispatchers.Default) { entries.groupBy { it.dir } }
                updateView(restoreScroll = true)
                swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val cols = savedColumns()
        if (layoutManager.spanCount != cols) {
            layoutManager.spanCount = cols
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
        updateToolbarTitle()
    }

    private fun updateView(restoreScroll: Boolean = false) {
        updateToolbarTitle()
        val path = currentPath
        val map = dirMap
        val items = childFolders(path, map) +
            (map[path]?.map { GalleryItem.Image(it.uri, it.path) } ?: emptyList())
        val saved = if (restoreScroll) scrollState[path] else null
        adapter.updateItems(items) {
            if (saved != null) layoutManager.onRestoreInstanceState(saved)
            else recyclerView.scrollToPosition(0)
        }
    }

    fun updateToolbarTitle() {
        val label = if (currentPath == viewModel.rootPath || currentPath.isEmpty())
            "Easy Gallery" else currentPath.substringAfterLast("/")
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = label
            setDisplayHomeAsUpEnabled(pathStack.isNotEmpty())
        }
    }

    private fun childFolders(
        parent: String,
        map: Map<String, List<GalleryViewModel.ImageEntry>>
    ): List<GalleryItem.Folder> {
        val prefix = "$parent/"
        // keyed by immediate child folder path → total image count + first uri
        val folderMap = LinkedHashMap<String, Pair<Int, android.net.Uri>>()
        for ((dir, dirEntries) in map) {
            if (!dir.startsWith(prefix)) continue
            val nextSegment = dir.removePrefix(prefix).substringBefore("/")
            val childPath = "$prefix$nextSegment"
            val existing = folderMap[childPath]
            folderMap[childPath] = if (existing == null)
                dirEntries.size to dirEntries.first().uri
            else
                (existing.first + dirEntries.size) to existing.second
        }
        return folderMap.entries.map { (path, pair) ->
            GalleryItem.Folder(path, path.substringAfterLast("/"), pair.first, pair.second)
        }
    }

    private fun savedColumns() =
        requireContext().getSharedPreferences("gallery_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("columns", 3)
}
