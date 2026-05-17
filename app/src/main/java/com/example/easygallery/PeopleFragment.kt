package com.example.easygallery

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PeopleFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var rawClusters: List<VectorStore.StoredCluster> = emptyList()
    private var faceCount = 0
    private var memberBBoxes: Map<Pair<String, Long>, android.graphics.RectF?> = emptyMap()
    private var renderJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_people, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { loadClusters(recluster = true) }

        recyclerView = view.findViewById(R.id.peopleRecyclerView)
        emptyText = view.findViewById(R.id.emptyText)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)

        val pad = (4 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad, pad, pad, bars.bottom + pad)
            insets
        }

        viewModel.filteredEntries.observe(viewLifecycleOwner) { entries ->
            if (rawClusters.isNotEmpty()) renderClusters(entries)
        }

        loadClusters(recluster = false)
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = "People"
            setDisplayHomeAsUpEnabled(false)
        }
    }

    private fun loadClusters(recluster: Boolean) {
        swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val (clusters, count, bboxes) = withContext(Dispatchers.IO) {
                VectorStore.init(requireContext().applicationContext)
                if (recluster || !VectorStore.hasClusters()) {
                    val prefs = requireContext()
                        .getSharedPreferences("gallery_prefs", android.content.Context.MODE_PRIVATE)
                    val threshold = prefs.getFloat("face_cluster_threshold", 0.45f)
                    val minDetectionScore = prefs.getFloat("face_detection_score_threshold", 0.70f)
                    val faces = VectorStore.getAllGoodFaceEmbeddings(minDetectionScore)
                    val result = FaceClusterer.cluster(faces, threshold)
                    VectorStore.storeClusters(result)
                }
                Triple(VectorStore.getStoredClusters(), VectorStore.countFaceEntries(), VectorStore.getClusterMemberBBoxes())
            }
            rawClusters = clusters
            faceCount = count
            memberBBoxes = bboxes
            renderClusters(viewModel.filteredEntries.value ?: emptyList())
            swipeRefresh.isRefreshing = false
        }
    }

    private fun renderClusters(entries: List<GalleryViewModel.ImageEntry>) {
        val snapshot = rawClusters
        val bboxSnapshot = memberBBoxes
        val countSnapshot = faceCount
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val allowedPaths = entries.map { it.path }.toSet()
                snapshot.mapNotNull { c ->
                    val filteredPaths = c.paths.filter { it in allowedPaths }
                    if (filteredPaths.size < 2) return@mapNotNull null
                    if (c.representativePath in allowedPaths) {
                        c.copy(paths = filteredPaths)
                    } else {
                        val newRep = filteredPaths.first()
                        c.copy(paths = filteredPaths, representativePath = newRep, representativeBBox = bboxSnapshot[newRep to c.clusterId])
                    }
                }
            }
            if (filtered.isEmpty()) {
                emptyText.text = when {
                    countSnapshot == 0 -> "No faces indexed yet.\nEnable face detection in Settings."
                    else -> "No recurring faces found."
                }
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = ClustersAdapter(filtered)
            }
        }
    }

    private inner class ClustersAdapter(private val items: List<VectorStore.StoredCluster>) :
        RecyclerView.Adapter<ClustersAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById(R.id.contactPhoto)
            val name: TextView = view.findViewById(R.id.contactName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_person_tile, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cluster = items[position]
            holder.name.text = cluster.name ?: "${cluster.paths.size} photos"
            val bbox = cluster.representativeBBox
            if (bbox != null) {
                holder.photo.load(cluster.representativePath) {
                    crossfade(true)
                    transformations(FaceCropTransformation(bbox))
                }
            } else {
                holder.photo.load(cluster.representativePath) { crossfade(true) }
            }
            holder.itemView.setOnClickListener {
                ClusterImagesSheet.showPaths(
                    parentFragmentManager,
                    cluster.paths,
                    cluster.name ?: "Person ${position + 1}",
                    cluster.clusterId
                )
            }
            holder.itemView.setOnLongClickListener {
                val state = viewModel.filterState.value ?: GalleryViewModel.FilterState()
                val isIncluded = cluster.clusterId in state.personIncludeIds
                val isExcluded = cluster.clusterId in state.personExcludeIds
                val popup = PopupMenu(holder.itemView.context, holder.itemView)
                val label = cluster.name ?: "${cluster.paths.size} photos"
                if (isIncluded) {
                    popup.menu.add("Remove include filter").setOnMenuItemClickListener {
                        viewModel.removePersonFilter(cluster.clusterId, requireContext())
                        true
                    }
                } else {
                    popup.menu.add("Add include filter").setOnMenuItemClickListener {
                        viewModel.addPersonInclude(cluster.clusterId, label, requireContext())
                        true
                    }
                }
                if (isExcluded) {
                    popup.menu.add("Remove exclude filter").setOnMenuItemClickListener {
                        viewModel.removePersonFilter(cluster.clusterId, requireContext())
                        true
                    }
                } else {
                    popup.menu.add("Add exclude filter").setOnMenuItemClickListener {
                        viewModel.addPersonExclude(cluster.clusterId, label, requireContext())
                        true
                    }
                }
                popup.show()
                true
            }
        }
    }

    private class FaceCropTransformation(private val bounds: RectF) : Transformation {
        override val cacheKey = "face_crop_v2|${bounds.left}|${bounds.top}|${bounds.right}|${bounds.bottom}"

        override suspend fun transform(input: Bitmap, size: Size): Bitmap {
            val extra  = maxOf(bounds.right - bounds.left, bounds.bottom - bounds.top) * 0.6f
            val left   = ((bounds.left   - extra) * input.width ).toInt().coerceIn(0, input.width  - 1)
            val top    = ((bounds.top    - extra) * input.height).toInt().coerceIn(0, input.height - 1)
            val right  = ((bounds.right  + extra) * input.width ).toInt().coerceIn(left + 1, input.width)
            val bottom = ((bounds.bottom + extra) * input.height).toInt().coerceIn(top  + 1, input.height)
            return Bitmap.createBitmap(input, left, top, right - left, bottom - top)
        }
    }
}
