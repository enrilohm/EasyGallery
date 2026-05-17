package com.example.easygallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

class GalleryAdapter(
    private val onFolderClick: (GalleryItem.Folder) -> Unit,
    private val onImageClick: (GalleryItem.Image, Int) -> Unit = { _, _ -> }
) : ListAdapter<GalleryItem, RecyclerView.ViewHolder>(DIFF) {

    fun updateItems(newItems: List<GalleryItem>) = submitList(newItems)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
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
        when (val item = getItem(position)) {
            is GalleryItem.Folder -> {
                holder as FolderViewHolder
                holder.thumbnail.load(item.coverUri)
                holder.name.text = item.name
                holder.count.text = item.count.toString()
                holder.itemView.setOnClickListener { onFolderClick(item) }
            }
            is GalleryItem.Image -> {
                (holder as ImageViewHolder).imageView.load(item.uri)
                val imageIndex = currentList.take(position + 1).count { it is GalleryItem.Image } - 1
                holder.itemView.setOnClickListener { onImageClick(item, imageIndex) }
            }
        }
    }

    fun currentPaths(): List<String> = currentList.filterIsInstance<GalleryItem.Image>().map { it.path }

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

        private val DIFF = object : DiffUtil.ItemCallback<GalleryItem>() {
            override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem) = oldItem == newItem
            override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem) = oldItem == newItem
        }
    }
}
