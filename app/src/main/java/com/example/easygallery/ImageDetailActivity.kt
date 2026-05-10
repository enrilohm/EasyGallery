package com.example.easygallery

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageDetailActivity : AppCompatActivity() {

    private val faceBoxCache = mutableMapOf<String, List<RectF>>()
    private val holders = SparseArray<DetailPagerAdapter.VH>()
    private var showFaces = false
    private lateinit var paths: List<String>
    private lateinit var adapter: DetailPagerAdapter
    private lateinit var favoriteButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_image_detail)

        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: return finish()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        adapter = DetailPagerAdapter(paths)
        val pager = findViewById<ViewPager2>(R.id.detailPager)
        pager.adapter = adapter
        pager.setCurrentItem(startIndex, false)

        val controller = WindowInsetsControllerCompat(window, pager)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        favoriteButton = findViewById(R.id.favoriteButton)
        updateFavoriteButton(paths[startIndex])

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (showFaces) detectAndShowFaces(position)
                updateFavoriteButton(paths[position])
            }
        })

        favoriteButton.setOnClickListener {
            val path = paths[pager.currentItem]
            lifecycleScope.launch(Dispatchers.IO) {
                val nowFavorite = !VectorStore.isFavorite(path)
                VectorStore.setFavorite(path, nowFavorite)
                withContext(Dispatchers.Main) { updateFavoriteButton(path) }
            }
        }

        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }

        val faceToggle = findViewById<ImageButton>(R.id.faceToggleButton)
        faceToggle.setOnClickListener {
            showFaces = !showFaces
            faceToggle.alpha = if (showFaces) 1f else 0.4f
            if (showFaces) {
                detectAndShowFaces(pager.currentItem)
            } else {
                holders[pager.currentItem]?.overlay?.clear()
            }
        }
    }

    private fun updateFavoriteButton(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fav = VectorStore.isFavorite(path)
            withContext(Dispatchers.Main) {
                favoriteButton.setImageResource(
                    if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                )
            }
        }
    }

    private fun detectAndShowFaces(position: Int) {
        val path = paths[position]
        val cached = faceBoxCache[path]
        if (cached != null) {
            holders[position]?.overlay?.setFaces(cached)
            return
        }
        lifecycleScope.launch {
            val boxes = withContext(Dispatchers.IO) {
                try { FaceDetector.detectBoxes(path) } catch (e: Exception) { emptyList() }
            }
            faceBoxCache[path] = boxes
            if (showFaces) holders[position]?.overlay?.setFaces(boxes)
        }
    }

    inner class DetailPagerAdapter(private val items: List<String>) :
        RecyclerView.Adapter<DetailPagerAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photoView: PhotoView = view.findViewById(R.id.photoView)
            val overlay: FaceOverlayView = view.findViewById(R.id.faceOverlay)

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
            holder.photoView.load(Uri.parse(items[position]))
        }
    }

    companion object {
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_INDEX = "index"

        fun open(context: Context, paths: List<String>, index: Int = 0) {
            context.startActivity(
                Intent(context, ImageDetailActivity::class.java).apply {
                    putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                    putExtra(EXTRA_INDEX, index)
                }
            )
        }
    }
}
