package com.medicabinet.app.features.expiring

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.medicabinet.app.reminders.ReminderModel
import com.medicabinet.app.reminders.TurnOnResult
import com.medicabinet.app.reminders.openNotificationSettings

/** What the reminder card and the Settings switch can ask the system to do. */
class ReminderActions(val turnOn: () -> Unit, val openSettings: () -> Unit)

/**
 * **Turn on** asks the model; only when the model says the permission is missing (Android 13+) is the system prompt
 * shown, and its answer goes back to the model. Nothing prompts at launch or during a scan.
 */
@Composable
fun rememberReminderActions(model: ReminderModel): ReminderActions {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.onPermissionResult(granted)
    }
    return remember(model, launcher, context) {
        ReminderActions(
            turnOn = {
                if (model.turnOn() == TurnOnResult.NEEDS_PERMISSION) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            openSettings = { openNotificationSettings(context) }
        )
    }
}
