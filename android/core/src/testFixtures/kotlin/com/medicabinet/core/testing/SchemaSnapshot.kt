package com.medicabinet.core.testing

import kotlinx.serialization.Serializable

/**
 * The shape of a database schema as introspected with SQLite pragmas — also the content of
 * `fixtures/schema-v1.json`, which the iOS database is compared with too.
 */
@Serializable
data class SchemaSnapshot(
    val tables: Map<String, Table>,
    val indexes: List<Index>,
    /** Declared types a platform may spell differently (`DATETIME` on iOS is `TEXT` on Android). */
    val typeAliases: Map<String, String> = emptyMap()
) {
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

    /** This snapshot with every declared type replaced through [typeAliases] (used on the expected side). */
    fun withTypeAliasesApplied(): SchemaSnapshot = copy(
        tables = tables.mapValues { (_, table) ->
            table.copy(columns = table.columns.map { it.copy(type = typeAliases[it.type] ?: it.type) })
        },
        typeAliases = emptyMap()
    )
}
