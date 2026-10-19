package com.medicabinet.core.testing

import java.io.File
import java.util.Base64
import kotlinx.serialization.Serializable

/** One case of `fixtures/archive-cases.json`, the archive decode vectors shared with the iOS app. */
@Serializable
data class ArchiveCase(
    val name: String,
    val file: String? = null,
    val content: String? = null,
    val contentBase64: String? = null,
    val generate: Generate? = null,
    val expect: Expect
) {
    /** `fileBytes`: the file is this many spaces. `locations` and `boxes`: a synthetic valid archive. */
    @Serializable
    data class Generate(val fileBytes: Int? = null, val locations: Int? = null, val boxes: Int? = null)

    @Serializable
    data class Expect(val summary: Summary? = null, val needsBackup: Boolean? = null, val error: ExpectedError? = null)

    @Serializable
    data class ExpectedError(val kind: String, val path: String? = null)

    override fun toString(): String = name

    /** The bytes this case feeds the decoder. */
    fun bytes(): ByteArray = when {
        file != null -> File(SharedFixtures.directory, file).readBytes()
        content != null -> content.toByteArray(Charsets.UTF_8)
        contentBase64 != null -> Base64.getDecoder().decode(contentBase64)
        generate?.fileBytes != null -> ByteArray(generate.fileBytes) { ' '.code.toByte() }
        generate != null -> {
            SyntheticArchive.json(generate.locations ?: 1, generate.boxes ?: 0).toByteArray(Charsets.UTF_8)
        }
        else -> ByteArray(0)
    }
}

@Serializable
data class Summary(
    val formatVersion: Int,
    val sourceFormatVersion: Int,
    val exportedAt: String,
    val app: App,
    val locations: Int,
    val inCabinet: Int,
    val removed: Int,
    val deleted: Int,
    val unknownExpiry: Int
) {
    @Serializable
    data class App(val name: String, val platform: String, val version: String, val build: String)
}

@Serializable
data class ArchiveCaseFixture(val formatVersion: Int, val description: String, val cases: List<ArchiveCase>)

/** A valid archive of a given size, as plain text (one location is the default; every box is in it). */
object SyntheticArchive {
    fun json(locations: Int, boxes: Int): String {
        val header = "{\"archive\":\"medicabinet\",\"formatVersion\":1,\"exportedAt\":\"2026-10-19T08:15:30.123Z\"," +
            "\"app\":{\"name\":\"MediCabinet\",\"platform\":\"ios\",\"version\":\"0.3.0\",\"build\":\"3\"}," +
            "\"locations\":["
        return header + (0 until locations).joinToString(",") { location(it) } + "],\"boxes\":[" +
            (0 until boxes).joinToString(",") { box(it) } + "]}"
    }

    fun uuid(prefix: String, index: Int): String =
        prefix + "%07X".format(index) + "-0000-4000-8000-" + "%012X".format(index)

    private fun location(index: Int): String =
        "{\"id\":\"${uuid("1", index)}\",\"name\":\"Location $index\",\"isDefault\":${index == 0}," +
            "\"sortOrder\":$index," +
            "\"createdAt\":\"2026-10-01T09:00:00.000Z\",\"updatedAt\":\"2026-10-01T09:00:00.000Z\"}"

    private fun box(index: Int): String =
        "{\"id\":\"${uuid("B", index)}\",\"gtin\":\"03400936404564\",\"productName\":\"Box $index\"," +
            "\"category\":\"uncategorized\",\"categoryOverridden\":false,\"locationId\":\"${uuid("1", 0)}\"," +
            "\"expiry\":{\"year\":2027,\"month\":3,\"day\":15}," +
            "\"lot\":\"LOT${index % 1000}\",\"serial\":\"SN$index\"," +
            "\"isOpened\":false,\"removal\":null,\"createdAt\":\"2026-10-02T10:00:00.000Z\"," +
            "\"updatedAt\":\"2026-10-02T10:00:00.000Z\",\"deletedAt\":null}"
}
