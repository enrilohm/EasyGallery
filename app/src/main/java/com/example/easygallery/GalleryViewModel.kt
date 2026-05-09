package com.example.easygallery

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class GalleryViewModel : ViewModel() {

    data class ImageEntry(val uri: Uri, val dir: String, val path: String)

    var entries: List<ImageEntry> = emptyList()
        private set

    var rootPath: String = ""
        private set

    val loaded = MutableLiveData(false)

    fun load(resolver: ContentResolver) {
        if (loaded.value == true) return
        Thread {
            val list = mutableListOf<ImageEntry>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol)
                    )
                    list.add(ImageEntry(uri, path.substringBeforeLast("/"), path))
                }
            }
            entries = list
            @Suppress("DEPRECATION")
            rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
            loaded.postValue(true)
        }.start()
    }
}
