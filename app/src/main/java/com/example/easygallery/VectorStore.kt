package com.example.easygallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VectorStore {

    data class FaceEntry(
        val id: Long,
        val path: String,
        val faceIndex: Int,
        val embedding: FloatArray,
        val bbox: RectF?,
        val yaw: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val faceSize: Float = 0.1f,
        val blurScore: Float = 100f,
        val hasLandmarks: Boolean = true,
    ) {
        val isGoodQuality: Boolean get() =
            kotlin.math.abs(yaw) < 30f &&
            kotlin.math.abs(pitch) < 25f &&
            faceSize > 0.04f &&
            blurScore > 30f &&
            hasLandmarks
    }
    data class StoredCluster(val clusterId: Long, val name: String?, val representativePath: String, val representativeBBox: RectF?, val paths: List<String>)

    private const val DB_NAME = "embeddings.db"
    private const val DB_VERSION = 11
    private const val TABLE = "embeddings"
    private const val OCR_TABLE = "ocr"
    private const val OBJECTS_TABLE = "objects"
    private const val FACES_TABLE = "faces"
    private const val FAVORITES_TABLE = "favorites"
    private const val GPS_TABLE = "gps"
    private const val HIDDEN_TABLE = "hidden"
    private const val CLUSTERS_TABLE = "face_clusters"
    private const val CLUSTER_MEMBERS_TABLE = "face_cluster_members"

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

    fun insertFace(
        path: String,
        faceIndex: Int,
        embedding: FloatArray,
        bbox: RectF? = null,
        yaw: Float? = null,
        pitch: Float? = null,
        roll: Float? = null,
        faceSize: Float? = null,
        blurScore: Float? = null,
        hasLandmarks: Boolean? = null,
    ) {
        val cv = ContentValues().apply {
            put("path", path)
            put("face_index", faceIndex)
            put("embedding", embedding.toByteArray())
            if (bbox != null) {
                put("bbox_left", bbox.left)
                put("bbox_top", bbox.top)
                put("bbox_right", bbox.right)
                put("bbox_bottom", bbox.bottom)
            }
            if (yaw != null) put("yaw", yaw)
            if (pitch != null) put("pitch", pitch)
            if (roll != null) put("roll", roll)
            if (faceSize != null) put("face_size", faceSize)
            if (blurScore != null) put("blur_score", blurScore)
            if (hasLandmarks != null) put("has_landmarks", if (hasLandmarks) 1 else 0)
        }
        helper!!.writableDatabase
            .insertWithOnConflict(FACES_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun clearFaces() {
        val db = helper!!.writableDatabase
        db.beginTransaction()
        try {
            db.delete(CLUSTER_MEMBERS_TABLE, null, null)
            db.delete(CLUSTERS_TABLE, null, null)
            db.delete(FACES_TABLE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    /** Returns all face embeddings with metadata, skipping sentinel no-face entries. */
    fun getAllFaceEmbeddings(): List<FaceEntry> {
        val db = helper!!.readableDatabase
        val result = mutableListOf<FaceEntry>()
        db.rawQuery(
            """SELECT id, path, face_index, embedding,
                      bbox_left, bbox_top, bbox_right, bbox_bottom,
                      yaw, pitch, roll, face_size, blur_score, has_landmarks
               FROM $FACES_TABLE WHERE face_index >= 0 AND length(embedding) > 0""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id        = cursor.getLong(0)
                val path      = cursor.getString(1)
                val faceIndex = cursor.getInt(2)
                val emb       = cursor.getBlob(3).toFloatArray()
                val bbox = if (cursor.isNull(4)) null else RectF(
                    cursor.getFloat(4), cursor.getFloat(5),
                    cursor.getFloat(6), cursor.getFloat(7)
                )
                result.add(FaceEntry(
                    id           = id,
                    path         = path,
                    faceIndex    = faceIndex,
                    embedding    = emb,
                    bbox         = bbox,
                    yaw          = if (cursor.isNull(8))  0f    else cursor.getFloat(8),
                    pitch        = if (cursor.isNull(9))  0f    else cursor.getFloat(9),
                    roll         = if (cursor.isNull(10)) 0f    else cursor.getFloat(10),
                    faceSize     = if (cursor.isNull(11)) 0.1f  else cursor.getFloat(11),
                    blurScore    = if (cursor.isNull(12)) 100f  else cursor.getFloat(12),
                    hasLandmarks = if (cursor.isNull(13)) true  else cursor.getInt(13) != 0,
                ))
            }
        }
        return result
    }

    /** Returns only faces that pass all quality checks, for use in clustering. */
    fun getAllGoodFaceEmbeddings(): List<FaceEntry> = getAllFaceEmbeddings().filter { it.isGoodQuality }

    // --- Face boxes ---

    data class FaceBox(val bbox: RectF, val clusterId: Long?)

    fun getFaceBoxesForPath(path: String): List<FaceBox> {
        val db = helper!!.readableDatabase
        val result = mutableListOf<FaceBox>()
        db.rawQuery("""
            SELECT f.bbox_left, f.bbox_top, f.bbox_right, f.bbox_bottom, m.cluster_id
            FROM $FACES_TABLE f
            LEFT JOIN $CLUSTER_MEMBERS_TABLE m ON f.id = m.face_id
            WHERE f.path = ? AND f.face_index >= 0 AND f.bbox_left IS NOT NULL
        """.trimIndent(), arrayOf(path)).use { cursor ->
            while (cursor.moveToNext()) {
                val bbox = RectF(
                    cursor.getFloat(0), cursor.getFloat(1),
                    cursor.getFloat(2), cursor.getFloat(3)
                )
                val clusterId = if (cursor.isNull(4)) null else cursor.getLong(4)
                result.add(FaceBox(bbox, clusterId))
            }
        }
        return result
    }

    // --- Clusters ---

    fun hasClusters(): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $CLUSTERS_TABLE LIMIT 1", null).use { return it.moveToFirst() }
    }

    fun storeClusters(clusters: List<FaceClusterer.FaceCluster>) {
        val db = helper!!.writableDatabase
        db.beginTransaction()
        try {
            db.delete(CLUSTER_MEMBERS_TABLE, null, null)
            db.delete(CLUSTERS_TABLE, null, null)
            for (cluster in clusters) {
                val cv = ContentValues().apply {
                    put("representative_face_id", cluster.representativeFaceId)
                }
                val clusterId = db.insert(CLUSTERS_TABLE, null, cv)
                for (faceId in cluster.memberFaceIds) {
                    val mcv = ContentValues().apply {
                        put("face_id", faceId)
                        put("cluster_id", clusterId)
                    }
                    db.insertWithOnConflict(CLUSTER_MEMBERS_TABLE, null, mcv, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getStoredClusters(): List<StoredCluster> {
        val db = helper!!.readableDatabase
        val clusterPaths = mutableMapOf<Long, MutableList<String>>()
        val clusterMeta  = mutableMapOf<Long, Triple<String?, String, RectF?>>() // clusterId → (name, repPath, repBBox)

        db.rawQuery("""
            SELECT c.cluster_id, c.name,
                   rf.path,
                   rf.bbox_left, rf.bbox_top, rf.bbox_right, rf.bbox_bottom,
                   mf.path AS member_path
            FROM $CLUSTERS_TABLE c
            JOIN $FACES_TABLE rf ON c.representative_face_id = rf.id
            JOIN $CLUSTER_MEMBERS_TABLE m ON c.cluster_id = m.cluster_id
            JOIN $FACES_TABLE mf ON m.face_id = mf.id
        """.trimIndent(), null).use { cursor ->
            while (cursor.moveToNext()) {
                val clusterId  = cursor.getLong(0)
                val name       = if (cursor.isNull(1)) null else cursor.getString(1)
                val repPath    = cursor.getString(2)
                val repBBox    = if (cursor.isNull(3)) null else RectF(
                    cursor.getFloat(3), cursor.getFloat(4),
                    cursor.getFloat(5), cursor.getFloat(6)
                )
                val memberPath = cursor.getString(7)
                clusterMeta.getOrPut(clusterId) { Triple(name, repPath, repBBox) }
                clusterPaths.getOrPut(clusterId) { mutableListOf() }.add(memberPath)
            }
        }

        return clusterMeta.entries
            .map { (id, meta) ->
                StoredCluster(id, meta.first, meta.second, meta.third,
                    clusterPaths[id]?.distinct() ?: emptyList())
            }
            .sortedByDescending { it.paths.size }
    }

    // --- GPS ---

    fun hasGpsEntry(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $GPS_TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
        }
    }

    fun insertGps(path: String, lat: Double?, lon: Double?) {
        val cv = ContentValues().apply {
            put("path", path)
            if (lat != null) put("lat", lat) else putNull("lat")
            if (lon != null) put("lon", lon) else putNull("lon")
        }
        helper!!.writableDatabase
            .insertWithOnConflict(GPS_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getGpsPoints(): List<Triple<String, Double, Double>> {
        val db = helper!!.readableDatabase
        val result = mutableListOf<Triple<String, Double, Double>>()
        db.rawQuery("SELECT path, lat, lon FROM $GPS_TABLE WHERE lat IS NOT NULL AND lon IS NOT NULL", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(Triple(cursor.getString(0), cursor.getDouble(1), cursor.getDouble(2)))
            }
        }
        return result
    }

    fun getIndexedGpsPaths(): Set<String> {
        val db = helper!!.readableDatabase
        val result = mutableSetOf<String>()
        db.rawQuery("SELECT path FROM $GPS_TABLE", null).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result
    }

    // --- Hidden ---

    fun isHidden(path: String): Boolean {
        val db = helper!!.readableDatabase
        db.rawQuery("SELECT 1 FROM $HIDDEN_TABLE WHERE path = ?", arrayOf(path)).use {
            return it.moveToFirst()
        }
    }

    fun setHidden(path: String, hidden: Boolean) {
        val db = helper!!.writableDatabase
        if (hidden) {
            val cv = ContentValues().apply { put("path", path) }
            db.insertWithOnConflict(HIDDEN_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            db.delete(HIDDEN_TABLE, "path = ?", arrayOf(path))
        }
    }

    fun getHiddenPaths(): List<String> {
        val db = helper!!.readableDatabase
        val paths = mutableListOf<String>()
        db.rawQuery("SELECT path FROM $HIDDEN_TABLE", null).use { cursor ->
            while (cursor.moveToNext()) paths.add(cursor.getString(0))
        }
        return paths
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
            db.execSQL("CREATE TABLE $FACES_TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, face_index INTEGER NOT NULL, embedding BLOB NOT NULL, bbox_left REAL, bbox_top REAL, bbox_right REAL, bbox_bottom REAL, yaw REAL, pitch REAL, roll REAL, face_size REAL, blur_score REAL, has_landmarks INTEGER)")
            db.execSQL("CREATE UNIQUE INDEX idx_faces_path_face ON $FACES_TABLE (path, face_index)")
            db.execSQL("CREATE INDEX idx_embeddings_path ON $TABLE (path)")
            db.execSQL("CREATE INDEX idx_ocr_path ON $OCR_TABLE (path)")
            db.execSQL("CREATE INDEX idx_objects_path ON $OBJECTS_TABLE (path)")
            db.execSQL("CREATE TABLE $FAVORITES_TABLE (path TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE $GPS_TABLE (path TEXT PRIMARY KEY, lat REAL, lon REAL)")
            db.execSQL("CREATE TABLE $HIDDEN_TABLE (path TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE $CLUSTERS_TABLE (cluster_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, representative_face_id INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $CLUSTER_MEMBERS_TABLE (face_id INTEGER PRIMARY KEY, cluster_id INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX idx_cluster_members_cluster ON $CLUSTER_MEMBERS_TABLE (cluster_id)")
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
            if (oldVersion < 7) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $GPS_TABLE (path TEXT PRIMARY KEY, lat REAL, lon REAL)")
            }
            if (oldVersion < 8) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $HIDDEN_TABLE (path TEXT PRIMARY KEY)")
            }
            if (oldVersion < 9) {
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN bbox_left REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN bbox_top REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN bbox_right REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN bbox_bottom REAL")
            }
            if (oldVersion < 10) {
                db.execSQL("CREATE TABLE IF NOT EXISTS $CLUSTERS_TABLE (cluster_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, representative_face_id INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS $CLUSTER_MEMBERS_TABLE (face_id INTEGER PRIMARY KEY, cluster_id INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cluster_members_cluster ON $CLUSTER_MEMBERS_TABLE (cluster_id)")
            }
            if (oldVersion < 11) {
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN yaw REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN pitch REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN roll REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN face_size REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN blur_score REAL")
                db.execSQL("ALTER TABLE $FACES_TABLE ADD COLUMN has_landmarks INTEGER")
            }
        }
    }
}
