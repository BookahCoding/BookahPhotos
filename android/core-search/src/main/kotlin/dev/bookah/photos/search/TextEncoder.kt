package dev.bookah.photos.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.IntBuffer
import kotlin.math.sqrt

/** A question as 512 numbers: the small text model (ONNX Runtime on the phone). The result has unit length, so a dot product is the cosine. */
interface QueryEncoder {
    fun encode(text: String): FloatArray
}

class OnnxQueryEncoder(model: InstalledModel, private val tokenizer: PhoneTokenizer) : QueryEncoder, Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model.onnx.path, OrtSession.SessionOptions())
    private val inputName = model.manifest.textModel.input

    @Synchronized
    override fun encode(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        OnnxTensor.createTensor(env, IntBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val row = (out[0].value as Array<FloatArray>)[0]
                return normalise(row)
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        fun normalise(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s).toFloat().coerceAtLeast(1e-12f)
            return FloatArray(v.size) { v[it] / n }
        }
    }
}

/** Why a search cannot run right now, so the screen can say it in words. */
enum class SearchBlock { NoModel, NoVectors, WrongModel }

/**
 * Content search: the question goes through the tokenizer and the text model, then is compared with every picture's vector. It refuses to search when the
 * vectors were made by another model than the text model (the answers would be nonsense that looks real).
 */
class SearchEngine(private val vectors: VectorIndex?, private val modelId: String?, private val encoder: QueryEncoder?) {
    fun blocked(): SearchBlock? = when {
        encoder == null || modelId == null -> SearchBlock.NoModel
        vectors == null || vectors.size == 0 -> SearchBlock.NoVectors
        vectors.modelId != modelId -> SearchBlock.WrongModel
        else -> null
    }

    /** Best [k] files for a typed question; empty for a blank one or when search is blocked. */
    fun search(question: String, k: Int = 200): List<Hit> {
        val q = question.trim()
        if (q.isEmpty() || blocked() != null) return emptyList()
        return vectors!!.search(encoder!!.encode(q), k)
    }
}
