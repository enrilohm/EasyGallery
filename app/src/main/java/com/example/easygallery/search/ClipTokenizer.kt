package com.example.easygallery.search

import org.json.JSONObject
import java.io.File

/**
 * Pure-Kotlin port of the OpenAI CLIP BPE tokenizer.
 * Reads vocab and merges directly from tokenizer.json (HuggingFace format).
 */
object ClipTokenizer {

    private const val SOT_TOKEN = "<|startoftext|>"
    private const val EOT_TOKEN = "<|endoftext|>"
    const val CONTEXT_LENGTH = 77

    // Maps byte value (0–255) to a unique unicode character — identical to OpenAI's bytes_to_unicode()
    private val byteToUnicode: CharArray by lazy { buildByteToUnicode() }

    private var vocab: Map<String, Int> = emptyMap()
    private var mergeRanks: Map<String, Int> = emptyMap()  // "a b" -> rank
    private val bpeCache = HashMap<String, List<String>>()

    fun load(tokenizerJson: File) {
        val root  = JSONObject(tokenizerJson.readText())
        val model = root.getJSONObject("model")

        val vocabJson = model.getJSONObject("vocab")
        vocab = buildMap(vocabJson.length()) {
            for (key in vocabJson.keys()) put(key, vocabJson.getInt(key))
        }

        val mergesJson = model.getJSONArray("merges")
        mergeRanks = buildMap(mergesJson.length()) {
            for (i in 0 until mergesJson.length()) {
                put(mergesJson.getString(i), i)   // key is already "a b" with a space
            }
        }

        bpeCache.clear()
        android.util.Log.d("ClipTokenizer", "Loaded vocab=${vocab.size} merges=${mergeRanks.size}")
    }

    /** Returns (input_ids, attention_mask) both padded to CONTEXT_LENGTH. */
    fun encode(text: String): Pair<LongArray, LongArray> {
        val sotId = vocab[SOT_TOKEN] ?: 49406
        val eotId = vocab[EOT_TOKEN] ?: 49407

        val ids = mutableListOf(sotId)

        val cleaned = text.lowercase().trim().replace(Regex("""\s+"""), " ")
        val pattern = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|\p{L}+|\p{N}|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE
        )

        for (match in pattern.findAll(cleaned)) {
            val word = match.value
            if (word == SOT_TOKEN || word == EOT_TOKEN) continue

            // Byte-encode: each UTF-8 byte → its designated unicode character
            val byteEncoded = word.toByteArray(Charsets.UTF_8)
                .joinToString("") { byteToUnicode[it.toInt() and 0xFF].toString() }

            for (token in bpe(byteEncoded)) {
                vocab[token]?.let { ids.add(it) }
            }
        }

        ids.add(eotId)

        // Truncate: keep SOT, up to 75 content tokens, EOT
        if (ids.size > CONTEXT_LENGTH) {
            ids[CONTEXT_LENGTH - 1] = eotId
            while (ids.size > CONTEXT_LENGTH) ids.removeAt(ids.size - 1)
        }

        val inputIds = LongArray(CONTEXT_LENGTH)
        val mask     = LongArray(CONTEXT_LENGTH)
        for (i in ids.indices) {
            inputIds[i] = ids[i].toLong()
            mask[i] = 1L
        }
        return inputIds to mask
    }

    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }

        // Split into characters; append </w> to the last one (marks end of word)
        val word: MutableList<String> = if (token.length == 1) {
            mutableListOf("$token</w>")
        } else {
            token.map { it.toString() }.toMutableList().also {
                it[it.size - 1] += "</w>"
            }
        }

        while (word.size > 1) {
            // Find the pair with the lowest merge rank
            var bestRank = Int.MAX_VALUE
            var bestIdx  = -1
            for (i in 0 until word.size - 1) {
                val rank = mergeRanks["${word[i]} ${word[i + 1]}"] ?: continue
                if (rank < bestRank) { bestRank = rank; bestIdx = i }
            }
            if (bestIdx == -1) break

            // Merge that pair everywhere in this pass
            val merged = word[bestIdx] + word[bestIdx + 1]
            var i = 0
            val newWord = mutableListOf<String>()
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == word[bestIdx] && word[i + 1] == word[bestIdx + 1]) {
                    newWord.add(merged); i += 2
                } else {
                    newWord.add(word[i]); i++
                }
            }
            word.clear(); word.addAll(newWord)
        }

        return word.also { bpeCache[token] = it }
    }

    private fun buildByteToUnicode(): CharArray {
        val result = CharArray(256)
        val bs = mutableListOf<Int>()
        ('!'.code..'~'.code).forEach  { bs.add(it) }   // 33–126
        ('¡'.code..'¬'.code).forEach  { bs.add(it) }   // 161–172
        ('®'.code..'ÿ'.code).forEach  { bs.add(it) }   // 174–255
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) { bs.add(b); cs.add(256 + n++) }
        }
        for (i in bs.indices) result[bs[i]] = cs[i].toChar()
        return result
    }
}
