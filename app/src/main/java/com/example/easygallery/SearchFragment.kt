package com.example.easygallery

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
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SearchFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private lateinit var adapter: GalleryAdapter
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
            onImageLongClick = { image ->
                ImageInfoSheet.show(parentFragmentManager, image.uri, image.path)
            }
        )

        val columns = requireContext()
            .getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
            .getInt("columns", 3)

        val recyclerView = view.findViewById<RecyclerView>(R.id.searchResults)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), columns)
        recyclerView.adapter = adapter

        view.findViewById<TextInputEditText>(R.id.searchInput).addTextChangedListener { text ->
            searchJob?.cancel()
            if (text.isNullOrBlank()) {
                adapter.updateItems(emptyList())
                return@addTextChangedListener
            }

            val clipEnabled = requireContext()
                .getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
                .getBoolean("clip_search_enabled", false)
            if (!clipEnabled) {
                adapter.updateItems(emptyList())
                return@addTextChangedListener
            }

            val query = text.toString()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                val embedding = withContext(Dispatchers.IO) {
                    VectorStore.init(requireContext())
                    ClipTextEncoder.load(requireContext())
                    ClipTextEncoder.encode(query)
                } ?: return@launch

                val paths = withContext(Dispatchers.IO) {
                    VectorStore.findSimilar(embedding, topK = 5)
                }

                val pathToUri = viewModel.entries.associate { it.path to it.uri }
                val results = paths.map { path ->
                    val uri = pathToUri[path] ?: Uri.fromFile(File(path))
                    GalleryItem.Image(uri, path)
                }
                adapter.updateItems(results)
            }
        }
    }
}
