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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.MessageDigest
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

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext

        job = scope.launch {
            _isRunning.postValue(true)
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
        val skipPaths = prefs.getStringSet(KEY_SKIP, emptySet())!!.toMutableSet()

        val paths = queryImagePaths(appContext)
        _total.postValue(paths.size)
        _failed.postValue(skipPaths.size)
        _processed.postValue(VectorStore.ocrCount() + skipPaths.size)

        var done = 0

        for (path in paths) {
            if (!currentCoroutineContext().isActive) break
            if (path in skipPaths) { done++; _processed.postValue(done); continue }

            val file = File(path)
            if (!file.exists()) {
                addToSkip(path, skipPaths, prefs)
                done++; _processed.postValue(done); _failed.postValue(skipPaths.size)
                continue
            }

            val hash = md5(file)
            if (hash == null) {
                addToSkip(path, skipPaths, prefs)
                done++; _processed.postValue(done); _failed.postValue(skipPaths.size)
                continue
            }

            if (VectorStore.hasOcr(hash)) {
                VectorStore.updateOcrPath(hash, path)
                done++; _processed.postValue(done)
                continue
            }

            val text = try {
                recognizeText(path)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "OCR failed: $path — ${t.message}")
                addToSkip(path, skipPaths, prefs)
                done++; _processed.postValue(done); _failed.postValue(skipPaths.size)
                continue
            }

            VectorStore.insertOcr(hash, path, text)
            done++
            _processed.postValue(done)
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
