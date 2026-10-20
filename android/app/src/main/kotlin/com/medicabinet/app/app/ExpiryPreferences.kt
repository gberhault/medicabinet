package com.medicabinet.app.app

import android.content.Context
import android.content.SharedPreferences
import com.medicabinet.core.expiry.ExpiryWindow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Device preferences of expiry tracking, under the same keys as the iOS app. They are not cabinet data: never
 * exported and not part of the Auto Backup rules, so a restored phone starts with the defaults and reminders off.
 */
interface ExpiryPreferences {
    /** The "expiring soon" window; a missing or unknown stored value is the named default. */
    var window: ExpiryWindow

    /** The user's choice of the monthly reminder (`reminder.enabled`). Off by default. */
    var reminderEnabled: Boolean

    /** **Not now** on the Expiring card: remembered for good. */
    var reminderCardDismissed: Boolean

    /** `YYYY-MM` of the last digest delivered or skipped: the guard against a second digest for a month. */
    var lastDigestMonth: String?

    /** The window now, then after every change (also from another instance on the same file). */
    fun windowChanges(): Flow<ExpiryWindow>

    /** The reminder flag now, then after every change. */
    fun reminderEnabledChanges(): Flow<Boolean>
}

class SharedExpiryPreferences(context: Context) : ExpiryPreferences {
    private val store: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var window: ExpiryWindow
        get() = ExpiryWindow.fromStoredDays(store.all[WINDOW_KEY] as? Int)
        set(value) = store.edit().putInt(WINDOW_KEY, value.days).apply()

    override var reminderEnabled: Boolean
        get() = store.all[REMINDER_ENABLED_KEY] as? Boolean ?: false
        set(value) = store.edit().putBoolean(REMINDER_ENABLED_KEY, value).apply()

    override var reminderCardDismissed: Boolean
        get() = store.all[CARD_DISMISSED_KEY] as? Boolean ?: false
        set(value) = store.edit().putBoolean(CARD_DISMISSED_KEY, value).apply()

    override var lastDigestMonth: String?
        get() = store.all[LAST_DIGEST_MONTH_KEY] as? String
        set(value) = store.edit().putString(LAST_DIGEST_MONTH_KEY, value).apply()

    override fun windowChanges(): Flow<ExpiryWindow> = changes(WINDOW_KEY) { window }

    override fun reminderEnabledChanges(): Flow<Boolean> = changes(REMINDER_ENABLED_KEY) { reminderEnabled }

    private fun <T> changes(key: String, read: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key || changed == null) trySend(read())
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        const val FILE = "expiry_preferences"
        const val WINDOW_KEY = "expiry.windowDays"
        const val REMINDER_ENABLED_KEY = "reminder.enabled"
        const val CARD_DISMISSED_KEY = "reminder.cardDismissed"
        const val LAST_DIGEST_MONTH_KEY = "reminder.lastDigestMonth"
    }
}

class InMemoryExpiryPreferences : ExpiryPreferences {
    private val windowState = MutableStateFlow(ExpiryWindow.DEFAULT)
    private val reminderState = MutableStateFlow(false)

    override var window: ExpiryWindow
        get() = windowState.value
        set(value) {
            windowState.value = value
        }

    override var reminderEnabled: Boolean
        get() = reminderState.value
        set(value) {
            reminderState.value = value
        }

    override var reminderCardDismissed: Boolean = false
    override var lastDigestMonth: String? = null

    override fun windowChanges(): Flow<ExpiryWindow> = windowState

    override fun reminderEnabledChanges(): Flow<Boolean> = reminderState
}
