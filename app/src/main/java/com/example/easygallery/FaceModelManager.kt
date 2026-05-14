package com.example.easygallery

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

object FaceModelManager {

    private const val TAG = "FaceModelManager"

    private data class ModelSpec(val file: String, val url: String)

    private val MODELS = listOf(
        ModelSpec("pnet.tflite",
            "https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/pnet.tflite"),
        ModelSpec("rnet.tflite",
            "https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/rnet.tflite"),
        ModelSpec("onet.tflite",
            "https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/onet.tflite"),
        ModelSpec("w600k_mbf.onnx",
            "https://huggingface.co/WePrompt/buffalo_sc/resolve/main/w600k_mbf.onnx?download=true"),
    )

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val file: String, val downloadedMb: Float, val totalMb: Float) : State()
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

    fun modelFile(context: Context, name: String) = File(context.filesDir, name)
    fun encoderFile(context: Context) = modelFile(context, "w600k_mbf.onnx")

    fun checkState(context: Context) {
        val allPresent = MODELS.all { spec ->
            val f = modelFile(context, spec.file)
            f.exists() && f.length() > 0
        }
        _state.postValue(if (allPresent) State.Ready else State.NotDownloaded)
    }

    fun download(context: Context) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext

        downloadJob = scope.launch {
            _state.postValue(State.Downloading("", 0f, 0f))
            try {
                for (spec in MODELS) {
                    if (!currentCoroutineContext().isActive) {
                        _state.postValue(State.NotDownloaded)
                        return@launch
                    }
                    val dest = modelFile(appContext, spec.file)
                    if (dest.exists() && dest.length() > 0) continue

                    val tmp = File(dest.parent, "${spec.file}.tmp")
                    val request = Request.Builder().url(spec.url)
                        .header("User-Agent", "EasyGallery/1.0").build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${spec.file}")

                    val body = response.body ?: throw IOException("Empty body for ${spec.file}")
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
                                    file = spec.file,
                                    downloadedMb = downloaded / 1_000_000f,
                                    totalMb = if (totalBytes > 0) totalBytes / 1_000_000f else 0f
                                ))
                            }
                        }
                    }
                    tmp.renameTo(dest)
                }
                _state.postValue(State.Ready)
            } catch (e: UnknownHostException) {
                _state.postValue(State.Failed("Cannot reach server — check internet connection"))
            } catch (e: SocketTimeoutException) {
                _state.postValue(State.Failed("Connection timed out"))
            } catch (e: Exception) {
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
        MODELS.forEach { spec -> modelFile(context, spec.file).delete() }
        _state.postValue(State.NotDownloaded)
    }
}
