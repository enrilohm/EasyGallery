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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

object EmbeddingManager {

    private const val TAG = "EmbeddingManager"
    private const val PREFS_NAME = "embeddings"
    private const val KEY_SKIP = "skip_paths"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // paths handled this session (new embeds + already-done + failures)
    private val _processed = MutableLiveData(0)
    val processed: LiveData<Int> = _processed

    private val _failed = MutableLiveData(0)
    val failed: LiveData<Int> = _failed

    private val _total = MutableLiveData(0)
    val total: LiveData<Int> = _total

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext

        job = scope.launch {
            _isRunning.postValue(true)
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
        val skipPaths = prefs.getStringSet(KEY_SKIP, emptySet())!!.toMutableSet()

        val paths = queryImagePaths(appContext)
        _total.postValue(paths.size)
        _failed.postValue(skipPaths.size)
        // Approximate initial processed count; will be updated accurately as we iterate
        _processed.postValue(VectorStore.count() + skipPaths.size)

        var done = 0

        for (path in paths) {
            if (!currentCoroutineContext().isActive) break

            // Already permanently skipped
            if (path in skipPaths) {
                done++
                _processed.postValue(done)
                continue
            }

            val file = File(path)
            if (!file.exists()) {
                android.util.Log.w(TAG, "File gone: $path")
                addToSkip(path, skipPaths, prefs)
                done++
                _processed.postValue(done)
                _failed.postValue(skipPaths.size)
                continue
            }

            val hash = md5(file)
            if (hash == null) {
                android.util.Log.w(TAG, "MD5 failed: $path")
                addToSkip(path, skipPaths, prefs)
                done++
                _processed.postValue(done)
                _failed.postValue(skipPaths.size)
                continue
            }

            // Already embedded (including duplicates with the same content hash)
            if (VectorStore.hasHash(hash)) {
                VectorStore.updatePath(hash, path)
                done++
                _processed.postValue(done)
                continue
            }

            val embedding = ClipEncoder.encode(path)
            if (embedding == null) {
                android.util.Log.w(TAG, "Encode failed: $path")
                addToSkip(path, skipPaths, prefs)
                done++
                _processed.postValue(done)
                _failed.postValue(skipPaths.size)
                continue
            }

            VectorStore.insert(hash, path, embedding)
            done++
            _processed.postValue(done)
        }
    }

    private fun addToSkip(path: String, skipPaths: MutableSet<String>, prefs: SharedPreferences) {
        skipPaths.add(path)
        prefs.edit().putStringSet(KEY_SKIP, HashSet(skipPaths)).apply()
    }

    fun pause() {
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

    private fun md5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { stream ->
                val buf = ByteArray(8192)
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "MD5 error for ${file.path}: ${e.message}")
            null
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
