package com.example.easygallery.gallery

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.easygallery.faces.FaceOverlayView
import com.example.easygallery.faces.FacesStore
import com.example.easygallery.MainActivity
import com.example.easygallery.R

class ImageDetailViewModel : ViewModel() {
    var paths: List<String> = emptyList()
}

class ImageDetailActivity : AppCompatActivity() {

    private val faceBoxCache = mutableMapOf<String, Pair<List<RectF>, List<RectF>>>()
    private val holders = SparseArray<DetailPagerAdapter.VH>()
    private var showFaces = false
    private var highlightClusterId = -1L
    private lateinit var paths: List<String>
    private lateinit var adapter: DetailPagerAdapter
    private lateinit var favoriteButton: ImageButton
    private lateinit var hiddenButton: ImageButton
    private var clipEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.easygallery.SimilarNav.register(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_image_detail)

        val detailVm = ViewModelProvider(this)[ImageDetailViewModel::class.java]
        if (detailVm.paths.isEmpty()) {
            detailVm.paths = pendingPaths?.also { pendingPaths = null }
                ?: intent.getStringArrayListExtra(EXTRA_PATHS)
                ?: return finish()
        }
        paths = detailVm.paths
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        highlightClusterId = intent.getLongExtra(EXTRA_HIGHLIGHT_CLUSTER_ID, -1L)

        adapter = DetailPagerAdapter(paths)
        val pager = findViewById<ViewPager2>(R.id.detailPager)
        pager.adapter = adapter
        pager.setCurrentItem(startIndex, false)

        // Keep the top toolbar clear of the status bar / front-camera cutout.
        val topBar = findViewById<View>(R.id.topBar)
        val baseMargin = (topBar.layoutParams as ViewGroup.MarginLayoutParams)
        val baseLeft = baseMargin.leftMargin
        val baseTop = baseMargin.topMargin
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (v.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = baseTop + bars.top
                leftMargin = baseLeft + bars.left
                v.layoutParams = this
            }
            insets
        }

        val controller = WindowInsetsControllerCompat(window, pager)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        favoriteButton = findViewById(R.id.favoriteButton)
        hiddenButton = findViewById(R.id.hiddenButton)
        updateFavoriteButton(paths[startIndex])
        updateHiddenButton(paths[startIndex])

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (showFaces) loadAndShowFaces(position)
                updateFavoriteButton(paths[position])
                updateHiddenButton(paths[position])
            }
        })

        favoriteButton.setOnClickListener {
            val path = paths[pager.currentItem]
            lifecycleScope.launch(Dispatchers.IO) {
                val nowFavorite = !MetaStore.isFavorite(path)
                MetaStore.setFavorite(path, nowFavorite)
                withContext(Dispatchers.Main) { updateFavoriteButton(path) }
            }
        }

        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }

        findViewById<View>(R.id.infoButton).setOnClickListener {
            val path = paths[pager.currentItem]
            ImageInfoSheet.show(supportFragmentManager, Uri.fromFile(File(path)), path)
        }

        val findSimilarButton = findViewById<ImageButton>(R.id.findSimilarButton)
        clipEnabled = getSharedPreferences("gallery_prefs", MODE_PRIVATE)
            .getBoolean("clip_search_enabled", false)
        findSimilarButton.visibility = if (clipEnabled) View.VISIBLE else View.GONE
        findSimilarButton.setOnClickListener {
            val crop = holders[pager.currentItem]?.cropFrame?.selectionCrop
            launchSimilar(paths[pager.currentItem], crop)
        }

        hiddenButton.setOnClickListener {
            val path = paths[pager.currentItem]
            lifecycleScope.launch(Dispatchers.IO) {
                val nowHidden = !MetaStore.isHidden(path)
                MetaStore.setHidden(path, nowHidden)
                withContext(Dispatchers.Main) { updateHiddenButton(path) }
            }
        }

        findViewById<View>(R.id.shareButton).setOnClickListener {
            val path = paths[pager.currentItem]
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", File(path)
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null
            ))
        }

        val faceToggle = findViewById<ImageButton>(R.id.faceToggleButton)
        faceToggle.setOnClickListener {
            showFaces = !showFaces
            faceToggle.alpha = if (showFaces) 1f else 0.4f
            if (showFaces) {
                loadAndShowFaces(pager.currentItem)
            } else {
                holders[pager.currentItem]?.overlay?.clear()
            }
        }
    }

    private fun launchSimilar(path: String, crop: RectF?) {
        // Collapse any older similar-search layers first, keeping only the
        // task-root gallery and this image detail. This caps the back stack at
        // [gallery] -> [this image] -> [new results] instead of stacking a new
        // MainActivity + ImageDetailActivity for every repeated similar search.
        com.example.easygallery.SimilarNav.collapseExcept(this)
        // Launch a fresh MainActivity *on top* so Back returns to this image
        // (the last selection a search was started from), then to the gallery.
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SIMILAR_PATH, path)
                if (crop != null) putExtra(
                    MainActivity.EXTRA_SIMILAR_CROP,
                    floatArrayOf(crop.left, crop.top, crop.right, crop.bottom)
                )
            }
        )
    }

    override fun onDestroy() {
        com.example.easygallery.SimilarNav.unregister(this)
        super.onDestroy()
    }

    private fun updateHiddenButton(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val hidden = MetaStore.isHidden(path)
            withContext(Dispatchers.Main) {
                if (hidden) {
                    hiddenButton.setImageResource(R.drawable.ic_eye_off)
                    hiddenButton.setColorFilter(android.graphics.Color.parseColor("#FF4444"))
                    hiddenButton.alpha = 1f
                } else {
                    hiddenButton.setImageResource(R.drawable.ic_eye)
                    hiddenButton.clearColorFilter()
                    hiddenButton.alpha = 0.8f
                }
            }
        }
    }

    private fun updateFavoriteButton(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fav = MetaStore.isFavorite(path)
            withContext(Dispatchers.Main) {
                favoriteButton.setImageResource(
                    if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                )
            }
        }
    }

    private fun loadAndShowFaces(position: Int) {
        val path = paths[position]
        val cached = faceBoxCache[path]
        if (cached != null) {
            holders[position]?.overlay?.setFaces(cached.first, cached.second)
            return
        }
        lifecycleScope.launch {
            val (normal, highlighted) = withContext(Dispatchers.IO) {
                val boxes = FacesStore.getFaceBoxesForPath(path)
                val n = mutableListOf<RectF>()
                val h = mutableListOf<RectF>()
                for (box in boxes) {
                    if (highlightClusterId >= 0 && box.clusterId == highlightClusterId) h.add(box.bbox)
                    else n.add(box.bbox)
                }
                n to h
            }
            faceBoxCache[path] = normal to highlighted
            if (showFaces) holders[position]?.overlay?.setFaces(normal, highlighted)
        }
    }

    inner class DetailPagerAdapter(private val items: List<String>) :
        RecyclerView.Adapter<DetailPagerAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photoView: PhotoView = view.findViewById(R.id.photoView)
            val overlay: FaceOverlayView = view.findViewById(R.id.faceOverlay)
            val cropFrame: CropFrameLayout = view as CropFrameLayout

            init {
                overlay.attach(photoView)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_image_detail, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holders.put(position, holder)
            holder.overlay.clear()
            holder.cropFrame.selectionEnabled = clipEnabled
            holder.cropFrame.selectionCrop = null
            holder.photoView.load(Uri.parse(items[position]))
        }
    }

    companion object {
        private const val EXTRA_PATHS = "paths" // kept for legacy fallback
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_HIGHLIGHT_CLUSTER_ID = "highlight_cluster_id"

        @Volatile var pendingPaths: List<String>? = null

        fun open(context: Context, paths: List<String>, index: Int = 0, highlightClusterId: Long = -1L) {
            pendingPaths = paths
            context.startActivity(
                Intent(context, ImageDetailActivity::class.java).apply {
                    putExtra(EXTRA_INDEX, index)
                    putExtra(EXTRA_HIGHLIGHT_CLUSTER_ID, highlightClusterId)
                }
            )
        }
    }
}
