package com.example.easygallery.faces

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

object ContactFaceLinker {

    suspend fun linkContactsToClusters(context: Context) = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return@withContext
        if (!FacesStore.hasClusters()) return@withContext

        val embeddingsByCluster = FacesStore.getClusterEmbeddings()
        if (embeddingsByCluster.isEmpty()) return@withContext

        val centroids = embeddingsByCluster.mapValues { (_, embs) -> centroid(embs) }
        val storedClusters = FacesStore.getStoredClusters().associateBy { it.clusterId }

        val cursor = try { context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
            ),
            "${ContactsContract.Contacts.PHOTO_URI} IS NOT NULL",
            null, null,
        ) } catch (e: Exception) { null } ?: return@withContext

        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                val photoUriStr = it.getString(2) ?: continue

                val bitmap = try {
                    context.contentResolver.openInputStream(Uri.parse(photoUriStr))
                        ?.use { stream -> BitmapFactory.decodeStream(stream) }
                } catch (e: Exception) { null } ?: continue

                val faces = FaceDetector.detect(bitmap)
                if (faces.size != 1) continue

                val embedding = FaceEncoder.encode(faces[0].crop) ?: continue

                var bestId = -1L
                var bestSim = 0.40f
                for ((clusterId, centroid) in centroids) {
                    val sim = dot(embedding, centroid)
                    if (sim > bestSim) { bestSim = sim; bestId = clusterId }
                }

                if (bestId >= 0 && storedClusters[bestId]?.name == null) {
                    FacesStore.setClusterName(bestId, name)
                }
            }
        }
    }

    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(0)
        val dim = embeddings[0].size
        val c = FloatArray(dim)
        for (e in embeddings) for (i in 0 until dim) c[i] += e[i]
        val len = sqrt(c.sumOf { (it * it).toDouble() }.toFloat())
        if (len > 1e-6f) for (i in c.indices) c[i] /= len
        return c
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
