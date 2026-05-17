package com.example.easygallery

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class FaceEntity(
    @Id var id: Long = 0,

    @Index var path: String = "",
    var faceIndex: Int = 0,             // -1 = sentinel (image processed, no faces found); >= 0 = real face

    // MobileFaceNet 512-dim embedding; null excluded from HNSW graph
    @HnswIndex(dimensions = 512)
    var embedding: FloatArray? = null,

    var bboxLeft: Float = 0f,
    var bboxTop: Float = 0f,
    var bboxRight: Float = 0f,
    var bboxBottom: Float = 0f,

    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var roll: Float = 0f,
    var faceSize: Float = 0f,
    var blurScore: Float = 0f,
    var hasLandmarks: Boolean = false,
    var detectionScore: Float = 0f,

    @Index var clusterId: Long = 0,     // 0 = unassigned; > 0 = FaceClusterEntity.id
)
