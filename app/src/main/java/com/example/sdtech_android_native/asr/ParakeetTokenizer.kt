package com.example.sdtech_android_native.asr

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal pure-Kotlin detokenizer for the Parakeet TDT tokenizer.json (a
 * standard Hugging Face "tokenizers" fast-tokenizer export: BPE vocab +
 * Metaspace pre/post-tokenizer + byte-fallback). Only decoding (token ids ->
 * text) is implemented — encoding/normalization isn't needed since this app
 * only detokenizes model output, never tokenizes new text.
 */
class ParakeetTokenizer private constructor(
    private val idToToken: Array<String?>,
    private val specialIds: Set<Int>,
    private val metaspaceReplacement: String,
) {
    /** Detokenizes [tokenIds] into text, replacing the Metaspace marker with spaces and resolving byte-fallback tokens. */
    fun decode(tokenIds: List<Int>, skipSpecialTokens: Boolean = true): String {
        val pendingBytes = ByteArrayOutputStream()
        val sb = StringBuilder()

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                sb.append(String(pendingBytes.toByteArray(), StandardCharsets.UTF_8))
                pendingBytes.reset()
            }
        }

        for (id in tokenIds) {
            if (skipSpecialTokens && id in specialIds) continue
            val token = idToToken.getOrNull(id) ?: continue
            val byteValue = byteFallbackValue(token)
            if (byteValue != null) {
                pendingBytes.write(byteValue)
            } else {
                flushBytes()
                sb.append(token)
            }
        }
        flushBytes()

        var result = sb.toString().replace(metaspaceReplacement, " ")
        if (result.startsWith(" ")) result = result.substring(1)
        return result
    }

    /** HF byte_fallback tokens are formatted like "<0xAB>", representing one raw byte each. */
    private fun byteFallbackValue(token: String): Int? {
        if (token.length == 6 && token.startsWith("<0x") && token.endsWith(">")) {
            return token.substring(3, 5).toIntOrNull(16)
        }
        return null
    }

    companion object {
        fun loadFromAssets(context: Context, assetPath: String): ParakeetTokenizer {
            val json = context.assets.open(assetPath).use { it.readBytes() }
                .toString(StandardCharsets.UTF_8)
            val root = JSONObject(json)

            val vocab = root.getJSONObject("model").getJSONObject("vocab")
            var maxId = 0
            val pairs = ArrayList<Pair<String, Int>>(vocab.length())
            val keys = vocab.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = vocab.getInt(token)
                pairs.add(token to id)
                if (id > maxId) maxId = id
            }

            val specialIds = mutableSetOf<Int>()
            root.optJSONArray("added_tokens")?.let { addedTokens ->
                for (i in 0 until addedTokens.length()) {
                    val entry = addedTokens.getJSONObject(i)
                    val id = entry.getInt("id")
                    pairs.add(entry.getString("content") to id)
                    if (id > maxId) maxId = id
                    if (entry.optBoolean("special", false)) specialIds.add(id)
                }
            }

            val idToToken = arrayOfNulls<String>(maxId + 1)
            for ((token, id) in pairs) idToToken[id] = token

            val replacement = root.optJSONObject("decoder")?.optString("replacement", "▁") ?: "▁"

            return ParakeetTokenizer(idToToken, specialIds, replacement)
        }
    }
}
