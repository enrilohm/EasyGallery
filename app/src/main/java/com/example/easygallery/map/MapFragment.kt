package com.example.easygallery.map

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer
import org.osmdroid.bonuspack.clustering.StaticCluster
import org.osmdroid.views.overlay.Marker
import com.example.easygallery.gallery.GalleryViewModel
import com.example.easygallery.gallery.ImageInfoSheet
import com.example.easygallery.R

class MapFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private var mapView: MapView? = null
    private var markersJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        val view = inflater.inflate(R.layout.fragment_map, container, false)
        mapView = view.findViewById<MapView>(R.id.mapView).also { map ->
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.isTilesScaledToDpi = true
            map.setMultiTouchControls(true)
            map.isVerticalMapRepetitionEnabled = false
            map.controller.setZoom(3.0)
            map.controller.setCenter(GeoPoint(20.0, 0.0))
        }

        viewModel.filteredEntries.observe(viewLifecycleOwner) { entries -> loadMarkers(entries) }

        return view
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    private fun loadMarkers(entries: List<GalleryViewModel.ImageEntry>) {
        markersJob?.cancel()
        val map = mapView ?: return
        markersJob = viewLifecycleOwner.lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                val entryByPath = entries.associateBy { it.path }

                // Paths already checked (includes no-GPS sentinels with null lat/lon)
                val indexedPaths = GpsStore.getIndexedGpsPaths()

                // For uncached paths read EXIF, store result (null = no GPS sentinel)
                entries.filter { it.path !in indexedPaths }.forEach { entry ->
                    try {
                        val ll = ExifInterface(entry.path).latLong
                        GpsStore.insertGps(entry.path, ll?.get(0), ll?.get(1))
                    } catch (_: Exception) {
                        GpsStore.insertGps(entry.path, null, null)
                    }
                }

                // All GPS points from DB, filtered to current entries
                GpsStore.getGpsPoints()
                    .mapNotNull { (path, lat, lon) -> entryByPath[path]?.let { Triple(lat, lon, it) } }
            }

            val clusterer = object : RadiusMarkerClusterer(requireContext()) {
                override fun buildClusterMarker(cluster: StaticCluster, mapView: MapView): Marker {
                    val styled = super.buildClusterMarker(cluster, mapView)
                    return object : Marker(mapView) {
                        init {
                            position = styled.position
                            icon = styled.icon
                            setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ ->
                                val entries = (0 until cluster.size).mapNotNull { i ->
                                    cluster.getItem(i).relatedObject as? GalleryViewModel.ImageEntry
                                }
                                ClusterImagesSheet.show(parentFragmentManager, entries)
                                true
                            }
                        }
                        override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean {
                            if (!hitTest(event, mapView)) return false
                            val lats = (0 until cluster.size).map { cluster.getItem(it).position.latitude }
                            val lons = (0 until cluster.size).map { cluster.getItem(it).position.longitude }
                            val box = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                            mapView.zoomToBoundingBox(box.increaseByScale(1.5f), true)
                            return true
                        }
                    }
                }
                override fun zoomOnCluster(mapView: MapView, cluster: StaticCluster) {
                    // zoom handled by long press instead
                }
            }
            points.forEach { (lat, lon, entry) ->
                Marker(map).apply {
                    position = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    relatedObject = entry
                    setOnMarkerClickListener { _, _ ->
                        ImageInfoSheet.show(parentFragmentManager, entry.uri, entry.path)
                        true
                    }
                    clusterer.add(this)
                }
            }
            map.overlays.clear()
            map.overlays.add(clusterer)

            if (points.isNotEmpty()) {
                val lats = points.map { it.first }
                val lons = points.map { it.second }
                val box = BoundingBox(
                    lats.max(), lons.max(), lats.min(), lons.min()
                )
                map.post {
                    map.zoomToBoundingBox(box.increaseByScale(1.3f), true)
                }
            }

            map.invalidate()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView?.onDetach()
        mapView = null
    }
}
