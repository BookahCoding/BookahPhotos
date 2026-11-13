package dev.bookah.photos.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `BookahPhotos/index/manifest.json`, written last by the indexer (see docs/snapshot-schema.md). */
@Serializable
data class Manifest(
    val generation: Long,
    val file: String,
    val size: Long,
    val sha256: String,
    @SerialName("schema_major") val schemaMajor: Int,
    @SerialName("schema_minor") val schemaMinor: Int = 0,
    @SerialName("created_utc") val createdUtc: String? = null,
    val counts: Counts = Counts(),
) {
    @Serializable
    data class Counts(val assets: Int = 0, val photos: Int = 0, val videos: Int = 0, val faces: Int = 0, val people: Int = 0)

    companion object {
        /** The newest schema major version this app understands. A newer major means "update the app". */
        const val SUPPORTED_MAJOR = 1
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Manifest = json.decodeFromString(serializer(), text)
    }
}
