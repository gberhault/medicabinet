package com.medicabinet.core.testing

import kotlinx.serialization.Serializable

/**
 * The shape of a database schema as introspected with SQLite pragmas — also the content of
 * `fixtures/schema-v1.json`, which the iOS database is compared with too.
 */
@Serializable
data class SchemaSnapshot(val tables: Map<String, Table>, val indexes: List<Index>) {
    @Serializable
    data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val primaryKey: Boolean
    )

    @Serializable
    data class ForeignKey(val from: String, val table: String, val to: String, val onDelete: String)

    @Serializable
    data class Table(val columns: List<Column>, val foreignKeys: List<ForeignKey>)

    @Serializable
    data class Index(
        val name: String,
        val table: String,
        val columns: List<String>,
        val unique: Boolean,
        val where: String?
    )
}
