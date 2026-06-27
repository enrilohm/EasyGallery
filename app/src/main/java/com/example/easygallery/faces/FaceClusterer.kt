package com.example.easygallery.faces

import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object FaceClusterer {

    data class FaceCluster(
        val representativeFaceId: Long,
        val representativePath: String,
        val representativeBBox: RectF?,
        val memberFaceIds: List<Long>,
        val paths: List<String>
    )

    /**
     * Single-linkage clustering via ObjectBox HNSW nearest-neighbor index.
     * O(n·k·log n) instead of O(n²) — HNSW returns approximate neighbors;
     * union-find handles transitivity so we only need one path per cluster.
     *
     * k: neighbors queried per face. Larger k = more robust but slower.
     * The HNSW index contains all faces; neighbors not in [faces] are skipped.
     */
    suspend fun cluster(
        faces: List<FacesStore.FaceEntry>,
        threshold: Float = 0.45f,
        k: Int = 50,
    ): List<FaceCluster> {
        if (faces.isEmpty()) return emptyList()
        val n = faces.size

        // ObjectBox HNSW score = squared L2 distance.
        // For unit vectors: score = 2*(1 - cosine_sim),
        // so cosine_sim > threshold  ⟺  score < 2*(1 - threshold).
        val scoreThreshold = 2f * (1f - threshold)

        val idToIdx = HashMap<Long, Int>(n * 2)
        for (i in 0 until n) idToIdx[faces[i].id] = i

        // Query HNSW in parallel — each query is independent.
        // Pairs are collected (small list: at most n*k entries) and unioned sequentially.
        val pairs: List<Pair<Int, Int>> = coroutineScope {
            val workers = minOf(8, Runtime.getRuntime().availableProcessors())
            val chunkSize = (n + workers - 1) / workers
            (0 until workers).map { t ->
                async(Dispatchers.IO) {
                    val result = mutableListOf<Pair<Int, Int>>()
                    val start = t * chunkSize
                    val end = minOf(start + chunkSize, n)
                    for (i in start until end) {
                        val neighbors = FacesStore.nearestFaceNeighbors(faces[i].embedding, k + 1)
                        for ((neighborId, score) in neighbors) {
                            if (score <= 0f) continue          // self-match
                            if (score >= scoreThreshold) continue  // below threshold
                            val j = idToIdx[neighborId] ?: continue  // not in good-quality set
                            if (i != j) result.add(i to j)
                        }
                    }
                    result
                }
            }.awaitAll().flatten()
        }

        val parent = IntArray(n) { it }
        val rank = IntArray(n)
        for ((i, j) in pairs) union(parent, rank, i, j)

        val groups = HashMap<Int, MutableList<Int>>(n)
        for (i in 0 until n) groups.getOrPut(find(parent, i)) { mutableListOf() }.add(i)

        return groups.values
            .sortedByDescending { it.size }
            .map { idxList ->
                val c = centroid(idxList.map { faces[it].embedding })
                val repIdx = idxList.maxByOrNull { idx ->
                    val e = faces[idx].embedding; var s = 0f
                    for (d in c.indices) s += e[d] * c[d]; s
                }!!
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

    private fun find(parent: IntArray, x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var cur = x
        while (cur != r) { val next = parent[cur]; parent[cur] = r; cur = next }
        return r
    }

    private fun union(parent: IntArray, rank: IntArray, a: Int, b: Int) {
        val ra = find(parent, a); val rb = find(parent, b)
        if (ra == rb) return
        if (rank[ra] < rank[rb]) parent[ra] = rb
        else if (rank[ra] > rank[rb]) parent[rb] = ra
        else { parent[rb] = ra; rank[ra]++ }
    }

    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        val c = FloatArray(embeddings[0].size)
        for (e in embeddings) for (k in c.indices) c[k] += e[k]
        var norm = 0f; for (x in c) norm += x * x; norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in c.indices) c[i] /= norm
        return c
    }
}
