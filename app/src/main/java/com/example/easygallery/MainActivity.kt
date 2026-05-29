package com.example.easygallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.easygallery.gallery.GalleryFragment
import com.example.easygallery.gallery.GalleryViewModel
import com.example.easygallery.search.ClipTextEncoder
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
                ClipTextEncoder.load(applicationContext)
                AppDatabase.init(applicationContext)
                ClipStore.preload()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTabs()
        viewModel.refreshHiddenPaths(this)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
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
