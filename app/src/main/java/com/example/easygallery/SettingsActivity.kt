package com.example.easygallery

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.settingsScrollView)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        val prefs = getSharedPreferences("gallery_prefs", MODE_PRIVATE)

        // --- Columns ---
        val slider = findViewById<Slider>(R.id.columnSlider)
        slider.value = prefs.getInt("columns", 3).toFloat()
        slider.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt("columns", value.toInt()).apply()
        }

        // --- CLIP section views ---
        val clipSwitch = findViewById<SwitchMaterial>(R.id.clipSearchSwitch)
        val clipSection = findViewById<View>(R.id.clipSection)
        val modelProgressBar = findViewById<LinearProgressIndicator>(R.id.modelProgressBar)
        val modelStatusText = findViewById<TextView>(R.id.modelStatusText)
        val modelActionButton = findViewById<MaterialButton>(R.id.modelActionButton)
        val embeddingSection = findViewById<View>(R.id.embeddingSection)
        val embeddingProgressBar = findViewById<LinearProgressIndicator>(R.id.embeddingProgressBar)
        val embeddingProgressText = findViewById<TextView>(R.id.embeddingProgressText)
        val pauseResumeButton = findViewById<MaterialButton>(R.id.pauseResumeButton)

        // --- Model state observer ---
        ModelManager.state.observe(this) { state ->
            when (state) {
                is ModelManager.State.NotDownloaded -> {
                    modelProgressBar.visibility = View.GONE
                    modelStatusText.text = "Not downloaded (~150 MB)"
                    modelActionButton.text = "Download"
                    modelActionButton.isEnabled = true
                    embeddingSection.visibility = View.GONE
                }
                is ModelManager.State.Downloading -> {
                    modelProgressBar.visibility = View.VISIBLE
                    val label = if (state.file.isNotEmpty()) "${state.file}: " else ""
                    if (state.totalMb > 0) {
                        modelProgressBar.isIndeterminate = false
                        modelProgressBar.max = state.totalMb.toInt()
                        modelProgressBar.progress = state.downloadedMb.toInt()
                        modelStatusText.text = "$label%.1f / %.1f MB".format(state.downloadedMb, state.totalMb)
                    } else {
                        modelProgressBar.isIndeterminate = true
                        modelStatusText.text = "$label%.1f MB…".format(state.downloadedMb)
                    }
                    modelActionButton.text = "Cancel"
                    modelActionButton.isEnabled = true
                    embeddingSection.visibility = View.GONE
                }
                is ModelManager.State.Ready -> {
                    modelProgressBar.visibility = View.GONE
                    modelStatusText.text = "Ready"
                    modelActionButton.text = "Delete"
                    modelActionButton.isEnabled = true
                    if (clipSwitch.isChecked) {
                        embeddingSection.visibility = View.VISIBLE
                        EmbeddingManager.loadProgress(this)
                        EmbeddingManager.start(this)
                    }
                }
                is ModelManager.State.Failed -> {
                    modelProgressBar.visibility = View.GONE
                    modelStatusText.text = "Failed: ${state.message}"
                    modelActionButton.text = "Retry"
                    modelActionButton.isEnabled = true
                    embeddingSection.visibility = View.GONE
                }
            }
        }

        modelActionButton.setOnClickListener {
            when (ModelManager.state.value) {
                is ModelManager.State.NotDownloaded, is ModelManager.State.Failed ->
                    ModelManager.download(this)
                is ModelManager.State.Downloading ->
                    ModelManager.cancelDownload()
                is ModelManager.State.Ready ->
                    ModelManager.delete(this)
                else -> {}
            }
        }

        // --- Embedding observers ---
        fun updateEmbeddingText(processed: Int, failed: Int, total: Int) {
            val failedSuffix = if (failed > 0) " ($failed failed)" else ""
            embeddingProgressText.text = "$processed / $total images processed$failedSuffix"
            if (total > 0) {
                embeddingProgressBar.max = total
                embeddingProgressBar.progress = processed
            }
        }

        EmbeddingManager.processed.observe(this) { processed ->
            updateEmbeddingText(processed, EmbeddingManager.failed.value ?: 0, EmbeddingManager.total.value ?: 0)
        }
        EmbeddingManager.failed.observe(this) { failed ->
            updateEmbeddingText(EmbeddingManager.processed.value ?: 0, failed, EmbeddingManager.total.value ?: 0)
        }
        EmbeddingManager.total.observe(this) { total ->
            updateEmbeddingText(EmbeddingManager.processed.value ?: 0, EmbeddingManager.failed.value ?: 0, total)
        }
        EmbeddingManager.isRunning.observe(this) { running ->
            pauseResumeButton.text = if (running) "Pause" else "Resume"
        }

        pauseResumeButton.setOnClickListener {
            if (EmbeddingManager.isRunning.value == true) EmbeddingManager.pause()
            else EmbeddingManager.start(this)
        }

        // --- CLIP switch ---
        clipSwitch.isChecked = prefs.getBoolean("clip_search_enabled", false)
        clipSection.visibility = if (clipSwitch.isChecked) View.VISIBLE else View.GONE
        if (clipSwitch.isChecked) ModelManager.checkState(this)

        clipSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("clip_search_enabled", checked).apply()
            clipSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                ModelManager.checkState(this)
            } else {
                EmbeddingManager.pause()
                ModelManager.cancelDownload()
            }
        }

        // --- OCR section views ---
        val ocrSwitch = findViewById<SwitchMaterial>(R.id.ocrSwitch)
        val ocrSection = findViewById<View>(R.id.ocrSection)
        val ocrProgressBar = findViewById<LinearProgressIndicator>(R.id.ocrProgressBar)
        val ocrProgressText = findViewById<TextView>(R.id.ocrProgressText)
        val pauseResumeOcrButton = findViewById<MaterialButton>(R.id.pauseResumeOcrButton)

        fun updateOcrText(processed: Int, failed: Int, total: Int) {
            val failedSuffix = if (failed > 0) " ($failed failed)" else ""
            ocrProgressText.text = "$processed / $total images processed$failedSuffix"
            if (total > 0) {
                ocrProgressBar.max = total
                ocrProgressBar.progress = processed
            }
        }

        OcrManager.processed.observe(this) { processed ->
            updateOcrText(processed, OcrManager.failed.value ?: 0, OcrManager.total.value ?: 0)
        }
        OcrManager.failed.observe(this) { failed ->
            updateOcrText(OcrManager.processed.value ?: 0, failed, OcrManager.total.value ?: 0)
        }
        OcrManager.total.observe(this) { total ->
            updateOcrText(OcrManager.processed.value ?: 0, OcrManager.failed.value ?: 0, total)
        }
        OcrManager.isRunning.observe(this) { running ->
            pauseResumeOcrButton.text = if (running) "Pause" else "Resume"
        }

        pauseResumeOcrButton.setOnClickListener {
            if (OcrManager.isRunning.value == true) OcrManager.pause()
            else OcrManager.start(this)
        }

        ocrSwitch.isChecked = prefs.getBoolean("ocr_enabled", false)
        ocrSection.visibility = if (ocrSwitch.isChecked) View.VISIBLE else View.GONE
        if (ocrSwitch.isChecked) {
            OcrManager.loadProgress(this)
            OcrManager.start(this)
        }

        ocrSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("ocr_enabled", checked).apply()
            ocrSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                OcrManager.loadProgress(this)
                OcrManager.start(this)
            } else {
                OcrManager.pause()
            }
        }

        // --- Object Detection section views ---
        val objectDetectionSwitch = findViewById<SwitchMaterial>(R.id.objectDetectionSwitch)
        val objectDetectionSection = findViewById<View>(R.id.objectDetectionSection)
        val yoloProgressBar = findViewById<LinearProgressIndicator>(R.id.yoloProgressBar)
        val yoloStatusText = findViewById<TextView>(R.id.yoloStatusText)
        val yoloActionButton = findViewById<MaterialButton>(R.id.yoloActionButton)
        val objectProcessingSection = findViewById<View>(R.id.objectProcessingSection)
        val objectProgressBar = findViewById<LinearProgressIndicator>(R.id.objectProgressBar)
        val objectProgressText = findViewById<TextView>(R.id.objectProgressText)
        val pauseResumeObjectButton = findViewById<MaterialButton>(R.id.pauseResumeObjectButton)

        // --- Yolo model state observer ---
        YoloModelManager.state.observe(this) { state ->
            when (state) {
                is YoloModelManager.State.NotDownloaded -> {
                    yoloProgressBar.visibility = View.GONE
                    yoloStatusText.text = "Not downloaded (~45 MB)"
                    yoloActionButton.text = "Download"
                    yoloActionButton.isEnabled = true
                    objectProcessingSection.visibility = View.GONE
                }
                is YoloModelManager.State.Downloading -> {
                    yoloProgressBar.visibility = View.VISIBLE
                    if (state.totalMb > 0) {
                        yoloProgressBar.isIndeterminate = false
                        yoloProgressBar.max = state.totalMb.toInt()
                        yoloProgressBar.progress = state.downloadedMb.toInt()
                        yoloStatusText.text = "%.1f / %.1f MB".format(state.downloadedMb, state.totalMb)
                    } else {
                        yoloProgressBar.isIndeterminate = true
                        yoloStatusText.text = "%.1f MB…".format(state.downloadedMb)
                    }
                    yoloActionButton.text = "Cancel"
                    yoloActionButton.isEnabled = true
                    objectProcessingSection.visibility = View.GONE
                }
                is YoloModelManager.State.Ready -> {
                    yoloProgressBar.visibility = View.GONE
                    yoloStatusText.text = "Ready"
                    yoloActionButton.text = "Delete"
                    yoloActionButton.isEnabled = true
                    if (objectDetectionSwitch.isChecked) {
                        objectProcessingSection.visibility = View.VISIBLE
                        ObjectDetectionManager.loadProgress(this)
                        ObjectDetectionManager.start(this)
                    }
                }
                is YoloModelManager.State.Failed -> {
                    yoloProgressBar.visibility = View.GONE
                    yoloStatusText.text = "Failed: ${state.message}"
                    yoloActionButton.text = "Retry"
                    yoloActionButton.isEnabled = true
                    objectProcessingSection.visibility = View.GONE
                }
            }
        }

        yoloActionButton.setOnClickListener {
            when (YoloModelManager.state.value) {
                is YoloModelManager.State.NotDownloaded, is YoloModelManager.State.Failed ->
                    YoloModelManager.download(this)
                is YoloModelManager.State.Downloading ->
                    YoloModelManager.cancelDownload()
                is YoloModelManager.State.Ready ->
                    YoloModelManager.delete(this)
                else -> {}
            }
        }

        // --- Object Detection processing observers ---
        fun updateObjectText(processed: Int, failed: Int, total: Int) {
            val failedSuffix = if (failed > 0) " ($failed failed)" else ""
            objectProgressText.text = "$processed / $total images processed$failedSuffix"
            if (total > 0) {
                objectProgressBar.max = total
                objectProgressBar.progress = processed
            }
        }

        ObjectDetectionManager.processed.observe(this) { processed ->
            updateObjectText(processed, ObjectDetectionManager.failed.value ?: 0, ObjectDetectionManager.total.value ?: 0)
        }
        ObjectDetectionManager.failed.observe(this) { failed ->
            updateObjectText(ObjectDetectionManager.processed.value ?: 0, failed, ObjectDetectionManager.total.value ?: 0)
        }
        ObjectDetectionManager.total.observe(this) { total ->
            updateObjectText(ObjectDetectionManager.processed.value ?: 0, ObjectDetectionManager.failed.value ?: 0, total)
        }
        ObjectDetectionManager.isRunning.observe(this) { running ->
            pauseResumeObjectButton.text = if (running) "Pause" else "Resume"
        }

        pauseResumeObjectButton.setOnClickListener {
            if (ObjectDetectionManager.isRunning.value == true) ObjectDetectionManager.pause()
            else ObjectDetectionManager.start(this)
        }

        // --- Object Detection switch ---
        objectDetectionSwitch.isChecked = prefs.getBoolean("object_detection_enabled", false)
        objectDetectionSection.visibility = if (objectDetectionSwitch.isChecked) View.VISIBLE else View.GONE
        if (objectDetectionSwitch.isChecked) YoloModelManager.checkState(this)

        objectDetectionSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("object_detection_enabled", checked).apply()
            objectDetectionSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                YoloModelManager.checkState(this)
            } else {
                ObjectDetectionManager.pause()
                YoloModelManager.cancelDownload()
            }
        }

        // --- Face Detection section views ---
        val faceDetectionSwitch = findViewById<SwitchMaterial>(R.id.faceDetectionSwitch)
        val faceDetectionSection = findViewById<View>(R.id.faceDetectionSection)
        val faceModelProgressBar = findViewById<LinearProgressIndicator>(R.id.faceModelProgressBar)
        val faceModelStatusText = findViewById<TextView>(R.id.faceModelStatusText)
        val faceModelActionButton = findViewById<MaterialButton>(R.id.faceModelActionButton)
        val faceProcessingSection = findViewById<View>(R.id.faceProcessingSection)
        val faceProgressBar = findViewById<LinearProgressIndicator>(R.id.faceProgressBar)
        val faceProgressText = findViewById<TextView>(R.id.faceProgressText)
        val pauseResumeFaceButton = findViewById<MaterialButton>(R.id.pauseResumeFaceButton)

        FaceModelManager.state.observe(this) { state ->
            when (state) {
                is FaceModelManager.State.NotDownloaded -> {
                    faceModelProgressBar.visibility = View.GONE
                    faceModelStatusText.text = "Not downloaded (~13 MB)"
                    faceModelActionButton.text = "Download"
                    faceModelActionButton.isEnabled = true
                    faceProcessingSection.visibility = View.GONE
                }
                is FaceModelManager.State.Downloading -> {
                    faceModelProgressBar.visibility = View.VISIBLE
                    if (state.totalMb > 0) {
                        faceModelProgressBar.isIndeterminate = false
                        faceModelProgressBar.max = state.totalMb.toInt()
                        faceModelProgressBar.progress = state.downloadedMb.toInt()
                        faceModelStatusText.text = "%.1f / %.1f MB".format(state.downloadedMb, state.totalMb)
                    } else {
                        faceModelProgressBar.isIndeterminate = true
                        faceModelStatusText.text = "%.1f MB…".format(state.downloadedMb)
                    }
                    faceModelActionButton.text = "Cancel"
                    faceModelActionButton.isEnabled = true
                    faceProcessingSection.visibility = View.GONE
                }
                is FaceModelManager.State.Ready -> {
                    faceModelProgressBar.visibility = View.GONE
                    faceModelStatusText.text = "Ready"
                    faceModelActionButton.text = "Delete"
                    faceModelActionButton.isEnabled = true
                    if (faceDetectionSwitch.isChecked) {
                        faceProcessingSection.visibility = View.VISIBLE
                        FaceIndexManager.loadProgress(this)
                        FaceIndexManager.start(this)
                    }
                }
                is FaceModelManager.State.Failed -> {
                    faceModelProgressBar.visibility = View.GONE
                    faceModelStatusText.text = "Failed: ${state.message}"
                    faceModelActionButton.text = "Retry"
                    faceModelActionButton.isEnabled = true
                    faceProcessingSection.visibility = View.GONE
                }
            }
        }

        faceModelActionButton.setOnClickListener {
            when (FaceModelManager.state.value) {
                is FaceModelManager.State.NotDownloaded, is FaceModelManager.State.Failed ->
                    FaceModelManager.download(this)
                is FaceModelManager.State.Downloading ->
                    FaceModelManager.cancelDownload()
                is FaceModelManager.State.Ready ->
                    FaceModelManager.delete(this)
                else -> {}
            }
        }

        fun updateFaceText(processed: Int, total: Int) {
            faceProgressText.text = "$processed / $total images processed"
            if (total > 0) {
                faceProgressBar.max = total
                faceProgressBar.progress = processed
            }
        }

        FaceIndexManager.processed.observe(this) { processed ->
            updateFaceText(processed, FaceIndexManager.total.value ?: 0)
        }
        FaceIndexManager.total.observe(this) { total ->
            updateFaceText(FaceIndexManager.processed.value ?: 0, total)
        }
        FaceIndexManager.isRunning.observe(this) { running ->
            pauseResumeFaceButton.text = if (running) "Pause" else "Resume"
        }

        pauseResumeFaceButton.setOnClickListener {
            if (FaceIndexManager.isRunning.value == true) FaceIndexManager.stop()
            else FaceIndexManager.start(this)
        }

        faceDetectionSwitch.isChecked = prefs.getBoolean("face_detection_enabled", false)
        faceDetectionSection.visibility = if (faceDetectionSwitch.isChecked) View.VISIBLE else View.GONE
        if (faceDetectionSwitch.isChecked) FaceModelManager.checkState(this)

        faceDetectionSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("face_detection_enabled", checked).apply()
            faceDetectionSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                FaceModelManager.checkState(this)
            } else {
                FaceIndexManager.stop()
                FaceModelManager.cancelDownload()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
