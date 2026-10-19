package com.medicabinet.app.persistence

/** Storage-level failures. Messages never contain row data (the cabinet is health data). */
sealed class PersistenceException(message: String) : Exception(message) {
    /** A stored row cannot be turned back into a domain value. Never repaired silently. */
    class CorruptRow(val table: String) : PersistenceException("corrupt row in table $table")
}
