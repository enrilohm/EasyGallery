package com.example.easygallery

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrManager {

    private const val TAG = "OcrManager"
    private const val PREFS_NAME = "ocr"
    private const val KEY_SKIP = "skip_paths"
    private const val TARGET_PX = 1024

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
                runOcr(appContext)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Unexpected error: ${t::class.simpleName}: ${t.message}")
            } finally {
                _isRunning.postValue(false)
            }
        }
    }

    private suspend fun runOcr(appContext: Context) {
        VectorStore.init(appContext)

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skipPaths = Collections.synchronizedSet(prefs.getStringSet(KEY_SKIP, emptySet())!!.toMutableSet())

        val paths = queryImagePaths(appContext)
        val pending = paths.filter { it !in skipPaths && !VectorStore.hasOcrPath(it) }
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
                        val hash = md5(file)
                        if (hash == null) {
                            addToSkip(path, skipPaths, prefs)
                            _failed.postValue(skipPaths.size)
                            _processed.postValue(count.incrementAndGet()); return@withPermit
                        }
                        if (VectorStore.hasOcr(hash)) {
                            VectorStore.updateOcrPath(hash, path)
                            _processed.postValue(count.incrementAndGet()); return@withPermit
                        }
                        val text = try {
                            recognizeText(path)
                        } catch (t: Throwable) {
                            android.util.Log.w(TAG, "OCR failed: $path — ${t.message}")
                            addToSkip(path, skipPaths, prefs)
                            _failed.postValue(skipPaths.size)
                            _processed.postValue(count.incrementAndGet()); return@withPermit
                        }
                        VectorStore.insertOcr(hash, path, text)
                        _processed.postValue(count.incrementAndGet())
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun recognizeText(path: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX &&
               bounds.outHeight / (sample * 2) >= TARGET_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null

        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(result.text.ifBlank { null })
                }
                .addOnFailureListener { e ->
                    bitmap.recycle()
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
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
            _processed.postValue(VectorStore.ocrCount() + skipPaths.size)
        }
    }

    @Synchronized
    private fun addToSkip(path: String, skipPaths: MutableSet<String>, prefs: SharedPreferences) {
        skipPaths.add(path)
        prefs.edit().putStringSet(KEY_SKIP, HashSet(skipPaths)).apply()
    }

    private fun md5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { stream ->
                val buf = ByteArray(8192)
                var read: Int
                while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "MD5 error: ${e.message}")
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
            while (cursor.moveToNext()) cursor.getString(col)?.let { paths.add(it) }
        }
        return paths
    }
}
