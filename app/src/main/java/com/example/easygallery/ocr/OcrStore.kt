package com.example.easygallery.ocr

import com.example.easygallery.db.AppDatabase
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder

object OcrStore {
    private val box: Box<OcrEntity> by lazy { AppDatabase.getStore().boxFor(OcrEntity::class.java) }

    fun hasOcrPath(path: String): Boolean =
        box.query()
            .equal(OcrEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun hasOcr(hash: String): Boolean =
        box.query()
            .equal(OcrEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insertOcr(hash: String, path: String, text: String?) =
        box.put(OcrEntity(hash = hash, path = path, text = text ?: ""))

    fun updateOcrPath(hash: String, path: String) =
        box.query()
            .equal(OcrEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.also { it.path = path; box.put(it) }

    fun ocrCount(): Int = box.count().toInt()

    fun getOcrText(path: String): String? =
        box.query()
            .equal(OcrEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.text?.ifEmpty { null }

    fun findByText(query: String, limit: Int, allowedPaths: Set<String>? = null): List<String> =
        box.query()
            .contains(OcrEntity_.text, query, StringOrder.CASE_INSENSITIVE)
            .notEqual(OcrEntity_.text, "", StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .map { it.path }
            .let { if (allowedPaths != null) it.filter { p -> p in allowedPaths } else it }
            .take(limit)
}
