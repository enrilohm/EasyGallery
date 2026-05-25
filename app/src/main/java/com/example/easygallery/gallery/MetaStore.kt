package com.example.easygallery.gallery

import com.example.easygallery.db.AppDatabase
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder

object MetaStore {
    private val box: Box<ImageMetaEntity> by lazy { AppDatabase.getStore().boxFor(ImageMetaEntity::class.java) }
    private val metaLock = Any()

    fun isHidden(path: String): Boolean =
        box.query()
            .equal(ImageMetaEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .equal(ImageMetaEntity_.isHidden, true)
            .build().use { it.count() > 0 }

    fun setHidden(path: String, hidden: Boolean) =
        upsertMeta(path) { isHidden = hidden }

    fun getHiddenPaths(): List<String> =
        box.query()
            .equal(ImageMetaEntity_.isHidden, true)
            .build().use { it.find() }
            .map { it.path }

    fun isFavorite(path: String): Boolean =
        box.query()
            .equal(ImageMetaEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .equal(ImageMetaEntity_.isFavorite, true)
            .build().use { it.count() > 0 }

    fun setFavorite(path: String, favorite: Boolean) =
        upsertMeta(path) { isFavorite = favorite }

    fun getFavoritePaths(): List<String> =
        box.query()
            .equal(ImageMetaEntity_.isFavorite, true)
            .build().use { it.find() }
            .map { it.path }

    private fun upsertMeta(path: String, update: ImageMetaEntity.() -> Unit) = synchronized(metaLock) {
        val e = box.query()
            .equal(ImageMetaEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?: ImageMetaEntity(path = path)
        e.update()
        box.put(e)
    }
}
