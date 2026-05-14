package com.example.easygallery

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ClusterImagesSheet : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            it.layoutParams.height = maxHeight
            BottomSheetBehavior.from(it).peekHeight = maxHeight
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_cluster_images, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val paths = arguments?.getStringArrayList(ARG_PATHS) ?: return
        val uris  = arguments?.getStringArrayList(ARG_URIS)  ?: return
        val title = arguments?.getString(ARG_TITLE)
        val clusterId = arguments?.getLong(ARG_CLUSTER_ID, -1L) ?: -1L

        view.findViewById<TextView>(R.id.clusterTitle).text = title ?: "${paths.size} photos"

        val items = paths.zip(uris).map { (path, uri) ->
            GalleryItem.Image(Uri.parse(uri), path)
        }

        val adapter = GalleryAdapter(
            onFolderClick = {},
            onImageClick = { _, index ->
                ImageDetailActivity.open(requireContext(), paths, index, clusterId)
            }
        )
        adapter.updateItems(items)

        view.findViewById<RecyclerView>(R.id.clusterGrid).apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            this.adapter = adapter
        }
    }

    companion object {
        private const val ARG_PATHS = "paths"
        private const val ARG_URIS  = "uris"
        private const val ARG_TITLE = "title"
        private const val ARG_CLUSTER_ID = "cluster_id"

        fun show(fm: FragmentManager, entries: List<GalleryViewModel.ImageEntry>) {
            ClusterImagesSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_PATHS, ArrayList(entries.map { it.path }))
                    putStringArrayList(ARG_URIS,  ArrayList(entries.map { it.uri.toString() }))
                }
            }.show(fm, null)
        }

        fun showPaths(fm: FragmentManager, paths: List<String>, title: String? = null, clusterId: Long = -1L) {
            ClusterImagesSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_PATHS, ArrayList(paths))
                    putStringArrayList(ARG_URIS, ArrayList(paths.map {
                        android.net.Uri.fromFile(java.io.File(it)).toString()
                    }))
                    title?.let { putString(ARG_TITLE, it) }
                    putLong(ARG_CLUSTER_ID, clusterId)
                }
            }.show(fm, null)
        }
    }
}
