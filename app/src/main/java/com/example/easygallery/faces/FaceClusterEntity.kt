package com.example.easygallery.faces

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class FaceClusterEntity(
    @Id var id: Long = 0,
    var name: String = "",
    var representativePath: String = "",
    var representativeBboxLeft: Float = 0f,
    var representativeBboxTop: Float = 0f,
    var representativeBboxRight: Float = 0f,
    var representativeBboxBottom: Float = 0f,
)
