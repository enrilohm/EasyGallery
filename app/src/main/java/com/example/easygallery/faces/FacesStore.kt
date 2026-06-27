package com.example.easygallery.faces

import android.graphics.RectF
import com.example.easygallery.db.AppDatabase
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder

object FacesStore {

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

    private val store by lazy { AppDatabase.getStore() }
    private val faceBox: Box<FaceEntity> by lazy { store.boxFor(FaceEntity::class.java) }
    private val clusterBox: Box<FaceClusterEntity> by lazy { store.boxFor(FaceClusterEntity::class.java) }

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

    fun setClusterName(clusterId: Long, name: String?) {
        val entity = clusterBox.get(clusterId) ?: return
        entity.name = name ?: ""
        clusterBox.put(entity)
    }

    fun getClusterEmbeddings(): Map<Long, List<FloatArray>> =
        faceBox.query()
            .greater(FaceEntity_.clusterId, 0L)
            .greater(FaceEntity_.faceIndex, -1L)
            .build().use { it.find() }
            .groupBy { it.clusterId }
            .mapValues { (_, faces) -> faces.mapNotNull { it.embedding } }

    fun getPathsForCluster(clusterId: Long): Set<String> =
        faceBox.query()
            .equal(FaceEntity_.clusterId, clusterId)
            .greater(FaceEntity_.faceIndex, -1L)
            .build().use { it.find() }
            .map { it.path }
            .toSet()

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
