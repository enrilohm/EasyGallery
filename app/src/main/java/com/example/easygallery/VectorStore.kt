package com.example.easygallery

import android.content.Context
import android.graphics.RectF
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder

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
        val detectionScore: Float = 1f,
    ) {
        val isGoodQuality: Boolean get() =
            kotlin.math.abs(yaw) < 30f &&
            kotlin.math.abs(pitch) < 25f &&
            faceSize > 0.04f &&
            blurScore > 30f &&
            hasLandmarks
    }

    data class StoredCluster(
        val clusterId: Long,
        val name: String?,
        val representativePath: String,
        val representativeBBox: RectF?,
        val paths: List<String>,
    )

    data class FaceBox(val bbox: RectF, val clusterId: Long?)

    private lateinit var store: BoxStore
    private lateinit var clipBox: Box<ClipEntity>
    private lateinit var ocrBox: Box<OcrEntity>
    private lateinit var objectsBox: Box<ObjectsEntity>
    private lateinit var gpsBox: Box<GpsEntity>
    private lateinit var metaBox: Box<ImageMetaEntity>
    private lateinit var faceBox: Box<FaceEntity>
    private lateinit var clusterBox: Box<FaceClusterEntity>

    fun init(context: Context) {
        if (::store.isInitialized) return
        context.applicationContext.getDatabasePath("embeddings.db").delete()
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
        clipBox    = store.boxFor(ClipEntity::class.java)
        ocrBox     = store.boxFor(OcrEntity::class.java)
        objectsBox = store.boxFor(ObjectsEntity::class.java)
        gpsBox     = store.boxFor(GpsEntity::class.java)
        metaBox    = store.boxFor(ImageMetaEntity::class.java)
        faceBox    = store.boxFor(FaceEntity::class.java)
        clusterBox = store.boxFor(FaceClusterEntity::class.java)
    }

    // --- CLIP embeddings ---

    fun hasEmbeddingPath(path: String): Boolean =
        clipBox.query()
            .equal(ClipEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insert(path: String, embedding: FloatArray) =
        clipBox.put(ClipEntity(path = path, embedding = embedding))

    fun count(): Int = clipBox.count().toInt()

    fun findSimilar(query: FloatArray, topK: Int, allowedPaths: Set<String>? = null): List<String> {
        val results = clipBox.query()
            .nearestNeighbors(ClipEntity_.embedding, query, topK * 3)
            .build().use { it.findWithScores() }
            .sortedBy { it.score }
            .map { it.get().path }
        return (if (allowedPaths != null) results.filter { it in allowedPaths } else results).take(topK)
    }

    // --- OCR ---

    fun hasOcrPath(path: String): Boolean =
        ocrBox.query()
            .equal(OcrEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun hasOcr(hash: String): Boolean =
        ocrBox.query()
            .equal(OcrEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insertOcr(hash: String, path: String, text: String?) =
        ocrBox.put(OcrEntity(hash = hash, path = path, text = text ?: ""))

    fun updateOcrPath(hash: String, path: String) =
        ocrBox.query()
            .equal(OcrEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.also { it.path = path; ocrBox.put(it) }

    fun ocrCount(): Int = ocrBox.count().toInt()

    fun getOcrText(path: String): String? =
        ocrBox.query()
            .equal(OcrEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.text?.ifEmpty { null }

    fun findByText(query: String, limit: Int, allowedPaths: Set<String>? = null): List<String> =
        ocrBox.query()
            .contains(OcrEntity_.text, query, StringOrder.CASE_INSENSITIVE)
            .notEqual(OcrEntity_.text, "", StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .map { it.path }
            .let { if (allowedPaths != null) it.filter { p -> p in allowedPaths } else it }
            .take(limit)

    // --- Objects ---

    fun hasObjectsPath(path: String): Boolean =
        objectsBox.query()
            .equal(ObjectsEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun hasObjects(hash: String): Boolean =
        objectsBox.query()
            .equal(ObjectsEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insertObjects(hash: String, path: String, labels: List<String>) =
        objectsBox.put(ObjectsEntity(hash = hash, path = path, labels = labels.joinToString(",")))

    fun updateObjectsPath(hash: String, path: String) =
        objectsBox.query()
            .equal(ObjectsEntity_.hash, hash, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.also { it.path = path; objectsBox.put(it) }

    fun objectsCount(): Int = objectsBox.count().toInt()

    fun getObjectLabels(path: String): List<String> =
        objectsBox.query()
            .equal(ObjectsEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?.labels?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun getDistinctObjectLabels(): List<Pair<String, Int>> =
        objectsBox.all
            .flatMap { it.labels.split(",").map(String::trim) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }

    fun getExamplePath(label: String): String? =
        objectsBox.query()
            .contains(ObjectsEntity_.labels, label, StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .firstOrNull { it.hasLabel(label) }
            ?.path

    fun getImagePathsByLabel(label: String): List<String> =
        objectsBox.query()
            .contains(ObjectsEntity_.labels, label, StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .filter { it.hasLabel(label) }
            .map { it.path }

    fun findByLabel(query: String, limit: Int, allowedPaths: Set<String>? = null): List<String> =
        objectsBox.query()
            .contains(ObjectsEntity_.labels, query, StringOrder.CASE_INSENSITIVE)
            .build().use { it.find() }
            .filter { it.hasLabel(query) }
            .map { it.path }
            .let { if (allowedPaths != null) it.filter { p -> p in allowedPaths } else it }
            .take(limit)

    // --- Faces ---

    // Row existence means face detection has run for this path (even if no faces found).
    fun hasFaceEntry(path: String): Boolean =
        faceBox.query()
            .equal(FaceEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

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
        detectionScore: Float? = null,
    ) {
        faceBox.put(FaceEntity(
            path           = path,
            faceIndex      = faceIndex,
            // faceIndex < 0 is a sentinel — no embedding stored, excluded from HNSW
            embedding      = if (faceIndex >= 0) embedding.takeIf { it.isNotEmpty() } else null,
            bboxLeft       = bbox?.left ?: 0f,
            bboxTop        = bbox?.top ?: 0f,
            bboxRight      = bbox?.right ?: 0f,
            bboxBottom     = bbox?.bottom ?: 0f,
            yaw            = yaw ?: 0f,
            pitch          = pitch ?: 0f,
            roll           = roll ?: 0f,
            faceSize       = faceSize ?: 0f,
            blurScore      = blurScore ?: 0f,
            hasLandmarks   = hasLandmarks ?: false,
            detectionScore = detectionScore ?: 0f,
        ))
    }

    fun clearFaces() = store.callInTx {
        clusterBox.removeAll()
        faceBox.removeAll()
        // hasFaceEntry() checks row existence, so removing all rows is sufficient
    }

    fun countFaceEntries(): Int =
        faceBox.query()
            .notEqual(FaceEntity_.faceIndex, -1L)
            .build().use { it.count().toInt() }

    fun findSimilarFaces(query: FloatArray, topK: Int): List<String> =
        faceBox.query()
            .nearestNeighbors(FaceEntity_.embedding, query, topK * 3)
            .build().use { it.findWithScores() }
            .sortedBy { it.score }
            .map { it.get().path }
            .distinct()
            .take(topK)

    fun getAllFaceEmbeddings(): List<FaceEntry> =
        faceBox.all
            .filter { it.faceIndex >= 0 }
            .map { it.toFaceEntry() }

    fun getAllGoodFaceEmbeddings(minDetectionScore: Float = 0.70f): List<FaceEntry> =
        getAllFaceEmbeddings().filter { it.isGoodQuality && it.detectionScore >= minDetectionScore }

    fun getFaceBoxesForPath(path: String): List<FaceBox> =
        faceBox.query()
            .equal(FaceEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .greater(FaceEntity_.faceIndex, -1L)
            .build().use { it.find() }
            .map { face ->
                FaceBox(
                    bbox      = RectF(face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom),
                    clusterId = if (face.clusterId == 0L) null else face.clusterId,
                )
            }

    // --- Clusters ---

    fun hasClusters(): Boolean = clusterBox.count() > 0

    fun storeClusters(clusters: List<FaceClusterer.FaceCluster>) = store.callInTx {
        clusterBox.removeAll()

        val updatedFaces = mutableListOf<FaceEntity>()
        for (cluster in clusters) {
            val clusterEntity = FaceClusterEntity(
                representativePath        = cluster.representativePath,
                representativeBboxLeft    = cluster.representativeBBox?.left ?: 0f,
                representativeBboxTop     = cluster.representativeBBox?.top ?: 0f,
                representativeBboxRight   = cluster.representativeBBox?.right ?: 0f,
                representativeBboxBottom  = cluster.representativeBBox?.bottom ?: 0f,
            )
            clusterBox.put(clusterEntity)
            for (faceId in cluster.memberFaceIds) {
                val face = faceBox.get(faceId) ?: continue
                face.clusterId = clusterEntity.id
                updatedFaces.add(face)
            }
        }
        faceBox.put(updatedFaces)
    }

    fun getClusterMemberBBoxes(): Map<Pair<String, Long>, RectF?> =
        faceBox.query()
            .greater(FaceEntity_.clusterId, 0L)
            .greater(FaceEntity_.faceIndex, -1L)
            .build().use { it.find() }
            .associate { face ->
                (face.path to face.clusterId) to
                    RectF(face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom)
                        .takeUnless { it.isEmpty }
            }

    fun getStoredClusters(): List<StoredCluster> =
        clusterBox.all
            .map { cluster ->
                val members = faceBox.query()
                    .equal(FaceEntity_.clusterId, cluster.id)
                    .build().use { it.find() }
                StoredCluster(
                    clusterId          = cluster.id,
                    name               = cluster.name.ifEmpty { null },
                    representativePath = cluster.representativePath,
                    representativeBBox = RectF(
                        cluster.representativeBboxLeft, cluster.representativeBboxTop,
                        cluster.representativeBboxRight, cluster.representativeBboxBottom,
                    ),
                    paths = members.map { it.path }.distinct(),
                )
            }
            .sortedByDescending { it.paths.size }

    // --- GPS ---

    // Row existence means GPS extraction has run for this path.
    fun hasGpsEntry(path: String): Boolean =
        gpsBox.query()
            .equal(GpsEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.count() > 0 }

    fun insertGps(path: String, lat: Double?, lon: Double?) =
        gpsBox.put(GpsEntity(
            path   = path,
            lat    = lat ?: 0.0,
            lon    = lon ?: 0.0,
            hasGps = lat != null && lon != null,
        ))

    fun getGpsPoints(): List<Triple<String, Double, Double>> =
        gpsBox.query()
            .equal(GpsEntity_.hasGps, true)
            .build().use { it.find() }
            .map { Triple(it.path, it.lat, it.lon) }

    fun getIndexedGpsPaths(): Set<String> =
        gpsBox.all.map { it.path }.toSet()

    // --- Hidden ---

    fun isHidden(path: String): Boolean =
        metaBox.query()
            .equal(ImageMetaEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .equal(ImageMetaEntity_.isHidden, true)
            .build().use { it.count() > 0 }

    fun setHidden(path: String, hidden: Boolean) =
        upsertMeta(path) { isHidden = hidden }

    fun getHiddenPaths(): List<String> =
        metaBox.query()
            .equal(ImageMetaEntity_.isHidden, true)
            .build().use { it.find() }
            .map { it.path }

    // --- Favorites ---

    fun isFavorite(path: String): Boolean =
        metaBox.query()
            .equal(ImageMetaEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .equal(ImageMetaEntity_.isFavorite, true)
            .build().use { it.count() > 0 }

    fun setFavorite(path: String, favorite: Boolean) =
        upsertMeta(path) { isFavorite = favorite }

    fun getFavoritePaths(): List<String> =
        metaBox.query()
            .equal(ImageMetaEntity_.isFavorite, true)
            .build().use { it.find() }
            .map { it.path }

    // --- Helpers ---

    private val metaLock = Any()

    private fun upsertMeta(path: String, update: ImageMetaEntity.() -> Unit) = synchronized(metaLock) {
        val e = metaBox.query()
            .equal(ImageMetaEntity_.path, path, StringOrder.CASE_SENSITIVE)
            .build().use { it.findFirst() }
            ?: ImageMetaEntity(path = path)
        e.update()
        metaBox.put(e)
    }

    private fun ObjectsEntity.hasLabel(label: String): Boolean =
        labels.split(",").any { it.trim().equals(label, ignoreCase = true) }

    private fun FaceEntity.toFaceEntry(): FaceEntry = FaceEntry(
        id             = id,
        path           = path,
        faceIndex      = faceIndex,
        embedding      = embedding ?: FloatArray(0),
        bbox           = if (bboxLeft == 0f && bboxTop == 0f && bboxRight == 0f && bboxBottom == 0f) null
                         else RectF(bboxLeft, bboxTop, bboxRight, bboxBottom),
        yaw            = yaw,
        pitch          = pitch,
        roll           = roll,
        faceSize       = faceSize,
        blurScore      = blurScore,
        hasLandmarks   = hasLandmarks,
        detectionScore = detectionScore,
    )
}
