package com.example.easygallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VectorStore {

    private const val DB_NAME = "embeddings.db"
    private const val DB_VERSION = 6
    private const val TABLE = "embeddings"
    private const val OCR_TABLE = "ocr"
    private const val OBJECTS_TABLE = "objects"
    private const val FACES_TABLE = "faces"
    private const val FAVORITES_TABLE = "favorites"

    private var helper: DbHelper? = null

    fun init(context: Context) {
        if (helper == null) {
            helper = DbHelper(context.applicationContext)
        }
    }

    fun hasEmbeddingPath(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
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

    fun hasOcrPath(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $OCR_TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
        }
    }

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

    fun findByText(query: String, limit: Int, allowedPaths: Set<String>? = null): List<String> {
        val db = helper!!.readableDatabase
        val paths = mutableListOf<String>()
        db.rawQuery(
            "SELECT path FROM $OCR_TABLE WHERE ocr_text LIKE ? AND ocr_text IS NOT NULL AND ocr_text != ''",
            arrayOf("%$query%")
        ).use { cursor ->
            while (cursor.moveToNext() && paths.size < limit) {
                val path = cursor.getString(0)
                if (allowedPaths == null || path in allowedPaths) paths.add(path)
            }
        }
        return paths
    }

    // --- Objects ---

    fun hasObjectsPath(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $OBJECTS_TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
        }
    }

    fun hasObjects(hash: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $OBJECTS_TABLE WHERE hash = ?", arrayOf(hash)).use {
            return it.moveToFirst()
        }
    }

    fun insertObjects(hash: String, path: String, labels: List<String>) {
        val cv = ContentValues().apply {
            put("hash", hash)
            put("path", path)
            put("labels", labels.joinToString(","))
        }
        helper!!.writableDatabase
            .insertWithOnConflict(OBJECTS_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateObjectsPath(hash: String, path: String) {
        val cv = ContentValues().apply { put("path", path) }
        helper!!.writableDatabase.update(OBJECTS_TABLE, cv, "hash = ?", arrayOf(hash))
    }

    fun objectsCount(): Int {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $OBJECTS_TABLE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getObjectLabels(path: String): List<String> {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT labels FROM $OBJECTS_TABLE WHERE path = ?", arrayOf(path)).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            val labels = cursor.getString(0) ?: return emptyList()
            return labels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun getDistinctObjectLabels(): List<Pair<String, Int>> {
        val db = helper!!.readableDatabase
        val counts = mutableMapOf<String, Int>()
        db.rawQuery("SELECT labels FROM $OBJECTS_TABLE WHERE labels IS NOT NULL AND labels != ''", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.getString(0) ?: continue
                row.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { label ->
                    counts[label] = (counts[label] ?: 0) + 1
                }
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun getExamplePath(label: String): String? {
        val db = helper!!.readableDatabase
        db.rawQuery(
            "SELECT path FROM $OBJECTS_TABLE WHERE ',' || labels || ',' LIKE '%,' || ? || ',%' AND labels != '' LIMIT 1",
            arrayOf(label)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun getImagePathsByLabel(label: String): List<String> {
        val db = helper!!.readableDatabase
        val paths = mutableListOf<String>()
        db.rawQuery(
            "SELECT path FROM $OBJECTS_TABLE WHERE ',' || labels || ',' LIKE '%,' || ? || ',%' AND labels != ''",
            arrayOf(label)
        ).use { cursor ->
            while (cursor.moveToNext()) paths.add(cursor.getString(0))
        }
        return paths
    }

    fun findByLabel(query: String, limit: Int, allowedPaths: Set<String>? = null): List<String> {
        val db = helper!!.readableDatabase
        val paths = mutableListOf<String>()
        db.rawQuery(
            "SELECT path FROM $OBJECTS_TABLE WHERE ',' || labels || ',' LIKE '%,' || ? || ',%' AND labels != ''",
            arrayOf(query)
        ).use { cursor ->
            while (cursor.moveToNext() && paths.size < limit) {
                val path = cursor.getString(0)
                if (allowedPaths == null || path in allowedPaths) paths.add(path)
            }
        }
        return paths
    }

    // --- Faces ---

    fun hasFaceEntry(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $FACES_TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
        }
    }

    fun insertFace(path: String, faceIndex: Int, embedding: FloatArray) {
        val cv = ContentValues().apply {
            put("path", path)
            put("face_index", faceIndex)
            put("embedding", embedding.toByteArray())
        }
        helper!!.writableDatabase
            .insertWithOnConflict(FACES_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun countFaceEntries(): Int {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT COUNT(DISTINCT path) FROM $FACES_TABLE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun findSimilarFaces(query: FloatArray, topK: Int): List<String> {
        val db = helper!!.readableDatabase
        val scored = mutableListOf<Pair<Float, String>>()
        db.rawQuery(
            "SELECT path, embedding FROM $FACES_TABLE WHERE face_index >= 0 AND length(embedding) > 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                val emb  = cursor.getBlob(1).toFloatArray()
                scored.add(dotProduct(query, emb) to path)
            }
        }
        return scored.sortedByDescending { it.first }.take(topK).map { it.second }.distinct()
    }

    /** Returns all face embeddings with path and row id, skipping sentinel no-face entries. */
    fun getAllFaceEmbeddings(): List<Triple<Long, String, FloatArray>> {
        val db = helper!!.readableDatabase
        val result = mutableListOf<Triple<Long, String, FloatArray>>()
        db.rawQuery(
            "SELECT id, path, embedding FROM $FACES_TABLE WHERE face_index >= 0 AND length(embedding) > 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id   = cursor.getLong(0)
                val path = cursor.getString(1)
                val emb  = cursor.getBlob(2).toFloatArray()
                result.add(Triple(id, path, emb))
            }
        }
        return result
    }

    // --- Favorites ---

    fun isFavorite(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $FAVORITES_TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
        }
    }

    fun setFavorite(path: String, favorite: Boolean) {
        val db = helper!!.writableDatabase
        if (favorite) {
            val cv = ContentValues().apply { put("path", path) }
            db.insertWithOnConflict(FAVORITES_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            db.delete(FAVORITES_TABLE, "path = ?", arrayOf(path))
        }
    }

    fun getFavoritePaths(): List<String> {
        val db = helper!!.readableDatabase
        val paths = mutableListOf<String>()
        db.rawQuery("SELECT path FROM $FAVORITES_TABLE", null).use { cursor ->
            while (cursor.moveToNext()) paths.add(cursor.getString(0))
        }
        return paths
    }

    // --- Embedding similarity ---

    fun findSimilar(query: FloatArray, topK: Int, allowedPaths: Set<String>? = null): List<String> {
        val db = helper!!.readableDatabase
        val scored = mutableListOf<Pair<Float, String>>()
        db.rawQuery("SELECT path, embedding FROM $TABLE", null).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                if (allowedPaths != null && path !in allowedPaths) continue
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
            db.execSQL("CREATE TABLE $OBJECTS_TABLE (hash TEXT PRIMARY KEY, path TEXT NOT NULL, labels TEXT)")
            db.execSQL("CREATE TABLE $FACES_TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, face_index INTEGER NOT NULL, embedding BLOB NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX idx_faces_path_face ON $FACES_TABLE (path, face_index)")
            db.execSQL("CREATE INDEX idx_embeddings_path ON $TABLE (path)")
            db.execSQL("CREATE INDEX idx_ocr_path ON $OCR_TABLE (path)")
            db.execSQL("CREATE INDEX idx_objects_path ON $OBJECTS_TABLE (path)")
            db.execSQL("CREATE TABLE $FAVORITES_TABLE (path TEXT PRIMARY KEY)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $OCR_TABLE (hash TEXT PRIMARY KEY, path TEXT NOT NULL, ocr_text TEXT)")
            }
            if (oldVersion < 3) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $OBJECTS_TABLE (hash TEXT PRIMARY KEY, path TEXT NOT NULL, labels TEXT)")
            }
            if (oldVersion < 4) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $FACES_TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, face_index INTEGER NOT NULL, embedding BLOB NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_faces_path_face ON $FACES_TABLE (path, face_index)")
            }
            if (oldVersion < 5) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_embeddings_path ON $TABLE (path)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ocr_path ON $OCR_TABLE (path)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_objects_path ON $OBJECTS_TABLE (path)")
            }
            if (oldVersion < 6) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $FAVORITES_TABLE (path TEXT PRIMARY KEY)")
            }
        }
    }
}
