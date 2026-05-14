package com.example.easygallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load

class GalleryAdapter(
    private val onFolderClick: (GalleryItem.Folder) -> Unit,
    private val onImageClick: (GalleryItem.Image, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<GalleryItem> = emptyList()

    fun updateItems(newItems: List<GalleryItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o] == newItems[n]
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is GalleryItem.Folder -> VIEW_TYPE_FOLDER
        is GalleryItem.Image -> VIEW_TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_FOLDER -> FolderViewHolder(inflater.inflate(R.layout.item_folder, parent, false))
            else -> ImageViewHolder(inflater.inflate(R.layout.item_gallery_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GalleryItem.Folder -> {
                holder as FolderViewHolder
                holder.thumbnail.load(item.coverUri)
                holder.name.text = item.name
                holder.count.text = item.count.toString()
                holder.itemView.setOnClickListener { onFolderClick(item) }
            }
            is GalleryItem.Image -> {
                (holder as ImageViewHolder).imageView.load(item.uri)
                val imageIndex = items.take(position + 1).count { it is GalleryItem.Image } - 1
                holder.itemView.setOnClickListener { onImageClick(item, imageIndex) }
            }
        }
    }

    fun currentPaths(): List<String> = items.filterIsInstance<GalleryItem.Image>().map { it.path }

    override fun getItemCount() = items.size

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.folderThumbnail)
        val name: TextView = view.findViewById(R.id.folderName)
        val count: TextView = view.findViewById(R.id.folderCount)
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
    }

    companion object {
        const val VIEW_TYPE_FOLDER = 0
        const val VIEW_TYPE_IMAGE = 1
    }
}
