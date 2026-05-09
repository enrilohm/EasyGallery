package com.example.easygallery

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ImageInfoSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_image_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val uri = Uri.parse(arguments?.getString(ARG_URI) ?: return)
        val path = arguments?.getString(ARG_PATH) ?: ""
        val container = view.findViewById<LinearLayout>(R.id.infoContainer)

        addRow(container, "Path", path)

        try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)

                listOfNotNull(
                    exif.getAttribute(ExifInterface.TAG_MAKE),
                    exif.getAttribute(ExifInterface.TAG_MODEL)
                ).joinToString(" ").takeIf { it.isNotBlank() }
                    ?.let { addRow(container, "Camera", it) }

                (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
                    ?.let { addRow(container, "Date", it) }

                val w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                val h = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
                if (w != null && h != null) addRow(container, "Dimensions", "$w × $h px")

                val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                if (exposure > 0) {
                    val label = if (exposure < 1) "1/${(1.0 / exposure).roundToInt()}s" else "${exposure}s"
                    addRow(container, "Exposure", label)
                }

                val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                if (aperture > 0) addRow(container, "Aperture", "ƒ/%.1f".format(aperture))

                exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?.let { addRow(container, "ISO", it) }

                val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                if (focal > 0) addRow(container, "Focal length", "%.1f mm".format(focal))

                exif.latLong?.let { (lat, lon) ->
                    addRow(container, "GPS", "%.6f, %.6f".format(lat, lon))
                }

                exif.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { flash ->
                    addRow(container, "Flash", if (flash and 1 == 1) "Fired" else "Did not fire")
                }

                exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)?.toIntOrNull()?.let { wb ->
                    addRow(container, "White balance", if (wb == 0) "Auto" else "Manual")
                }
            }
        } catch (_: Exception) {
            addRow(container, "EXIF", "Not available")
        }

        if (path.isNotBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = requireContext()
                val (ocrText, labels) = withContext(Dispatchers.IO) {
                    VectorStore.init(ctx)
                    VectorStore.getOcrText(path) to VectorStore.getObjectLabels(path)
                }
                if (!ocrText.isNullOrBlank()) addRow(container, "Extracted text", ocrText)
                if (labels.isNotEmpty()) addRow(container, "Detected objects", labels.joinToString(", "))
            }
        }
    }

    private fun addRow(container: LinearLayout, label: String, value: String) {
        val row = layoutInflater.inflate(R.layout.item_info_row, container, false)
        row.findViewById<TextView>(R.id.infoLabel).text = label.uppercase()
        row.findViewById<TextView>(R.id.infoValue).text = value
        container.addView(row)
    }

    companion object {
        private const val ARG_URI = "uri"
        private const val ARG_PATH = "path"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, uri: Uri, path: String) {
            ImageInfoSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_URI, uri.toString())
                    putString(ARG_PATH, path)
                }
            }.show(fragmentManager, null)
        }
    }
}
