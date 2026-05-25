package com.example.easygallery.faces

import android.content.Context
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
import java.util.concurrent.atomic.AtomicInteger
import com.example.easygallery.db.AppDatabase

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

    @Volatile var isPaused = false
        private set

    fun start(context: Context) {
        if (job?.isActive == true) return
        isPaused = false
        val appContext = context.applicationContext

        _isRunning.postValue(true)
        job = scope.launch {
            try {
                AppDatabase.init(appContext)
                FaceEncoder.load(appContext)
                FaceDetector.init(appContext)

                val allPaths = queryImagePaths(appContext)
                val pending = allPaths.filter { !FacesStore.hasFaceEntry(it) }
                _total.postValue(allPaths.size)
                _processed.postValue(allPaths.size - pending.size)

                val count = AtomicInteger(allPaths.size - pending.size)
                val parallelism = minOf(4, Runtime.getRuntime().availableProcessors())
                val semaphore = Semaphore(parallelism)

                coroutineScope {
                    pending.map { path ->
                        async {
                            if (!isActive) return@async
                            semaphore.withPermit {
                                try {
                                    val faces = FaceDetector.detect(path)
                                    faces.forEach { face ->
                                        val embedding = FaceEncoder.encode(face.crop)
                                        if (embedding != null) {
                                            FacesStore.insertFace(
                                                path           = path,
                                                faceIndex      = face.faceIndex,
                                                embedding      = embedding,
                                                bbox           = face.bounds,
                                                yaw            = face.yaw,
                                                pitch          = face.pitch,
                                                roll           = face.roll,
                                                faceSize       = face.faceRelativeSize,
                                                blurScore      = face.blurScore,
                                                hasLandmarks   = face.hasLandmarks,
                                                detectionScore = face.detectionScore,
                                            )
                                        }
                                    }
                                    if (faces.isEmpty()) {
                                        FacesStore.insertFace(path, -1, FloatArray(0))
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e(TAG, "Failed $path: ${e.message}")
                                }
                                _processed.postValue(count.incrementAndGet())
                            }
                        }
                    }.awaitAll()
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Unexpected error: ${t::class.simpleName}: ${t.message}")
            } finally {
                _isRunning.postValue(false)
            }
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
        isPaused = true
        job?.cancel()
        _isRunning.postValue(false)
    }

    fun loadProgress(context: Context) {
        scope.launch {
            AppDatabase.init(context.applicationContext)
            val done = FacesStore.countFaceEntries()
            val total = queryImagePaths(context.applicationContext).size
            _processed.postValue(done)
            _total.postValue(total)
        }
    }
}
