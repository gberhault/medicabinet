package com.medicabinet.app.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The four bottom-bar tabs. */
enum class AppTab(val route: String) {
    SCAN("scan"),
    CABINET("cabinet"),
    EXPIRING("expiring"),
    SETTINGS("settings")
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

    /** The main screen has switched to [tab]. A newer request for another tab is kept. */
    fun consume(tab: AppTab) {
        pending.compareAndSet(tab, null)
    }
}
