package com.example.easygallery

import android.content.Context
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

object EmbeddingManager {

    private const val TAG = "EmbeddingManager"
    private const val PREFS_NAME = "embeddings"
    private const val KEY_SKIP = "skip_paths"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _processed = MutableLiveData(0)
    val processed: LiveData<Int> = _processed

    private val _failed = MutableLiveData(0)
    val failed: LiveData<Int> = _failed

    private val _total = MutableLiveData(0)
    val total: LiveData<Int> = _total

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    @Volatile var isPaused = false
        private set

    fun start(context: Context) {
        if (job?.isActive == true) return
        isPaused = false
        val appContext = context.applicationContext
        _isRunning.postValue(true)
        job = scope.launch {
            try {
                runEmbedding(appContext)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Unexpected error: ${t::class.simpleName}: ${t.message}")
            } finally {
                _isRunning.postValue(false)
            }
        }
    }

    private suspend fun runEmbedding(appContext: Context) {
        VectorStore.init(appContext)
        ClipEncoder.load(appContext)

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skipPaths = Collections.synchronizedSet(prefs.getStringSet(KEY_SKIP, emptySet())!!.toMutableSet())

        val paths = queryImagePaths(appContext)
        val pending = paths.filter { it !in skipPaths && !VectorStore.hasEmbeddingPath(it) }
        _total.postValue(paths.size)
        _failed.postValue(skipPaths.size)
        val alreadyDone = paths.size - pending.size
        _processed.postValue(alreadyDone)

        val count = AtomicInteger(alreadyDone)
        val parallelism = minOf(4, Runtime.getRuntime().availableProcessors())
        val semaphore = Semaphore(parallelism)

        coroutineScope {
            pending.map { path ->
                async {
                    if (!isActive) return@async
                    semaphore.withPermit {
                        val file = File(path)
                        if (!file.exists()) {
                            addToSkip(path, skipPaths, prefs)
                            _failed.postValue(skipPaths.size)
                            _processed.postValue(count.incrementAndGet()); return@withPermit
                        }
                        val bitmap = ClipEncoder.decodeBitmap(path)
                        if (bitmap == null) {
                            addToSkip(path, skipPaths, prefs)
                            _failed.postValue(skipPaths.size)
                            _processed.postValue(count.incrementAndGet()); return@withPermit
                        }
                        val emb = ClipEncoder.encodeBatch(listOf(bitmap))[0]
                        bitmap.recycle()
                        if (emb != null) {
                            VectorStore.insert(path, emb)
                        } else {
                            addToSkip(path, skipPaths, prefs)
                            _failed.postValue(skipPaths.size)
                        }
                        _processed.postValue(count.incrementAndGet())
                    }
                }
            }.awaitAll()
        }
    }

    @Synchronized
    private fun addToSkip(path: String, skipPaths: MutableSet<String>, prefs: SharedPreferences) {
        skipPaths.add(path)
        prefs.edit().putStringSet(KEY_SKIP, HashSet(skipPaths)).apply()
    }

    fun pause() {
        isPaused = true
        job?.cancel()
        job = null
        _isRunning.postValue(false)
    }

    fun loadProgress(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            VectorStore.init(appContext)
            val paths = queryImagePaths(appContext)
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val skipPaths = prefs.getStringSet(KEY_SKIP, emptySet())!!
            _total.postValue(paths.size)
            _failed.postValue(skipPaths.size)
            _processed.postValue(VectorStore.count() + skipPaths.size)
        }
    }

    private fun queryImagePaths(context: Context): List<String> {
        val paths = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATA),
            null, null, null
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                cursor.getString(col)?.let { paths.add(it) }
            }
        }
        return paths
    }
}
