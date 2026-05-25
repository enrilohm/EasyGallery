package com.example.easygallery.objects

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class ObjectsEntity(
    @Id var id: Long = 0,

    @Index var hash: String = "",
    @Index var path: String = "",

    var labels: String = "",    // comma-separated YOLO labels
)
