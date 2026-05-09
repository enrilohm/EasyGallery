package com.example.easygallery

import android.net.Uri

sealed class GalleryItem {
    data class Folder(val path: String, val name: String, val count: Int, val coverUri: Uri) : GalleryItem()
    data class Image(val uri: Uri, val path: String) : GalleryItem()
}
