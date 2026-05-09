package com.example.easygallery

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
