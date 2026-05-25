package com.example.easygallery.faces

import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.sqrt

object FaceClusterer {

    data class FaceCluster(
        val representativeFaceId: Long,
        val representativePath: String,
        val representativeBBox: RectF?,
        val memberFaceIds: List<Long>,
        val paths: List<String>
    )

    suspend fun cluster(
        faces: List<FacesStore.FaceEntry>,
        threshold: Float = 0.45f
    ): List<FaceCluster> {
        if (faces.isEmpty()) return emptyList()
        val n = faces.size

        val adj = buildGraph(faces, threshold)

        // Each node starts as its own cluster label
        val labels = IntArray(n) { it }
        val order = IntArray(n) { it }

        repeat(30) {
            order.shuffle()
            var changed = false
            for (i in order) {
                if (adj[i].isEmpty()) continue
                val weightByLabel = HashMap<Int, Float>(adj[i].size * 2)
                for ((j, w) in adj[i]) {
                    weightByLabel[labels[j]] = (weightByLabel[labels[j]] ?: 0f) + w
                }
                val best = weightByLabel.maxByOrNull { it.value }!!.key
                if (best != labels[i]) { labels[i] = best; changed = true }
            }
            if (!changed) return@repeat
        }

        val groups = HashMap<Int, MutableList<Int>>()
        for (i in labels.indices) groups.getOrPut(labels[i]) { mutableListOf() }.add(i)

        return groups.values
            .sortedByDescending { it.size }
            .map { idxList ->
                val c = centroid(idxList.map { faces[it].embedding })
                val repIdx = idxList.maxByOrNull { dot(faces[it].embedding, c) }!!
                val rep = faces[repIdx]
                FaceCluster(
                    representativeFaceId = rep.id,
                    representativePath   = rep.path,
                    representativeBBox   = rep.bbox,
                    memberFaceIds        = idxList.map { faces[it].id },
                    paths                = idxList.map { faces[it].path }.distinct()
                )
            }
    }

    // Pair comparisons divided across CPU cores; each worker owns a contiguous row range
    private suspend fun buildGraph(
        faces: List<FacesStore.FaceEntry>,
        threshold: Float
    ): Array<MutableList<Pair<Int, Float>>> = coroutineScope {
        val n = faces.size
        val workers = minOf(8, Runtime.getRuntime().availableProcessors())
        val chunkSize = (n + workers - 1) / workers

        val chunks = (0 until workers).map { t ->
            async(Dispatchers.Default) {
                val edges = mutableListOf<Triple<Int, Int, Float>>()
                val start = t * chunkSize
                val end = minOf(start + chunkSize, n)
                for (i in start until end) {
                    val ei = faces[i].embedding
                    for (j in i + 1 until n) {
                        val sim = dot(ei, faces[j].embedding)
                        if (sim > threshold) edges.add(Triple(i, j, sim))
                    }
                }
                edges
            }
        }.awaitAll()

        val adj = Array(n) { mutableListOf<Pair<Int, Float>>() }
        for (chunk in chunks) {
            for ((i, j, w) in chunk) {
                adj[i].add(j to w)
                adj[j].add(i to w)
            }
        }
        adj
    }

    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        val c = FloatArray(embeddings[0].size)
        for (e in embeddings) for (k in c.indices) c[k] += e[k]
        normalize(c)
        return c
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
    }
}
