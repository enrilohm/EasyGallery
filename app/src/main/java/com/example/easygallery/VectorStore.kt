package com.example.easygallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VectorStore {

    private const val DB_NAME = "embeddings.db"
    private const val DB_VERSION = 1
    private const val TABLE = "embeddings"

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
            db.execSQL("""
                CREATE TABLE $TABLE (
                    hash TEXT PRIMARY KEY,
                    path TEXT NOT NULL,
                    embedding BLOB NOT NULL
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
