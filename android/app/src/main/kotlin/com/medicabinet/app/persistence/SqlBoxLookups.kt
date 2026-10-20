package com.medicabinet.app.persistence

import com.medicabinet.core.Box
import com.medicabinet.core.BoxLookups
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The reads of `SqlBoxRepository`: every query ignores deleted boxes. */
internal class SqlBoxLookups(private val cabinet: CabinetDatabase, private val io: CoroutineDispatcher) : BoxLookups {
    override suspend fun activeBoxes(): List<Box> = withContext(io) { inCabinet() }

    override suspend fun box(id: UUID): Box? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE id = ? AND deletedAt IS NULL",
            arrayOf(id.stored())
        ).mapRows { toBox() }.firstOrNull()
    }

    override suspend fun box(gtin: String, serial: String): Box? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE gtin = ? AND serial = ? AND deletedAt IS NULL LIMIT 1",
            arrayOf(gtin, serial)
        ).mapRows { toBox() }.firstOrNull()
    }

    override suspend fun latestBox(gtin: String): Box? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE gtin = ? AND deletedAt IS NULL " +
                "ORDER BY createdAt DESC, id DESC LIMIT 1",
            arrayOf(gtin)
        ).mapRows { toBox() }.firstOrNull()
    }

    // A correlated subquery rather than a window function: Android 8 ships SQLite 3.18, before window functions.
    override suspend fun latestBoxPerGTIN(): List<Box> = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box AS newest WHERE newest.deletedAt IS NULL AND newest.gtin IS NOT NULL " +
                "AND newest.id = (SELECT id FROM box WHERE gtin = newest.gtin AND deletedAt IS NULL " +
                "ORDER BY createdAt DESC, id DESC LIMIT 1) ORDER BY newest.createdAt DESC, newest.id DESC"
        ).mapRows { toBox() }
    }

    override suspend fun cabinetBoxes(gtin: String): List<Box> = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE gtin = ? AND removedAt IS NULL AND deletedAt IS NULL " +
                "ORDER BY createdAt, id",
            arrayOf(gtin)
        ).mapRows { toBox() }
    }

    override fun observeCabinet(): Flow<List<Box>> = cabinet.changes.map { inCabinet() }.flowOn(io).conflate()

    private fun inCabinet(): List<Box> = cabinet.database.query(
        "SELECT ${Columns.BOX} FROM box WHERE removedAt IS NULL AND deletedAt IS NULL ORDER BY createdAt, id"
    ).mapRows { toBox() }
}
