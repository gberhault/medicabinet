# Expiry tracking and reminders

Every box carries its expiry date, or an explicit "unknown". This feature turns those dates into a status per
box, an **Expiring** tab that lists what needs attention, and an optional monthly reminder. Spec:
[`specs/expiry-tracking/`](../specs/expiry-tracking/) (epic #22). Both apps behave the same; the rules are written
once as shared fixtures (see below).

MediCabinet is an inventory tool, not a medical device: the status says whether the date printed on a box has
passed, never whether a medicine is safe to take.

## The status of a box

| Status | When |
|--------|------|
| **Expired** | today is after the last valid day |
| **Expires today** (counted as expiring soon) | today is the last valid day — the box is still usable that day |
| **Expiring soon** | 0 to *window* days left, both ends included |
| **OK** | more than *window* days left |
| **Expiry unknown** | no date was ever read or entered — never shown, counted or sorted as OK |

- The **last valid day** is the printed day, or the last day of the month when only a month is known (GS1 `DD=00`),
  including 29 February in a leap year.
- **Today** is the calendar date on the phone, in its current time zone. A day or time-zone change (midnight,
  travel, a manual clock change) updates every status, the tab badge and the scheduled reminders.
- A phone clock outside 2000–2099 never crashes the rule: it only changes the answer.
- Boxes that were used up, thrown away or deleted have no status and appear nowhere on this screen.

### The "expiring soon" window

Settings → **Expiring soon**: 30, 60 or 90 days, **60** by default (`ExpiryWindow.default`). It is a preference of
the phone — not cabinet data — so it is not exported and not in the cabinet backup. A missing or unrecognised stored
value silently falls back to the default. The Cabinet list, box detail, Expiring tab, badge and reminders all follow
it as soon as it changes.

## The Expiring tab

Three sections, in this order; a section with no box is hidden, and OK boxes are never listed:

1. **Expired**, oldest expiry first.
2. **Expiring soon**, soonest first.
3. **Expiry unknown**, by name.

Ties break by name (the same folding as the Cabinet list and search: case, accents, `œ æ ß ’`), then by id, so the
list never reshuffles between refreshes. A row shows the name, the location, the expiry date in the user's locale
(*March 2027* for a month-only date — never an invented day) and the status as **text plus an icon**, not colour
alone. Rows do not open the box detail (that is the Cabinet tab).

- **Thrown away**: swipe a row, or use the accessibility action. The box is marked removed (*thrown away*) and the
  usual Undo banner appears. Nothing is removed without that banner.
- **Badge**: the tab shows the number of expired plus expiring-soon boxes (hidden at zero; unknown boxes are not
  counted), live on every tab, with an accessibility label.
- **Empty, loading, error**: "All clear — nothing expires in the next *N* days", a loading state, and an error state
  with **Retry**.
- The view follows the data (observation of the boxes in the cabinet) and the day; there is no manual refresh.

## The monthly reminder

Off by default. Offered by a card on the Expiring tab (**Turn on** / **Not now**; *Not now* is remembered) and by a
**Monthly reminder** switch in Settings. The system permission is asked only when the user turns it on; if it is
refused, or revoked later, the app says so and offers **Open Settings** — it never pretends the reminder is on.

- **When**: the 1st of each month at 09:00 local time (also after travelling to another time zone).
- **What**: two counts and the window only — for example "2 boxes expired · 3 boxes expire within 60 days" — never a
  medicine name, a location, a lot, a serial or a date (a lock screen is not a private place).
- **Counts** are computed as of the 1st, not of the delivery time, so a late notification says what an on-time one
  would have said. Boxes of unknown expiry are not counted; expired boxes still in the cabinet are counted every
  month until they are removed.
- **Nothing to say, nothing sent**: when both counts are zero no notification is delivered that month.
- **Tap**: opens the app on the Expiring tab.

### How each platform delivers it

| | iOS | Android |
|--|-----|---------|
| Mechanism | local notifications, scheduled ahead: the next 12 digests, each with its own counts | one inexact `AlarmManager.setWindow` alarm for the next digest day; the receiver computes the counts when it fires |
| Reschedule | when the cabinet, the window or the setting changes, when the app becomes active, and when the day or time zone changes (debounced, 1 s) | when the app starts or the setting changes, and on boot, app update, time-zone change and clock change |
| Limits | at most 12 pending (the system allows 64); triggers carry no time zone, so 09:00 stays local; if the app is never opened for a year, the reminders run out | delivered in the hour after 09:00, later under Doze or aggressive battery savers until the app is opened again; at most one digest per month |
| Permission | notification authorisation | `POST_NOTIFICATIONS` (Android 13+); `RECEIVE_BOOT_COMPLETED` to re-arm after a reboot |

Android uses an inexact alarm rather than WorkManager on purpose: WorkManager's manifest adds `WAKE_LOCK`,
`ACCESS_NETWORK_STATE` and `FOREGROUND_SERVICE`, which the app does not need and the privacy rules forbid; exact
alarms need a special permission and are not justified for a monthly summary.

## Privacy

- No new network access, analytics or third-party library.
- Notifications carry counts only. Cabinet contents are never logged (failure kinds only).
- Preferences (`expiry.windowDays`, `reminder.enabled`, `reminder.cardDismissed`, and on Android
  `reminder.lastDigestMonth`) live in `UserDefaults` / `SharedPreferences`; they are not exported and not in
  Android Auto Backup, so a restored phone starts with the default window and reminders off.
- iOS keeps `CompleteUntilFirstUserAuthentication` and never reads the cabinet in the background; the Android
  receiver reads it only after the first unlock.

## Where the code is

| What | iOS | Android |
|------|-----|---------|
| Status, window, overview, digest, calendar day (pure, tested by the shared fixtures) | `MediCabinetCore/Expiry/` | `:core` `com.medicabinet.core.expiry` |
| Expiring tab | `Features/Expiring/` | `features/expiring/` |
| Preferences, today, tab routing | `App/` | `app/` |
| Reminder permission, scheduling, delivery | `App/Reminders/` (only place importing `UserNotifications`) | `reminders/` (only place using the alarm and notification APIs) |

Shared fixtures (run by both cores; a wrong value fails both suites): `fixtures/expiry-status.json` (status,
days left, and how an instant plus a time zone becomes "today"), `fixtures/expiring-overview.json` (sections, order,
tie-breaks, badge) and `fixtures/expiry-digest.json` (counts per digest day, next and due digest days). Their
expected values were computed independently with Python's `datetime` and `zoneinfo`.

## Testing it

```bash
swift test --package-path ios/Packages/MediCabinetCore   # status, overview, digest on the desktop
cd android && ./gradlew :core:test                       # the same fixtures on the JVM
bash scripts/local-ci.sh                                 # everything, as before a PR
```

To see a reminder without waiting for the 1st: turn the reminder on, move the phone's date to the last day of a
month, and open the app — iOS reschedules on becoming active; on Android the next digest is armed for the 1st at
09:00 (move the clock past it to trigger the alarm).
