package com.example.easygallery.map

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class GpsEntity(
    @Id var id: Long = 0,

    @Index var path: String = "",

    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var hasGps: Boolean = false,    // true = actual GPS coords present
)
