// Fixture: alarm and notification APIs outside .../reminders/ trigger alarms_and_notifications_confined_to_reminders.
import android.app.AlarmManager
import androidx.core.app.NotificationManagerCompat

class Reminds(private val alarms: AlarmManager, private val notifications: NotificationManagerCompat)
