package com.example.easygallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

class IndexingForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "indexing_channel"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, IndexingForegroundService::class.java)
            )
        }
    }

    private val intObserver = Observer<Int> { updateNotification(); checkIfDone() }
    private val boolObserver = Observer<Boolean> { updateNotification(); checkIfDone() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        observeInt(EmbeddingManager.processed)
        observeInt(OcrManager.processed)
        observeInt(ObjectDetectionManager.processed)
        observeInt(FaceIndexManager.processed)
        observeBool(EmbeddingManager.isRunning)
        observeBool(OcrManager.isRunning)
        observeBool(ObjectDetectionManager.isRunning)
        observeBool(FaceIndexManager.isRunning)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        EmbeddingManager.processed.removeObserver(intObserver)
        OcrManager.processed.removeObserver(intObserver)
        ObjectDetectionManager.processed.removeObserver(intObserver)
        FaceIndexManager.processed.removeObserver(intObserver)
        EmbeddingManager.isRunning.removeObserver(boolObserver)
        OcrManager.isRunning.removeObserver(boolObserver)
        ObjectDetectionManager.isRunning.removeObserver(boolObserver)
        FaceIndexManager.isRunning.removeObserver(boolObserver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeInt(ld: LiveData<Int>) = ld.observeForever(intObserver)
    private fun observeBool(ld: LiveData<Boolean>) = ld.observeForever(boolObserver)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Photo Indexing", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val parts = buildList {
            if (EmbeddingManager.isRunning.value == true)
                add("CLIP ${EmbeddingManager.processed.value ?: 0}/${EmbeddingManager.total.value ?: 0}")
            if (OcrManager.isRunning.value == true)
                add("OCR ${OcrManager.processed.value ?: 0}/${OcrManager.total.value ?: 0}")
            if (ObjectDetectionManager.isRunning.value == true)
                add("Objects ${ObjectDetectionManager.processed.value ?: 0}/${ObjectDetectionManager.total.value ?: 0}")
            if (FaceIndexManager.isRunning.value == true)
                add("Faces ${FaceIndexManager.processed.value ?: 0}/${FaceIndexManager.total.value ?: 0}")
        }
        val text = if (parts.isEmpty()) "Processing…" else parts.joinToString(" · ")

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Indexing photos")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun checkIfDone() {
        val anyRunning = EmbeddingManager.isRunning.value == true ||
                OcrManager.isRunning.value == true ||
                ObjectDetectionManager.isRunning.value == true ||
                FaceIndexManager.isRunning.value == true
        if (!anyRunning) stopSelf()
    }
}
