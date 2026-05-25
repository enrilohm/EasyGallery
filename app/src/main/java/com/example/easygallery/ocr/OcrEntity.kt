package com.example.easygallery.ocr

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class OcrEntity(
    @Id var id: Long = 0,

    @Index var hash: String = "",
    @Index var path: String = "",

    var text: String = "",
)
