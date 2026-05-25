package com.example.easygallery.search

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index


@Entity
data class ClipEntity(
    @Id var id: Long = 0,

    @Index var path: String = "",

    @HnswIndex(dimensions = 512)
    var embedding: FloatArray? = null,
)
