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

object ModelManager {

    private const val TAG = "ModelManager"
    private const val BASE_URL =
        "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val downloadedMb: Float, val totalMb: Float, val file: String = "") : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableLiveData<State>(State.NotDownloaded)
    val state: LiveData<State> = _state

    fun modelFile(context: Context)     = File(context.filesDir, "models/clip_vision_quantized.onnx")
    fun textModelFile(context: Context) = File(context.filesDir, "models/clip_text_quantized.onnx")
    fun tokenizerFile(context: Context) = File(context.filesDir, "models/tokenizer.json")

    private fun allReady(context: Context) =
        listOf(modelFile(context), textModelFile(context), tokenizerFile(context))
            .all { it.exists() && it.length() > 0 }

    fun checkState(context: Context) {
        _state.postValue(if (allReady(context)) State.Ready else State.NotDownloaded)
    }

    fun download(context: Context) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext

        downloadJob = scope.launch {
            _state.postValue(State.Downloading(0f, 0f))
            try {
                File(appContext.filesDir, "models").mkdirs()

                downloadFile("$BASE_URL/onnx/vision_model_quantized.onnx", modelFile(appContext),     "Vision model")
                if (!currentCoroutineContext().isActive) { _state.postValue(State.NotDownloaded); return@launch }

                downloadFile("$BASE_URL/onnx/text_model_quantized.onnx",   textModelFile(appContext), "Text model")
                if (!currentCoroutineContext().isActive) { _state.postValue(State.NotDownloaded); return@launch }

                downloadFile("$BASE_URL/tokenizer.json",                    tokenizerFile(appContext), "Tokenizer")
                if (!currentCoroutineContext().isActive) { _state.postValue(State.NotDownloaded); return@launch }

                _state.postValue(State.Ready)

            } catch (e: UnknownHostException) {
                android.util.Log.e(TAG, "DNS failure: ${e.message}")
                _state.postValue(State.Failed("Cannot reach server — check internet connection"))
            } catch (e: SocketTimeoutException) {
                android.util.Log.e(TAG, "Timeout: ${e.message}")
                _state.postValue(State.Failed("Connection timed out"))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed: ${e::class.simpleName}: ${e.message}")
                _state.postValue(State.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun downloadFile(url: String, dest: File, label: String) {
        if (dest.exists() && dest.length() > 0) {
            android.util.Log.d(TAG, "$label already downloaded")
            return
        }
        android.util.Log.d(TAG, "Downloading $label from $url")
        val tmp = File(dest.parent, "${dest.name}.tmp")

        val request = Request.Builder().url(url).header("User-Agent", "EasyGallery/1.0").build()
        val response = client.newCall(request).execute()
        android.util.Log.d(TAG, "HTTP ${response.code} for $label")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $label")

        val body = response.body ?: throw IOException("Empty body for $label")
        val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L
        var downloaded = 0L

        body.byteStream().use { input ->
            FileOutputStream(tmp).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    if (!currentCoroutineContext().isActive) {
                        tmp.delete()
                        return
                    }
                    output.write(buf, 0, read)
                    downloaded += read
                    _state.postValue(State.Downloading(
                        downloaded / 1_000_000f,
                        if (totalBytes > 0) totalBytes / 1_000_000f else 0f,
                        label
                    ))
                }
            }
        }
        tmp.renameTo(dest)
        android.util.Log.d(TAG, "$label done — $downloaded bytes")
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        client.dispatcher.cancelAll()
    }

    fun delete(context: Context) {
        cancelDownload()
        modelFile(context).delete()
        textModelFile(context).delete()
        tokenizerFile(context).delete()
        _state.postValue(State.NotDownloaded)
    }
}
