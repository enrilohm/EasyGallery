package com.example.easygallery.gallery

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class ImageMetaEntity(
    @Id var id: Long = 0,

    @Index var path: String = "",

    var isFavorite: Boolean = false,
    var isHidden: Boolean = false,
)
