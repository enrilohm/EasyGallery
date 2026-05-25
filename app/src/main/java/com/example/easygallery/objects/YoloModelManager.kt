package com.example.easygallery.objects

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object YoloModelManager {

    private const val TAG = "YoloModelManager"
    private const val MODEL_URL =
        "https://huggingface.co/deepghs/yolos/resolve/main/yolov8s/model.onnx"
    private const val MODEL_FILE = "yolov8s.onnx"

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val downloadedMb: Float, val totalMb: Float) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableLiveData<State>(State.NotDownloaded)
    val state: LiveData<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelFile(context: Context) = File(context.filesDir, MODEL_FILE)

    fun checkState(context: Context) {
        val f = modelFile(context)
        _state.postValue(if (f.exists() && f.length() > 0) State.Ready else State.NotDownloaded)
    }

    fun download(context: Context) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        val dest = modelFile(appContext)
        val tmp = File(dest.parent, "${dest.name}.tmp")

        downloadJob = scope.launch {
            _state.postValue(State.Downloading(0f, 0f))
            try {
                val request = Request.Builder().url(MODEL_URL)
                    .header("User-Agent", "EasyGallery/1.0").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

                val body = response.body ?: throw IOException("Empty body")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            if (!currentCoroutineContext().isActive) {
                                tmp.delete()
                                _state.postValue(State.NotDownloaded)
                                return@launch
                            }
                            output.write(buf, 0, read)
                            downloaded += read
                            _state.postValue(State.Downloading(
                                downloaded / 1_000_000f,
                                if (totalBytes > 0) totalBytes / 1_000_000f else 0f
                            ))
                        }
                    }
                }
                tmp.renameTo(dest)
                _state.postValue(State.Ready)

            } catch (e: UnknownHostException) {
                tmp.delete()
                _state.postValue(State.Failed("Cannot reach server — check internet connection"))
            } catch (e: SocketTimeoutException) {
                tmp.delete()
                _state.postValue(State.Failed("Connection timed out"))
            } catch (e: Exception) {
                tmp.delete()
                android.util.Log.e(TAG, "Download failed: ${e.message}")
                _state.postValue(State.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        client.dispatcher.cancelAll()
        _state.postValue(State.NotDownloaded)
    }

    fun delete(context: Context) {
        cancelDownload()
        modelFile(context).delete()
        _state.postValue(State.NotDownloaded)
    }
}
