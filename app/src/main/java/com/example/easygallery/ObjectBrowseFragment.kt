package com.example.easygallery

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ObjectBrowseFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: View
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var objectItems: List<ObjectItem> = emptyList()
    private var selectedLabel: String? = null

    private data class ObjectItem(val label: String, val examplePath: String?, val count: Int)

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { exitLabelView() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_object_browse, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener {
            val label = selectedLabel
            if (label != null) showImagesForLabel(label) else loadObjectGrid()
        }

        recyclerView = view.findViewById(R.id.objectRecyclerView)
        emptyText = view.findViewById(R.id.emptyText)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewModel.filterState.observe(viewLifecycleOwner) {
            val label = selectedLabel
            if (label != null) showImagesForLabel(label) else loadObjectGrid()
        }

        val pad = (4 * resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad, pad, pad, bars.bottom + pad)
            insets
        }

        loadObjectGrid()
    }

    private fun loadObjectGrid() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            objectItems = withContext(Dispatchers.IO) {
                VectorStore.init(ctx)
                VectorStore.getDistinctObjectLabels().mapNotNull { (label, _) ->
                    val paths = viewModel.applyFilters(VectorStore.getImagePathsByLabel(label))
                    if (paths.isEmpty()) null
                    else ObjectItem(label, paths.first(), paths.size)
                }
            }
            showObjectGrid()
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onResume() {
        super.onResume()
        updateToolbar()
    }

    private fun showObjectGrid() {
        selectedLabel = null
        backCallback.isEnabled = false
        if (objectItems.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
            recyclerView.adapter = ObjectAdapter()
        }
        updateToolbar()
    }

    private fun showImagesForLabel(label: String) {
        selectedLabel = label
        backCallback.isEnabled = true
        updateToolbar()

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val pathToUri = viewModel.entries.associate { it.path to it.uri }
            val rawPaths = withContext(Dispatchers.IO) { VectorStore.getImagePathsByLabel(label) }
            val items = viewModel.applyFilters(rawPaths).map { path ->
                GalleryItem.Image(pathToUri[path] ?: Uri.fromFile(File(path)), path) as GalleryItem
            }
            val columns = ctx.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
                .getInt("columns", 3)
            recyclerView.layoutManager = GridLayoutManager(ctx, columns)
            val imagePaths = items.filterIsInstance<GalleryItem.Image>().map { it.path }
            val adapter = GalleryAdapter(
                onFolderClick = {},
                onImageClick = { _, index ->
                    ImageDetailActivity.open(requireContext(), imagePaths, index)
                },
                onImageLongClick = { image ->
                    ImageInfoSheet.show(parentFragmentManager, image.uri, image.path)
                }
            )
            adapter.updateItems(items)
            recyclerView.adapter = adapter
            swipeRefresh.isRefreshing = false
        }
    }

    private fun exitLabelView() {
        showObjectGrid()
    }

    private fun updateToolbar() {
        if (!isAdded) return
        val label = selectedLabel
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = label?.replaceFirstChar { it.uppercase() } ?: "Objects"
            setDisplayHomeAsUpEnabled(label != null)
        }
    }

    private inner class ObjectAdapter : RecyclerView.Adapter<ObjectAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.exampleImage)
            val label: TextView = view.findViewById(R.id.objectLabel)
            val count: TextView = view.findViewById(R.id.objectCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_object_tile, parent, false))

        override fun getItemCount() = objectItems.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = objectItems[position]
            holder.label.text = item.label.replaceFirstChar { it.uppercase() }
            holder.count.text = item.count.toString()
            item.examplePath?.let { holder.image.load(File(it)) { crossfade(true) } }
            holder.itemView.setOnClickListener { showImagesForLabel(item.label) }
        }
    }
}
