package com.example.easygallery.map

import com.example.easygallery.db.AppDatabase
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder

object GpsStore {
    private val box: Box<GpsEntity> by lazy { AppDatabase.getStore().boxFor(GpsEntity::class.java) }

    fun hasGpsEntry(path: String): Boolean =
        box.query()
            .equal(GpsEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insertGps(path: String, lat: Double?, lon: Double?) =
        box.put(GpsEntity(
            path   = path,
            lat    = lat ?: 0.0,
            lon    = lon ?: 0.0,
            hasGps = lat != null && lon != null,
        ))

    fun getGpsPoints(): List<Triple<String, Double, Double>> =
        box.query()
            .equal(GpsEntity_.hasGps, true)
            .build().use { it.find() }
            .map { Triple(it.path, it.lat, it.lon) }

    fun getIndexedGpsPaths(): Set<String> =
        box.all.map { it.path }.toSet()
}
