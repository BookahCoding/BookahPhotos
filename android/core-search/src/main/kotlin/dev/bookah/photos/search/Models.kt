package dev.bookah.photos.search

import dev.bookah.photos.data.Session
import dev.bookah.photos.data.SnapshotSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
class ModelFile(val name: String, val size: Long, val sha256: String)

@Serializable
class TextModelInfo(
    val input: String = "text",
    @SerialName("input_type") val inputType: String = "int32",
    val seq: Int = 77,
    @SerialName("output_dim") val outputDim: Int = 512,
)

/** `BookahPhotos/models/manifest.json`: which model files belong together, written by the indexer last (so it never points at a partial file). */
@Serializable
class ModelManifest(
    val version: Int = 1,
    @SerialName("model_id") val modelId: String,
    val dir: String,
    @SerialName("created_utc") val createdUtc: String = "",
    @SerialName("text_model") val textModel: TextModelInfo = TextModelInfo(),
    val files: List<ModelFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }

    /** Names and folders come from the server: only plain names, never a path. */
    fun validate(): ModelManifest {
        require(PLAIN.matches(dir)) { "unexpected model folder name" }
        require(files.all { PLAIN.matches(it.name) }) { "unexpected model file name" }
        require(files.any { it.name == ONNX } && files.any { it.name == TOKENIZER }) { "the manifest lacks the text model or the tokenizer file" }
        require(modelId.isNotBlank() && textModel.seq == PhoneTokenizer.SEQ && textModel.outputDim > 0) { "unsupported text model" }
        return this
    }

    companion object {
        const val ONNX = "text_small.onnx"
        const val TOKENIZER = "text_tokenizer.bin"
        private val PLAIN = Regex("^[A-Za-z0-9._-]{1,120}$")
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ModelManifest = json.decodeFromString<ModelManifest>(text).validate()
    }
}

class InstalledModel(val manifest: ModelManifest, val onnx: File, val tokenizer: File)

/**
 * The search model on the phone, in the app's own storage (not the cache: Android may clear that). Files are written under a temporary name, checked
 * (size and SHA-256), moved onto their final names, and only then `installed.json` is written, so a broken or interrupted download never replaces a
 * working model.
 */
class ModelStore(private val dir: File) {
    init { dir.mkdirs() }

    private val pointer = File(dir, "installed.json")

    class BadDownload(message: String) : IOException(message)

    fun installed(): InstalledModel? {
        val manifest = runCatching { ModelManifest.parse(pointer.readText()) }.getOrNull() ?: return null
        val folder = File(dir, manifest.dir)
        val onnx = File(folder, ModelManifest.ONNX)
        val tok = File(folder, ModelManifest.TOKENIZER)
        val ok = manifest.files.all { f -> File(folder, f.name).let { it.isFile && it.length() == f.size } }
        return if (ok) InstalledModel(manifest, onnx, tok) else null
    }

    fun sizeOnDisk(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun install(manifest: ModelManifest, open: (ModelFile) -> java.io.InputStream, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        manifest.validate()
        val folder = File(dir, manifest.dir)
        folder.mkdirs()
        val total = manifest.totalSize
        var done = 0L
        val tmps = ArrayList<Pair<File, File>>()
        try {
            for (f in manifest.files) {
                val tmp = File(folder, "download-${f.name}.tmp")
                val digest = MessageDigest.getInstance("SHA-256")
                var n = 0L
                open(f).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r); digest.update(buf, 0, r); n += r; done += r
                            onProgress(done, total)
                        }
                    }
                }
                if (n != f.size) throw BadDownload("${f.name}: size $n does not match the manifest (${f.size})")
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(f.sha256, ignoreCase = true)) throw BadDownload("${f.name}: checksum does not match the manifest")
                tmps.add(tmp to File(folder, f.name))
            }
            for ((tmp, final) in tmps) Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val ptmp = File(dir, "installed.json.tmp")
            ptmp.writeText(Json.encodeToString(ModelManifest.serializer(), manifest))
            Files.move(ptmp.toPath(), pointer.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)    // last
            dir.listFiles { f -> f.isDirectory && f.name != manifest.dir }?.forEach { it.deleteRecursively() }                    // an older model folder
        } finally {
            tmps.forEach { it.first.delete() }
        }
    }

    fun delete() {
        pointer.delete()
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** Checks `BookahPhotos/models/manifest.json` and downloads the model files when asked (never by itself: they are 158 MB). */
class ModelSync(private val http: OkHttpClient, private val session: Session, private val store: ModelStore) {
    private fun url(vararg parts: String) = session.davRoot().newBuilder().addPathSegment("BookahPhotos").addPathSegment("models").also { b -> parts.forEach { b.addPathSegment(it) } }.build()

    /** Null when the indexer has not published a model yet. */
    suspend fun fetchManifest(): ModelManifest? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("manifest.json")).header("Authorization", session.authHeader).build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 -> throw SnapshotSync.UnauthorizedException()
                resp.code == 404 -> null
                !resp.isSuccessful -> throw IOException("Nextcloud answered ${resp.code}")
                else -> ModelManifest.parse(resp.body.string())
            }
        }
    }

    suspend fun download(manifest: ModelManifest, onProgress: (done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        manifest.validate()
        store.install(manifest, open = { f ->
            val req = Request.Builder().url(url(manifest.dir, f.name)).header("Authorization", session.authHeader).build()
            val resp = http.newCall(req).execute()
            if (resp.code == 401) { resp.close(); throw SnapshotSync.UnauthorizedException() }
            if (!resp.isSuccessful) { resp.close(); throw IOException("Nextcloud answered ${resp.code} for ${f.name}") }
            resp.body.byteStream()
        }, onProgress = onProgress)
    }
}
