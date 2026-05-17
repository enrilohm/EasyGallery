package com.example.easygallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GalleryViewModel : ViewModel() {

    data class ImageEntry(val uri: Uri, val dir: String, val path: String)
    data class FilterState(val favoritesOnly: Boolean = false, val showHidden: Boolean = false)

    var entries: List<ImageEntry> = emptyList()
        private set

    var rootPath: String = ""
        private set

    val loaded = MutableLiveData(false)
    val filterState = MutableLiveData(FilterState())

    @Volatile private var favoritePaths: Set<String> = emptySet()
    @Volatile private var hiddenPaths: Set<String> = emptySet()

    private val rawEntries = MutableLiveData<List<ImageEntry>>()

    val filteredEntries = MediatorLiveData<List<ImageEntry>>()

    private var filterJob: Job? = null

    private fun scheduleRecompute() {
        val raw = rawEntries.value ?: return
        val state = filterState.value ?: FilterState()
        val hidden = hiddenPaths
        val favorites = favoritePaths
        filterJob?.cancel()
        filterJob = viewModelScope.launch(Dispatchers.Default) {
            var result = if (state.showHidden) raw else raw.filter { it.path !in hidden }
            if (state.favoritesOnly) result = result.filter { it.path in favorites }
            filteredEntries.postValue(result)
        }
    }

    init {
        filteredEntries.addSource(rawEntries) { scheduleRecompute() }
        filteredEntries.addSource(filterState) { scheduleRecompute() }
    }

    fun applyFilters(paths: List<String>): List<String> {
        val state = filterState.value ?: return paths
        var result = if (state.showHidden) paths else paths.filter { it !in hiddenPaths }
        if (state.favoritesOnly) result = result.filter { it in favoritePaths }
        return result
    }

    fun allowedPaths(): Set<String>? {
        val state = filterState.value ?: return null
        return when {
            state.favoritesOnly && !state.showHidden -> favoritePaths - hiddenPaths
            state.favoritesOnly -> favoritePaths
            else -> null
        }
    }

    fun setFavoritesOnly(enabled: Boolean, context: Context) {
        val current = filterState.value ?: FilterState()
        if (enabled) {
            Thread {
                VectorStore.init(context.applicationContext)
                favoritePaths = VectorStore.getFavoritePaths().toSet()
                filterState.postValue(current.copy(favoritesOnly = true))
            }.start()
        } else {
            favoritePaths = emptySet()
            filterState.value = current.copy(favoritesOnly = false)
        }
    }

    fun setShowHidden(show: Boolean, context: Context) {
        val current = filterState.value ?: FilterState()
        if (!show) {
            Thread {
                VectorStore.init(context.applicationContext)
                hiddenPaths = VectorStore.getHiddenPaths().toSet()
                filterState.postValue(current.copy(showHidden = false))
            }.start()
        } else {
            filterState.value = current.copy(showHidden = true)
        }
    }

    fun refreshHiddenPaths(context: Context) {
        Thread {
            VectorStore.init(context.applicationContext)
            val newHidden = VectorStore.getHiddenPaths().toSet()
            if (newHidden != hiddenPaths) {
                hiddenPaths = newHidden
                filterState.postValue(filterState.value ?: FilterState())
            }
        }.start()
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
            hiddenPaths = VectorStore.getHiddenPaths().toSet()
            entries = list
            @Suppress("DEPRECATION")
            rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
            rawEntries.postValue(list)
            loaded.postValue(true)
        }.start()
    }
}
