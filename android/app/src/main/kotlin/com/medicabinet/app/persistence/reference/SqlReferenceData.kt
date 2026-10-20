package com.medicabinet.app.persistence.reference

import android.database.sqlite.SQLiteException
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.reference.Cip13
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The reference data the app uses: installed and opened lazily on first use (never during launch), once, off the main
 * thread. When it is missing or unusable every product is unknown and [status] says why; nothing throws because of it,
 * so the cabinet keeps working.
 */
class SqlReferenceData(
    private val installer: ReferenceInstaller,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onUnavailable: (ReferenceUnavailableReason, Throwable?) -> Unit = AppLog::referenceUnavailable
) : ReferenceDataSource {
    private sealed interface Opened {
        class Ready(val database: ReferenceDatabase) : Opened

        class Failed(val reason: ReferenceUnavailableReason) : Opened
    }

    private val mutex = Mutex()
    private var opened: Opened? = null

    override suspend fun product(gtin: String): ReferenceProduct? {
        val cip13 = Cip13.fromGtin(gtin)
        val database = (open() as? Opened.Ready)?.database
        return if (cip13 == null || database == null) null else lookup(database, cip13)
    }

    override suspend fun status(): ReferenceStatus = when (val state = open()) {
        is Opened.Ready -> ReferenceStatus.Available(state.database.dataDate)
        is Opened.Failed -> ReferenceStatus.Unavailable(state.reason)
    }

    private suspend fun lookup(database: ReferenceDatabase, cip13: String): ReferenceProduct? = withContext(io) {
        try {
            database.product(cip13)
        } catch (error: SQLiteException) {
            // Fails like an unreadable database: the product is unknown, the scan goes on.
            onUnavailable(ReferenceUnavailableReason.UNREADABLE, error)
            null
        }
    }

    private suspend fun open(): Opened = mutex.withLock {
        opened ?: withContext(io) { openBlocking() }.also { opened = it }
    }

    private fun openBlocking(): Opened = try {
        Opened.Ready(openInstalled())
    } catch (error: ReferenceUnavailableException) {
        onUnavailable(error.reason, error)
        Opened.Failed(error.reason)
    }

    /** An installed copy that no longer opens is replaced from the assets once before giving up. */
    private fun openInstalled(): ReferenceDatabase = try {
        ReferenceDatabase.open(installer.install())
    } catch (error: ReferenceUnavailableException) {
        if (error.reason != ReferenceUnavailableReason.UNREADABLE) throw error
        ReferenceDatabase.open(installer.install(force = true))
    }
}
