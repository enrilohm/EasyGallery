package com.example.easygallery

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object FaceIndexManager {

    private const val TAG = "FaceIndexManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _processed  = MutableLiveData(0)
    private val _total      = MutableLiveData(0)
    private val _isRunning  = MutableLiveData(false)

    val processed:  LiveData<Int>     = _processed
    val total:      LiveData<Int>     = _total
    val isRunning:  LiveData<Boolean> = _isRunning

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext

        job = scope.launch {
            _isRunning.postValue(true)
            VectorStore.init(appContext)
            FaceEncoder.load(appContext)

            val allPaths = queryImagePaths(appContext)
            val pending = allPaths.filter { !VectorStore.hasFaceEntry(it) }
            _total.postValue(allPaths.size)
            _processed.postValue(allPaths.size - pending.size)

            var count = allPaths.size - pending.size
            for (path in pending) {
                if (!isActive) break
                try {
                    val faces = FaceDetector.detect(path)
                    faces.forEach { face ->
                        val embedding = FaceEncoder.encode(face.crop)
                        if (embedding != null) {
                            VectorStore.insertFace(path, face.faceIndex, embedding)
                        }
                    }
                    if (faces.isEmpty()) {
                        VectorStore.insertFace(path, -1, FloatArray(0))
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed $path: ${e.message}")
                }
                count++
                _processed.postValue(count)
            }
            _isRunning.postValue(false)
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

    fun stop() {
        job?.cancel()
        _isRunning.postValue(false)
    }

    fun loadProgress(context: Context) {
        scope.launch {
            VectorStore.init(context.applicationContext)
            val done = VectorStore.countFaceEntries()
            val total = queryImagePaths(context.applicationContext).size
            _processed.postValue(done)
            _total.postValue(total)
        }
    }
}
