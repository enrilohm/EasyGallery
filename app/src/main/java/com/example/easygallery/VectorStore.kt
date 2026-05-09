package com.example.easygallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VectorStore {

    private const val DB_NAME = "embeddings.db"
    private const val DB_VERSION = 2
    private const val TABLE = "embeddings"
    private const val OCR_TABLE = "ocr"

    private var helper: DbHelper? = null

    fun init(context: Context) {
        if (helper == null) {
            helper = DbHelper(context.applicationContext)
        }
    }

    fun hasHash(hash: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $TABLE WHERE hash = ?", arrayOf(hash)).use {
            return it.moveToFirst()
        }
    }

    fun insert(hash: String, path: String, embedding: FloatArray) {
        val db = helper!!.writableDatabase
        val cv = ContentValues().apply {
            put("hash", hash)
            put("path", path)
            put("embedding", embedding.toByteArray())
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updatePath(hash: String, path: String) {
        val db = helper!!.writableDatabase
        val cv = ContentValues().apply { put("path", path) }
        db.update(TABLE, cv, "hash = ?", arrayOf(hash))
    }

    fun count(): Int {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // --- OCR ---

    fun hasOcr(hash: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $OCR_TABLE WHERE hash = ?", arrayOf(hash)).use {
            return it.moveToFirst()
        }
    }

    fun insertOcr(hash: String, path: String, text: String?) {
        val cv = ContentValues().apply {
            put("hash", hash)
            put("path", path)
            put("ocr_text", text)
        }
        helper!!.writableDatabase
            .insertWithOnConflict(OCR_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateOcrPath(hash: String, path: String) {
        val cv = ContentValues().apply { put("path", path) }
        helper!!.writableDatabase.update(OCR_TABLE, cv, "hash = ?", arrayOf(hash))
    }

    fun ocrCount(): Int {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $OCR_TABLE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getOcrText(path: String): String? {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT ocr_text FROM $OCR_TABLE WHERE path = ?", arrayOf(path)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun findByText(query: String, limit: Int): List<String> {
        val db = helper!!.readableDatabase
        val paths = mutableListOf<String>()
        db.rawQuery(
            "SELECT path FROM $OCR_TABLE WHERE ocr_text LIKE ? AND ocr_text IS NOT NULL AND ocr_text != '' LIMIT ?",
            arrayOf("%$query%", limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) paths.add(cursor.getString(0))
        }
        return paths
    }

    // --- Embedding similarity ---

    fun findSimilar(query: FloatArray, topK: Int): List<String> {
        val db = helper!!.readableDatabase
        val scored = mutableListOf<Pair<Float, String>>()
        db.rawQuery("SELECT path, embedding FROM $TABLE", null).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                val emb  = cursor.getBlob(1).toFloatArray()
                scored.add(dotProduct(query, emb) to path)
            }
        }
        return scored.sortedByDescending { it.first }.take(topK).map { it.second }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    fun allEmbeddings(): List<Pair<String, FloatArray>> {
        val db = helper!!.readableDatabase
        val result = mutableListOf<Pair<String, FloatArray>>()
        db.rawQuery("SELECT hash, embedding FROM $TABLE", null).use { cursor ->
            while (cursor.moveToNext()) {
                val hash = cursor.getString(0)
                val embedding = cursor.getBlob(1).toFloatArray()
                result.add(hash to embedding)
            }
        }
        return result
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in this) buf.putFloat(f)
        return buf.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buf.float }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE (hash TEXT PRIMARY KEY, path TEXT NOT NULL, embedding BLOB NOT NULL)")
            db.execSQL("CREATE TABLE $OCR_TABLE (hash TEXT PRIMARY KEY, path TEXT NOT NULL, ocr_text TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Preserve existing embeddings — only add the new OCR table
                db.execSQL("CREATE TABLE IF NOT EXISTS $OCR_TABLE (hash TEXT PRIMARY KEY, path TEXT NOT NULL, ocr_text TEXT)")
            }
        }
    }
}
