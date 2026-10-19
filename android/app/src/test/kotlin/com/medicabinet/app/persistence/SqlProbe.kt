package com.medicabinet.app.persistence

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.testing.SchemaSnapshot

/** Test helpers that read a database through plain SQL. */
object SqlProbe {
    fun <T> query(db: SupportSQLiteDatabase, sql: String, map: (Cursor) -> T): List<T> = db.query(sql).use { cursor ->
        val rows = mutableListOf<T>()
        while (cursor.moveToNext()) rows += map(cursor)
        rows
    }

    fun string(db: SupportSQLiteDatabase, sql: String): String? = query(db, sql) { it.stringOrNull(0) }.firstOrNull()

    fun long(db: SupportSQLiteDatabase, sql: String): Long? = query(db, sql) { it.getLong(0) }.firstOrNull()

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    /** Introspects tables, foreign keys and `CREATE INDEX` indexes with the same pragmas as the iOS test. */
    fun snapshot(db: SupportSQLiteDatabase): SchemaSnapshot {
        val names = query(
            db,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' ORDER BY name"
        ) { it.getString(0) }
        val tables = mutableMapOf<String, SchemaSnapshot.Table>()
        val indexes = mutableListOf<SchemaSnapshot.Index>()
        for (name in names) {
            val columns = query(db, "PRAGMA table_info($name)") {
                SchemaSnapshot.Column(
                    name = it.getString(1),
                    type = it.getString(2),
                    notNull = it.getLong(3) == 1L,
                    default = it.stringOrNull(4),
                    primaryKey = it.getLong(5) > 0L
                )
            }
            val foreignKeys = query(db, "PRAGMA foreign_key_list($name)") {
                SchemaSnapshot.ForeignKey(
                    from = it.getString(3),
                    table = it.getString(2),
                    to = it.getString(4),
                    onDelete = it.getString(6)
                )
            }.sortedBy { it.from }
            tables[name] = SchemaSnapshot.Table(columns, foreignKeys)

            val created = query(db, "PRAGMA index_list($name)") {
                Triple(it.getString(1), it.getLong(2) == 1L, it.getString(3))
            }
                .filter { it.third == "c" }
            for ((indexName, unique) in created.map { it.first to it.second }) {
                val indexColumns = query(db, "PRAGMA index_info($indexName)") { it.getString(2) }
                val ddl = string(db, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$indexName'")
                val where = ddl?.substringAfter(" WHERE ", missingDelimiterValue = "")?.trim()?.ifEmpty { null }
                indexes += SchemaSnapshot.Index(indexName, name, indexColumns, unique, where)
            }
        }
        return SchemaSnapshot(tables, indexes.sortedBy { it.name })
    }
}
