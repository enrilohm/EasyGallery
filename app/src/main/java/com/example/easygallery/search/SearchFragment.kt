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
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.easygallery.db.AppDatabase
import com.example.easygallery.ocr.OcrStore
import com.example.easygallery.objects.ObjectsStore
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
    private var searchJob: Job? = null

    override fun onResume() {
        super.onResume()
        (requireActivity() as androidx.appcompat.app.AppCompatActivity)
            .supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GalleryAdapter(
            onFolderClick = {},
            onImageClick = { _, position ->
                val paths = adapter.currentPaths()
                ImageDetailActivity.open(requireContext(), paths, position)
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

        searchInput = view.findViewById(R.id.searchInput)
        searchInput.addTextChangedListener { text -> runSearch(text?.toString()) }

        viewModel.filterState.observe(viewLifecycleOwner) {
            runSearch(searchInput.text?.toString())
        }
    }

    private fun runSearch(query: String?) {
        searchJob?.cancel()
        if (query.isNullOrBlank()) {
            adapter.updateItems(emptyList())
            swipeRefresh.isRefreshing = false
            return
        }

        val clipEnabled = requireContext()
            .getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
            .getBoolean("clip_search_enabled", false)
        if (!clipEnabled) {
            adapter.updateItems(emptyList())
            swipeRefresh.isRefreshing = false
            return
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val pathToUri = viewModel.entries.associate { it.path to it.uri }
            val allowed = viewModel.allowedPaths()

            val paths = withContext(Dispatchers.IO) {
                AppDatabase.init(ctx)

                // 1. OCR text hits
                val textHits = OcrStore.findByText(query, limit = 50, allowedPaths = allowed)

                // 2. Object label hits
                val labelHits = ObjectsStore.findByLabel(query, limit = 50, allowedPaths = allowed)
                    .filter { it !in textHits }

                // 3. CLIP embedding similarity
                val soFar = textHits + labelHits
                ClipTextEncoder.load(ctx)
                val embedding = ClipTextEncoder.encode(query)
                val embeddingHits = if (embedding != null)
                    ClipStore.findSimilar(embedding, topK = 200, allowedPaths = allowed)
                        .filter { it !in textHits && it !in labelHits }
                else emptyList()

                soFar + embeddingHits
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
