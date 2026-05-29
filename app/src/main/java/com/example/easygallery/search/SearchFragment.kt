package com.example.easygallery.search

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.easygallery.db.AppDatabase
import com.example.easygallery.ocr.OcrStore
import com.example.easygallery.gallery.GalleryViewModel
import com.example.easygallery.gallery.GalleryAdapter
import com.example.easygallery.gallery.GalleryItem
import com.example.easygallery.R
import com.example.easygallery.gallery.ImageDetailActivity

class SearchFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private lateinit var adapter: GalleryAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: TextInputEditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var searchModeToggle: MaterialButtonToggleGroup
    private var searchJob: Job? = null
    private var similarPath: String? = null

    override fun onResume() {
        super.onResume()
        (requireActivity() as androidx.appcompat.app.AppCompatActivity)
            .supportActionBar?.setDisplayHomeAsUpEnabled(false)
        if (::searchModeToggle.isInitialized) updateToggleVisibility()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GalleryAdapter(
            onFolderClick = {},
            onImageClick = { item, _ ->
                // Resolve the tapped item's position in the current snapshot so the list
                // and index can't diverge if results were re-ranked since bind time
                // (e.g. background indexing adding embeddings during a similar search).
                val paths = adapter.currentPaths()
                val index = paths.indexOf(item.path)
                if (index >= 0) ImageDetailActivity.open(requireContext(), paths, index)
                else ImageDetailActivity.open(requireContext(), listOf(item.path), 0)
            }
        )

        val columns = requireContext()
            .getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
            .getInt("columns", 3)

        recyclerView = view.findViewById(R.id.searchResults)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), columns)
        recyclerView.adapter = adapter

        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { runSearch(searchInput.text?.toString()) }

        searchModeToggle = view.findViewById(R.id.searchModeToggle)
        searchModeToggle.check(R.id.btnModeSemantic)
        searchModeToggle.addOnButtonCheckedListener { _, _, _ ->
            runSearch(searchInput.text?.toString())
        }

        searchInput = view.findViewById(R.id.searchInput)
        searchInput.addTextChangedListener { text -> runSearch(text?.toString()) }

        viewModel.filterState.observe(viewLifecycleOwner) {
            updateToggleVisibility()
            runSearch(searchInput.text?.toString())
        }
        viewModel.similarRequest.observe(viewLifecycleOwner) { path ->
            if (path != null) {
                similarPath = path
                searchInput.setText("")
                runSearch(null)
                viewModel.consumeSimilar()
            }
        }
        updateToggleVisibility()
    }

    private fun updateToggleVisibility() {
        val prefs = requireContext().getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        val bothEnabled = prefs.getBoolean("clip_search_enabled", false) &&
                prefs.getBoolean("ocr_enabled", false)
        searchModeToggle.visibility = if (bothEnabled) View.VISIBLE else View.GONE
    }

    private fun runSearch(query: String?) {
        searchJob?.cancel()
        // Typing a text query cancels any active similar-image search.
        if (!query.isNullOrBlank()) similarPath = null
        val similar = similarPath

        if (query.isNullOrBlank() && similar == null) {
            adapter.updateItems(emptyList())
            swipeRefresh.isRefreshing = false
            return
        }

        val prefs = requireContext().getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        val clipEnabled = prefs.getBoolean("clip_search_enabled", false)
        val ocrEnabled = prefs.getBoolean("ocr_enabled", false)

        if (similar != null && !clipEnabled) {
            similarPath = null
            adapter.updateItems(emptyList())
            swipeRefresh.isRefreshing = false
            return
        }

        if (similar == null && !clipEnabled && !ocrEnabled) {
            adapter.updateItems(emptyList())
            swipeRefresh.isRefreshing = false
            return
        }

        val useOcr = similar == null && ocrEnabled &&
                (!clipEnabled || searchModeToggle.checkedButtonId == R.id.btnModeOcr)

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val pathToUri = viewModel.entries.associate { it.path to it.uri }
            val allowed = viewModel.allowedPaths()

            val paths = withContext(Dispatchers.IO) {
                AppDatabase.init(ctx)
                when {
                    similar != null -> {
                        val emb = ClipStore.embeddingForPath(similar) ?: run {
                            ClipEncoder.load(ctx)
                            ClipEncoder.decodeBitmap(similar)?.let {
                                ClipEncoder.encodeBatch(listOf(it)).firstOrNull()
                            }
                        }
                        if (emb != null)
                            ClipStore.findSimilar(emb, topK = 200, allowedPaths = allowed)
                                .filter { it != similar }
                        else emptyList()
                    }
                    useOcr -> OcrStore.findByText(query!!, limit = 200, allowedPaths = allowed)
                    else -> {
                        ClipTextEncoder.load(ctx)
                        val embedding = ClipTextEncoder.encode(query!!)
                        if (embedding != null)
                            ClipStore.findSimilar(embedding, topK = 200, allowedPaths = allowed)
                        else emptyList()
                    }
                }
            }

            val results = paths.map { path ->
                val uri = pathToUri[path] ?: Uri.fromFile(File(path))
                GalleryItem.Image(uri, path)
            }
            adapter.updateItems(results) { recyclerView.scrollToPosition(0) }
            swipeRefresh.isRefreshing = false
        }
    }
}
