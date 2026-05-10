package com.example.easygallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class GalleryViewModel : ViewModel() {

    data class ImageEntry(val uri: Uri, val dir: String, val path: String)
    data class FilterState(val favoritesOnly: Boolean = false)

    var entries: List<ImageEntry> = emptyList()
        private set

    var rootPath: String = ""
        private set

    val loaded = MutableLiveData(false)
    val filterState = MutableLiveData(FilterState())

    private var favoritePaths: Set<String> = emptySet()

    // Internal LiveData that fires on every load/reload; filteredEntries derives from this.
    private val rawEntries = MutableLiveData<List<ImageEntry>>()

    val filteredEntries = MediatorLiveData<List<ImageEntry>>()

    init {
        filteredEntries.addSource(rawEntries) { filteredEntries.value = applyFilterToEntries(it) }
        filteredEntries.addSource(filterState) {
            val raw = rawEntries.value ?: return@addSource
            filteredEntries.value = applyFilterToEntries(raw)
        }
    }

    fun applyFilters(paths: List<String>): List<String> {
        val state = filterState.value ?: return paths
        return if (state.favoritesOnly) paths.filter { it in favoritePaths } else paths
    }

    fun allowedPaths(): Set<String>? {
        val state = filterState.value ?: return null
        return if (state.favoritesOnly) favoritePaths else null
    }

    fun setFavoritesOnly(enabled: Boolean, context: Context) {
        if (enabled) {
            Thread {
                VectorStore.init(context.applicationContext)
                favoritePaths = VectorStore.getFavoritePaths().toSet()
                filterState.postValue(FilterState(favoritesOnly = true))
            }.start()
        } else {
            favoritePaths = emptySet()
            filterState.value = FilterState(favoritesOnly = false)
        }
    }

    private fun applyFilterToEntries(list: List<ImageEntry>): List<ImageEntry> {
        val state = filterState.value ?: FilterState()
        return if (state.favoritesOnly) list.filter { it.path in favoritePaths } else list
    }

    fun load(resolver: ContentResolver) {
        if (loaded.value == true) return
        doLoad(resolver)
    }

    fun reload(resolver: ContentResolver) {
        doLoad(resolver)
    }

    private fun doLoad(resolver: ContentResolver) {
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
            rawEntries.postValue(list)
            loaded.postValue(true)
        }.start()
    }
}
