package com.medicabinet.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.app.features.expiring.Notice
import com.medicabinet.app.features.expiring.ReminderActions
import com.medicabinet.app.reminders.ReminderModel
import com.medicabinet.app.reminders.ReminderState
import com.medicabinet.core.expiry.ExpiryWindow

/**
 * "Expiring soon": how many days ahead a box counts as expiring soon (30, 60 or 90). Saved at once; the Cabinet, the
 * Expiring tab, the badge and the reminder follow the new value. Device-only: never exported.
 */
@Composable
fun ExpiryWindowSetting(preferences: ExpiryPreferences, modifier: Modifier = Modifier) {
    val changes = remember(preferences) { preferences.windowChanges() }
    val selected by changes.collectAsState(preferences.window)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_expiryWindow_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Column(Modifier.selectableGroup()) {
            ExpiryWindow.entries.forEach { option ->
                WindowOption(option, option == selected) { preferences.window = option }
            }
        }
        Text(
            stringResource(R.string.settings_expiryWindow_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The **Monthly reminder** switch, off by default. Turning it on is the only thing that can show the system
 * permission prompt. While the system blocks notifications the switch is not shown as on; the reason is said in words
 * with **Open Settings**.
 */
@Composable
fun ReminderSetting(model: ReminderModel, actions: ReminderActions, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val notice by model.notice.collectAsState()
    val on = state == ReminderState.ON
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .toggleable(
                    value = on,
                    role = Role.Switch,
                    onValueChange = { if (it) actions.turnOn() else model.turnOff() }
                )
                .testTag("settings.reminder.switch"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.reminder_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            Switch(checked = on, onCheckedChange = null)
        }
        Text(
            stringResource(R.string.reminder_settings_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Notice(notice, actions, tag = "settings.reminder")
    }
}

/** "Expiring soon" window and the monthly reminder: the expiry settings of the Settings screen. */
@Composable
fun ExpirySettingsSection(
    preferences: ExpiryPreferences,
    model: ReminderModel,
    actions: ReminderActions,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        ExpiryWindowSetting(preferences)
        ReminderSetting(model, actions)
    }
}

@Composable
private fun WindowOption(option: ExpiryWindow, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("settings.expiryWindow.${option.days}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            stringResource(R.string.settings_expiryWindow_option, option.days),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
