package com.example.easygallery

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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

class MapFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()
    private var mapView: MapView? = null

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
            map.controller.setZoom(3.0)
            map.controller.setCenter(GeoPoint(20.0, 0.0))
        }

        viewModel.loaded.observe(viewLifecycleOwner) { loaded ->
            if (loaded) loadMarkers()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    private fun loadMarkers() {
        val map = mapView ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                viewModel.entries.mapNotNull { entry ->
                    try {
                        ExifInterface(entry.path).latLong?.let { (lat, lon) ->
                            Triple(lat, lon, entry)
                        }
                    } catch (_: Exception) { null }
                }
            }

            val clusterer = object : RadiusMarkerClusterer(requireContext()) {
                override fun buildClusterMarker(cluster: StaticCluster, mapView: MapView): Marker {
                    return super.buildClusterMarker(cluster, mapView).also { m ->
                        m.setOnMarkerClickListener { _, _ ->
                            val entries = (0 until cluster.size).mapNotNull { i ->
                                cluster.getItem(i).relatedObject as? GalleryViewModel.ImageEntry
                            }
                            ClusterImagesSheet.show(parentFragmentManager, entries)
                            true
                        }
                    }
                }
                override fun zoomOnCluster(mapView: MapView, cluster: StaticCluster) {
                    // handled by click listener instead
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
