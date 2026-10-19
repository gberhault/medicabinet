package com.medicabinet.app.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.medicabinet.core.testing.SchemaSnapshot

/** Test helpers that read a database through its driver. */
object SqlProbe {
    /** Runs a query and maps every row by column index. */
    fun <T> query(driver: SqlDriver, sql: String, map: (SqlCursor) -> T): List<T> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<T>()
            while (cursor.next().value) rows += map(cursor)
            QueryResult.Value(rows)
        },
        parameters = 0
    ).value

    fun execute(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    fun string(driver: SqlDriver, sql: String): String? = query(driver, sql) { it.getString(0) }.firstOrNull()

    fun long(driver: SqlDriver, sql: String): Long? = query(driver, sql) { it.getLong(0) }.firstOrNull()

    /** Introspects tables, foreign keys and `CREATE INDEX` indexes with the same pragmas as the iOS test. */
    fun snapshot(driver: SqlDriver): SchemaSnapshot {
        val names = query(
            driver,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' ORDER BY name"
        ) { it.getString(0)!! }
        val tables = mutableMapOf<String, SchemaSnapshot.Table>()
        val indexes = mutableListOf<SchemaSnapshot.Index>()
        for (name in names) {
            val columns = query(driver, "PRAGMA table_info($name)") {
                SchemaSnapshot.Column(
                    name = it.getString(1)!!,
                    type = it.getString(2)!!,
                    notNull = it.getLong(3) == 1L,
                    default = it.getString(4),
                    primaryKey = (it.getLong(5) ?: 0L) > 0L
                )
            }
            val foreignKeys = query(driver, "PRAGMA foreign_key_list($name)") {
                SchemaSnapshot.ForeignKey(
                    from = it.getString(3)!!,
                    table = it.getString(2)!!,
                    to = it.getString(4)!!,
                    onDelete = it.getString(6)!!
                )
            }.sortedBy { it.from }
            tables[name] = SchemaSnapshot.Table(columns, foreignKeys)

            for ((indexName, unique) in query(driver, "PRAGMA index_list($name)") {
                Triple(it.getString(1)!!, it.getLong(2) == 1L, it.getString(3)!!)
            }.filter { it.third == "c" }.map { it.first to it.second }) {
                val indexColumns = query(driver, "PRAGMA index_info($indexName)") { it.getString(2)!! }
                val ddl = string(driver, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$indexName'")
                val where = ddl?.substringAfter(" WHERE ", missingDelimiterValue = "")?.trim()?.ifEmpty { null }
                indexes += SchemaSnapshot.Index(indexName, name, indexColumns, unique, where)
            }
        }
        return SchemaSnapshot(tables, indexes.sortedBy { it.name })
    }
}
