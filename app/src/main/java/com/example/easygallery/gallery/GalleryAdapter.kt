package com.example.easygallery.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.easygallery.R

class GalleryAdapter(
    private val onFolderClick: (GalleryItem.Folder) -> Unit,
    private val onImageClick: (GalleryItem.Image, Int) -> Unit = { _, _ -> },
    val onSelectionChanged: (Set<String>) -> Unit = {}
) : ListAdapter<GalleryItem, RecyclerView.ViewHolder>(DIFF) {

    private val selectedKeys = mutableSetOf<String>()
    var inSelectionMode = false
        private set

    fun clearSelection() {
        selectedKeys.clear()
        inSelectionMode = false
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(emptySet())
    }

    fun selectedKeys(): Set<String> = selectedKeys

    private fun key(item: GalleryItem) = when (item) {
        is GalleryItem.Image -> item.path
        is GalleryItem.Folder -> item.path
    }

    private fun toggle(item: GalleryItem, position: Int) {
        val k = key(item)
        if (selectedKeys.contains(k)) selectedKeys.remove(k) else selectedKeys.add(k)
        if (selectedKeys.isEmpty()) {
            inSelectionMode = false
            notifyItemRangeChanged(0, itemCount)
        } else {
            notifyItemChanged(position)
        }
        onSelectionChanged(selectedKeys.toSet())
    }

    private fun enterSelection(item: GalleryItem, position: Int) {
        inSelectionMode = true
        selectedKeys.add(key(item))
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedKeys.toSet())
    }

    fun updateItems(newItems: List<GalleryItem>, onCommitted: (() -> Unit)? = null) {
        // Drop selected keys that are no longer in the new list
        val newKeys = newItems.map { key(it) }.toSet()
        selectedKeys.retainAll(newKeys)
        if (selectedKeys.isEmpty()) inSelectionMode = false
        submitList(newItems, onCommitted)
    }

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
        val item = getItem(position)
        val selected = selectedKeys.contains(key(item))
        when (item) {
            is GalleryItem.Folder -> {
                holder as FolderViewHolder
                holder.thumbnail.load(item.coverUri)
                holder.name.text = item.name
                holder.count.text = item.count.toString()
                holder.checkIcon.visibility = if (selected) View.VISIBLE else View.GONE
                holder.chevronIcon.visibility = if (selected) View.GONE else View.VISIBLE
                holder.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener {
                    if (inSelectionMode) toggle(item, holder.bindingAdapterPosition)
                    else onFolderClick(item)
                }
                holder.itemView.setOnLongClickListener {
                    if (!inSelectionMode) enterSelection(item, holder.bindingAdapterPosition)
                    else toggle(item, holder.bindingAdapterPosition)
                    true
                }
            }
            is GalleryItem.Image -> {
                holder as ImageViewHolder
                holder.imageView.load(item.uri)
                holder.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
                holder.checkIcon.visibility = if (selected) View.VISIBLE else View.GONE
                val imageIndex = currentList.take(position + 1).count { it is GalleryItem.Image } - 1
                holder.itemView.setOnClickListener {
                    if (inSelectionMode) toggle(item, holder.bindingAdapterPosition)
                    else onImageClick(item, imageIndex)
                }
                holder.itemView.setOnLongClickListener {
                    if (!inSelectionMode) enterSelection(item, holder.bindingAdapterPosition)
                    else toggle(item, holder.bindingAdapterPosition)
                    true
                }
            }
        }
    }

    fun currentPaths(): List<String> = currentList.filterIsInstance<GalleryItem.Image>().map { it.path }

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.folderThumbnail)
        val name: TextView = view.findViewById(R.id.folderName)
        val count: TextView = view.findViewById(R.id.folderCount)
        val checkIcon: ImageView = view.findViewById(R.id.checkIcon)
        val chevronIcon: ImageView = view.findViewById(R.id.chevronIcon)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val checkIcon: ImageView = view.findViewById(R.id.checkIcon)
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
