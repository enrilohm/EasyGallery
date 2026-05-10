package com.example.easygallery

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GalleryFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private lateinit var adapter: GalleryAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var recyclerView: RecyclerView

    private var currentPath = ""
    private val pathStack = ArrayDeque<String>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            currentPath = pathStack.removeLast()
            if (pathStack.isEmpty()) isEnabled = false
            updateView()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        adapter = GalleryAdapter(
            onFolderClick = { folder ->
                pathStack.addLast(currentPath)
                currentPath = folder.path
                backCallback.isEnabled = true
                updateView()
            },
            onImageClick = { _, index ->
                ImageDetailActivity.open(requireContext(), adapter.currentPaths(), index)
            },
            onImageLongClick = { image ->
                ImageInfoSheet.show(parentFragmentManager, image.uri, image.path)
            }
        )

        layoutManager = GridLayoutManager(requireContext(), savedColumns())
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.getItemViewType(position) == GalleryAdapter.VIEW_TYPE_FOLDER)
                    layoutManager.spanCount else 1
        }

        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad, pad, pad, bars.bottom + pad)
            insets
        }

        viewModel.loaded.observe(viewLifecycleOwner) { loaded ->
            if (loaded) {
                if (currentPath.isEmpty()) currentPath = viewModel.rootPath
                updateView()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        layoutManager.spanCount = savedColumns()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateToolbarTitle()
    }

    private fun updateView() {
        updateToolbarTitle()
        val subfolders = childFolders(currentPath)
        val images = viewModel.entries
            .filter { it.dir == currentPath }
            .map { GalleryItem.Image(it.uri, it.path) }
        adapter.updateItems(subfolders + images)
        recyclerView.scrollToPosition(0)
    }

    private fun updateToolbarTitle() {
        val label = if (currentPath == viewModel.rootPath || currentPath.isEmpty())
            "Easy Gallery" else currentPath.substringAfterLast("/")
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = label
            setDisplayHomeAsUpEnabled(pathStack.isNotEmpty())
        }
    }

    private fun childFolders(parent: String): List<GalleryItem.Folder> {
        val prefix = "$parent/"
        val folderMap = LinkedHashMap<String, MutableList<GalleryViewModel.ImageEntry>>()
        for (entry in viewModel.entries) {
            if (!entry.dir.startsWith(prefix)) continue
            val nextSegment = entry.dir.removePrefix(prefix).substringBefore("/")
            folderMap.getOrPut("$prefix$nextSegment") { mutableListOf() }.add(entry)
        }
        return folderMap.entries.map { (path, entries) ->
            GalleryItem.Folder(path, path.substringAfterLast("/"), entries.size, entries.first().uri)
        }
    }

    private fun savedColumns() =
        requireContext().getSharedPreferences("gallery_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("columns", 3)
}
