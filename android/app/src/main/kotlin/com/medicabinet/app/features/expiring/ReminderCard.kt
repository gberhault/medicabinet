package com.medicabinet.app.features.expiring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.reminders.ReminderModel
import com.medicabinet.app.reminders.ReminderNotice
import com.medicabinet.app.reminders.ReminderState

/** The words explaining why the reminder cannot work, or `null` when nothing is wrong. */
internal fun reminderNoticeText(notice: ReminderNotice): Int? = when (notice) {
    ReminderNotice.NONE -> null
    ReminderNotice.BLOCKED -> R.string.reminder_settings_blocked
    ReminderNotice.DENIED -> R.string.reminder_settings_denied
}

/**
 * The monthly-reminder offer above the Expiring list: **Turn on** or **Not now** (remembered). When the system no
 * longer allows notifications for a reminder that was on, it says so with **Open Settings** instead. Nothing is shown
 * once the reminder is on, or off with the offer dismissed.
 */
@Composable
fun ReminderCard(model: ReminderModel, actions: ReminderActions, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val offers by model.offersCard.collectAsState()
    val notice by model.notice.collectAsState()
    if (state == ReminderState.BLOCKED) {
        CardFrame(modifier.testTag("reminder.card.blocked")) {
            Notice(notice, actions)
        }
    } else if (offers) {
        CardFrame(modifier.testTag("reminder.card")) {
            Text(stringResource(R.string.reminder_card_message), style = MaterialTheme.typography.bodyMedium)
            Notice(notice, actions)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(
                    onClick = model::dismissCard,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reminder.card.notNow")
                ) { Text(stringResource(R.string.reminder_card_notNow)) }
                Button(
                    onClick = actions.turnOn,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reminder.card.turnOn")
                ) { Text(stringResource(R.string.reminder_card_turnOn)) }
            }
        }
    }
}

@Composable
private fun CardFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.reminder_card_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            content()
        }
    }
}

/** The explanation and **Open Settings**, shown while [notice] says something is wrong. */
@Composable
internal fun Notice(notice: ReminderNotice, actions: ReminderActions, tag: String = "reminder.card") {
    val words = reminderNoticeText(notice) ?: return
    Text(stringResource(words), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(
        onClick = actions.openSettings,
        modifier = Modifier.heightIn(min = 48.dp).testTag("$tag.openSettings")
    ) { Text(stringResource(R.string.reminder_settings_openSettings)) }
}
