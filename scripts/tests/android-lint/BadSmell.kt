package com.medicabinet.core

class BadSmell {
    fun swallow() {
        try {
            check(false)
        } catch (e: IllegalStateException) {
        }
    }
}
