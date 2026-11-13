package dev.bookah.photos.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The snapshots on the phone. A new snapshot is written to a temporary file, checked (size and SHA-256), moved onto its final name and
 * only then made current, so the previous one keeps working until the switch and a broken download never replaces a good one.
 */
class SnapshotStore(private val dir: File) {
    init { dir.mkdirs() }

    private val pointer = File(dir, "current")

    /** The generation that is current, if its file is still there. */
    fun currentGeneration(): Long? {
        val g = runCatching { pointer.readText().trim().toLong() }.getOrNull() ?: return null
        return g.takeIf { fileFor(it).isFile }
    }

    fun currentFile(): File? = currentGeneration()?.let(::fileFor)

    fun fileFor(generation: Long) = File(dir, "bookahphotos-g%06d.db".format(generation))

    class BadDownload(message: String) : IOException(message)

    /** Reads [input] into a temporary file, verifies it against [manifest], makes it current and removes older snapshots (keeps the previous one). */
    fun install(manifest: Manifest, input: InputStream, onProgress: (Long) -> Unit = {}): File {
        val tmp = File(dir, "download.tmp")
        tmp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n); total += n
                    onProgress(total)
                }
            }
            if (total != manifest.size) throw BadDownload("size $total does not match the manifest (${manifest.size})")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(manifest.sha256, ignoreCase = true)) throw BadDownload("checksum does not match the manifest")
            val target = fileFor(manifest.generation)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val ptmp = File(dir, "current.tmp")
            ptmp.writeText(manifest.generation.toString())
            Files.move(ptmp.toPath(), pointer.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            prune(keep = manifest.generation)
            return target
        } finally {
            tmp.delete()
        }
    }

    /** Removes snapshot files except the current one and the one before it. */
    private fun prune(keep: Long) {
        val files = dir.listFiles { f -> f.name.startsWith("bookahphotos-g") && f.name.endsWith(".db") }.orEmpty()
            .mapNotNull { f -> f.name.removePrefix("bookahphotos-g").removeSuffix(".db").toLongOrNull()?.let { it to f } }
            .sortedByDescending { it.first }
        files.filter { it.first != keep }.drop(1).forEach { it.second.delete() }
    }
}
