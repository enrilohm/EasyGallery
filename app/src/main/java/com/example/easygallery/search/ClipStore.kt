package com.example.easygallery.search

import com.example.easygallery.db.AppDatabase
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder

object ClipStore {
    private val box: Box<ClipEntity> by lazy { AppDatabase.getStore().boxFor(ClipEntity::class.java) }
    @Volatile private var embeddingCache: List<Pair<String, FloatArray>>? = null

    fun hasEmbeddingPath(path: String): Boolean =
        box.query()
            .equal(ClipEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insert(path: String, embedding: FloatArray) {
        box.put(ClipEntity(path = path, embedding = embedding))
        embeddingCache = null
    }

    fun count(): Int = box.count().toInt()

    fun embeddingForPath(path: String): FloatArray? {
        embeddingCache?.firstOrNull { it.first == path }?.let { return it.second }
        return box.query()
            .equal(ClipEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst()?.embedding }
    }

    fun findSimilar(query: FloatArray, topK: Int, allowedPaths: Set<String>? = null): List<String> {
        val cache = embeddingCache ?: box.all
            .mapNotNull { e -> e.embedding?.let { e.path to it } }
            .also { embeddingCache = it }
        return (if (allowedPaths != null) cache.filter { it.first in allowedPaths } else cache)
            .map { (path, emb) -> path to dot(query, emb) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
