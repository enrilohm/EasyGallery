package com.example.easygallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.example.easygallery.gallery.GalleryFragment
import com.example.easygallery.gallery.TimelineFragment
import com.example.easygallery.gallery.GalleryViewModel
import com.example.easygallery.search.ClipTextEncoder
import com.example.easygallery.search.ClipEncoder
import com.example.easygallery.search.ClipStore
import com.example.easygallery.db.AppDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: GalleryViewModel
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private var tabMediator: TabLayoutMediator? = null
    private var currentTabs: List<TabType> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SimilarNav.register(this)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        viewModel = ViewModelProvider(this)[GalleryViewModel::class.java]

        viewPager = findViewById(R.id.viewPager)
        viewPager.isUserInputEnabled = false
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (currentTabs.getOrNull(position) == TabType.GALLERY) {
                    supportFragmentManager.fragments
                        .filterIsInstance<GalleryFragment>()
                        .firstOrNull()
                        ?.updateToolbarTitle()
                } else {
                    supportActionBar?.title = "Easy Gallery"
                    supportActionBar?.setDisplayHomeAsUpEnabled(false)
                }
            }
        })

        tabLayout = findViewById(R.id.tabLayout)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewPager.setCurrentItem(tab.position, false)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        applyTabs()
        requestPermissionAndLoad()
        handleSimilarIntent(intent)

        val clipEnabled = getSharedPreferences("gallery_prefs", MODE_PRIVATE)
            .getBoolean("clip_search_enabled", false)
        if (clipEnabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                AppDatabase.init(applicationContext)
                ClipStore.preload()
                // Pre-warm both encoders so the first search/crop isn't slow.
                ClipTextEncoder.load(applicationContext)
                ClipEncoder.load(applicationContext)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTabs()
        viewModel.refreshHiddenPaths(this)
    }

    override fun onDestroy() {
        SimilarNav.unregister(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSimilarIntent(intent)
    }

    private fun handleSimilarIntent(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_SIMILAR_PATH) ?: return
        intent.removeExtra(EXTRA_SIMILAR_PATH)
        val cropArr = intent.getFloatArrayExtra(EXTRA_SIMILAR_CROP)
        intent.removeExtra(EXTRA_SIMILAR_CROP)
        val crop = cropArr?.takeIf { it.size == 4 }
            ?.let { android.graphics.RectF(it[0], it[1], it[2], it[3]) }
        val searchIndex = currentTabs.indexOf(TabType.SEARCH)
        if (searchIndex >= 0) {
            viewPager.setCurrentItem(searchIndex, false)
            tabLayout.getTabAt(searchIndex)?.select()
        }
        viewModel.requestSimilar(path, crop)
    }

    private fun buildTabs(): List<TabType> {
        val prefs = getSharedPreferences("gallery_prefs", MODE_PRIVATE)
        val clip  = prefs.getBoolean("clip_search_enabled", false)
        val ocr   = prefs.getBoolean("ocr_enabled", false)
        val obj   = prefs.getBoolean("object_detection_enabled", false)
        val face  = prefs.getBoolean("face_detection_enabled", false)
        return buildList {
            add(TabType.GALLERY)
            add(TabType.TIMELINE)
            if (clip || ocr || obj) add(TabType.SEARCH)
            if (obj) add(TabType.OBJECTS)
            add(TabType.MAP)
            if (face) add(TabType.PEOPLE)
            add(TabType.FILTER)
        }
    }

    private fun applyTabs() {
        val newTabs = buildTabs()
        if (newTabs == currentTabs) return
        currentTabs = newTabs

        tabMediator?.detach()
        val adapter = GalleryPagerAdapter(this, newTabs)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = maxOf(1, newTabs.size - 1)
        tabMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = newTabs[position].label
        }.also { it.attach() }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val inSelection = activeSelectionFragment() != null
        menu.findItem(R.id.action_settings).isVisible = !inSelection
        menu.findItem(R.id.action_share_zip).isVisible = inSelection
        menu.findItem(R.id.action_share).isVisible = inSelection
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_share_zip -> {
                shareSelectionAsZip()
                return true
            }
            R.id.action_share -> {
                shareSelectionIndividually()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun shareSelectionIndividually() {
        val entries = resolveEntries().ifEmpty { return }
        if (entries.isEmpty()) return
        val uris = entries.mapNotNull { (path, _) ->
            val file = java.io.File(path)
            if (file.exists()) FileProvider.getUriForFile(this, "$packageName.fileprovider", file) else null
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun galleryFragment() = supportFragmentManager.fragments
        .filterIsInstance<GalleryFragment>().firstOrNull()

    private fun timelineFragment() = supportFragmentManager.fragments
        .filterIsInstance<TimelineFragment>().firstOrNull()

    private fun activeSelectionFragment(): Any? {
        val gf = galleryFragment()
        if (gf?.isInSelectionMode() == true) return gf
        val tf = timelineFragment()
        if (tf?.isInSelectionMode() == true) return tf
        return null
    }

    private fun resolveEntries(): List<Pair<String, String>> = when (val f = activeSelectionFragment()) {
        is GalleryFragment -> f.resolveSelectedEntries()
        is TimelineFragment -> f.resolveSelectedEntries()
        else -> emptyList()
    }

    private fun shareSelectionAsZip() {
        val entries = resolveEntries().ifEmpty { return }
        if (entries.isEmpty()) return
        lifecycleScope.launch {
            val zipFile = withContext(Dispatchers.IO) {
                val out = File(cacheDir, "selection.zip")
                ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    val seenEntries = mutableSetOf<String>()
                    for ((path, entryName) in entries) {
                        val file = File(path)
                        if (!file.exists()) continue
                        // Deduplicate while preserving directory prefix
                        val dir = entryName.substringBeforeLast("/", "")
                        val base = entryName.substringAfterLast("/")
                        val baseName = base.substringBeforeLast(".")
                        val ext = base.substringAfterLast(".", "")
                        var finalEntry = entryName
                        var n = 1
                        while (!seenEntries.add(finalEntry)) {
                            finalEntry = if (dir.isEmpty()) "$baseName($n).$ext"
                                         else "$dir/$baseName($n).$ext"
                            n++
                        }
                        zip.putNextEntry(ZipEntry(finalEntry))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                out
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", zipFile)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null
            ))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            viewModel.load(contentResolver)
        }
    }

    private fun requestPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.load(contentResolver)
        } else {
            requestPermissions(arrayOf(permission), PERMISSION_REQUEST)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 1
        const val EXTRA_SIMILAR_PATH = "similar_path"
        const val EXTRA_SIMILAR_CROP = "similar_crop"
    }
}
