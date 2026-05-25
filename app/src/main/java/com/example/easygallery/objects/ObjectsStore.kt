package com.example.easygallery.objects

import com.example.easygallery.db.AppDatabase
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder

object ObjectsStore {
    private val box: Box<ObjectsEntity> by lazy { AppDatabase.getStore().boxFor(ObjectsEntity::class.java) }

    fun hasObjectsPath(path: String): Boolean =
        box.query()
            .equal(ObjectsEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun hasObjects(hash: String): Boolean =
        box.query()
            .equal(ObjectsEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insertObjects(hash: String, path: String, labels: List<String>) =
        box.put(ObjectsEntity(hash = hash, path = path, labels = labels.joinToString(",")))

    fun updateObjectsPath(hash: String, path: String) =
        box.query()
            .equal(ObjectsEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.also { it.path = path; box.put(it) }

    fun objectsCount(): Int = box.count().toInt()

    fun getObjectLabels(path: String): List<String> =
        box.query()
            .equal(ObjectsEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.labels?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun getDistinctObjectLabels(): List<Pair<String, Int>> =
        box.all
            .flatMap { it.labels.split(",").map(String::trim) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }

    fun getExamplePath(label: String): String? =
        box.query()
            .contains(ObjectsEntity_.labels, label, StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .firstOrNull { it.hasLabel(label) }
            ?.path

    fun getImagePathsByLabel(label: String): List<String> =
        box.query()
            .contains(ObjectsEntity_.labels, label, StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .filter { it.hasLabel(label) }
            .map { it.path }

    fun findByLabel(query: String, limit: Int, allowedPaths: Set<String>? = null): List<String> =
        box.query()
            .contains(ObjectsEntity_.labels, query, StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .filter { it.hasLabel(query) }
            .map { it.path }
            .let { if (allowedPaths != null) it.filter { p -> p in allowedPaths } else it }
            .take(limit)

    private fun ObjectsEntity.hasLabel(label: String): Boolean =
        labels.split(",").any { it.trim().equals(label, ignoreCase = true) }
}
