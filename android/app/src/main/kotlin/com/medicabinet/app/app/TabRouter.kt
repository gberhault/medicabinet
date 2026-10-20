package com.medicabinet.app.app

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The four bottom-bar tabs. */
enum class AppTab(val route: String) {
    SCAN("scan"),
    CABINET("cabinet"),
    EXPIRING("expiring"),
    SETTINGS("settings");

    companion object {
        /** The tab the app opens on: the cabinet, not the camera (which only starts once the Scan tab is shown). */
        val LAUNCH = CABINET
    }
}

/**
 * Lets code outside the screens (the notification tap handled by `MainActivity`) switch tab. A request made before
 * the main screen exists (a cold start from the notification) is kept until it is consumed.
 */
class TabRouter {
    private val pending = MutableStateFlow<AppTab?>(null)

    /** The tab someone asked for and the main screen has not shown yet. */
    val requested: StateFlow<AppTab?> = pending.asStateFlow()

    fun select(tab: AppTab) {
        pending.value = tab
    }

    /**
     * Applies the destination a notification tap put in [intent] ([EXTRA_DESTINATION]). Only a known tab is accepted:
     * the activity is exported, so the extra is treated as untrusted. Returns whether a tab was requested.
     */
    fun handle(intent: Intent?): Boolean {
        val tab = AppTab.entries.firstOrNull { it.route == intent?.getStringExtra(EXTRA_DESTINATION) } ?: return false
        select(tab)
        return true
    }

    /** The main screen has switched to [tab]. A newer request for another tab is kept. */
    fun consume(tab: AppTab) {
        pending.compareAndSet(tab, null)
    }

    companion object {
        /** The intent extra of the digest's tap target: the route of the tab to open. */
        const val EXTRA_DESTINATION = "destination"
    }
}
