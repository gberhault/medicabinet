# Expiry tracking and notifications — Design

Status: **Approved 2026-10-19**
Epic: #22 · Requirements: [`requirements.md`](requirements.md) · Platforms: iOS + Android

The rules (status, sorting, digest content and dates) are written **once as shared
fixtures** and implemented **twice in the cores**. The apps add the screen, the settings, the
"today" source and the platform notification scheduler. Section numbers are referenced from
`tasks.md`.

## 1. Key decisions

### 1.1 Android scheduler: inexact `AlarmManager`, not WorkManager — **changes requirements decision 5**

Requirements decision 5 named WorkManager and left its cost to this design (open point 2).
Checking it changed the answer:

| Option | Permissions added to the merged manifest | Size / dependency | Fit for "1st of the month, 09:00" | Verdict |
|--------|------------------------------------------|-------------------|-----------------------------------|---------|
| WorkManager one-time work with an initial delay | `RECEIVE_BOOT_COMPLETED`, **`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`** (declared by `work-runtime`'s own manifest) | New AndroidX library + Room-backed internal database | Delay computed once; a time-zone change still needs our own re-enqueue | Rejected: breaks X1 ("only `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`") unless three permissions are stripped with `tools:node="remove"`, which WorkManager does not support |
| `AlarmManager.setExact*` | `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | none | Exact | Rejected (Play policy; the CLAUDE.md "no exact alarms" rule) |
| **`AlarmManager.setWindow` (inexact, `RTC_WAKEUP`, 1 h window) + our receivers** | `RECEIVE_BOOT_COMPLETED` only | **none** (platform API) | Delivered in the hour after 09:00, later under Doze; the late-run rule of §4.4 covers it | **Chosen** |

Consequences: no new dependency, no APK size change; we re-arm the alarm ourselves on
boot, app update, time-zone change and clock change (§6.4). **Your approval of this design
approves this change**; `requirements.md` decision 5 is then amended to say "an inexact
platform alarm" in the same commit.

### 1.2 "Today" is a calendar day, computed at the edge

The core never sees a time zone in its rules. `CalendarDay` (year, month, day) is derived
once from the injected clock and the injected time zone (`CalendarDay.from(_:timeZone:)`),
then every rule works on calendar days with integer day arithmetic (days since 1970-01-01,
the same civil-calendar algorithm on both platforms). This makes the fixtures time-zone
free and the result identical on both platforms. A small fixture section proves the
`instant + zone → day` conversion separately (§2.1).

### 1.3 iOS precomputes, Android computes at fire time — same function, same answer

iOS local notifications carry their text when scheduled, so iOS computes the next 12 digests
in advance and reschedules when anything they depend on changes. Android's alarm runs our
code, so Android computes the counts when it fires. Both call
`ExpiryDigest.counts(boxes:on:window:)` with the **digest date** (never the delivery time),
so a late Android digest shows the same counts as the iPhone (N6).

### 1.4 Cabinet changes are observed, not polled

E7 (the view updates without a manual refresh) and N11 (iOS reschedules on every stock
change) need a change signal. `BoxRepository` gains one observation method returning the
active boxes every time they change. iOS: GRDB `ValueObservation`. Android: the
`CabinetDatabase.transaction` helper bumps an in-process `MutableStateFlow` after each commit,
so every writer — today's repositories, #12's tombstones, #29's import — signals without
remembering to.

### 1.5 Preferences on the platform key-value store

Same pattern as bulk scan (#12 design §1.4): `UserDefaults` on iOS and `SharedPreferences`
named `expiry_preferences` on Android, same keys (§5). They are device preferences: not
exported (W3), and not in Android Auto Backup (its rules include only the cabinet and its
backups), so a restored phone starts with the defaults and reminders off. iOS
`PrivacyInfo.xcprivacy` needs `NSPrivacyAccessedAPICategoryUserDefaults` / `CA92.1`, which
#12 adds; this epic checks it is there.

### 1.6 Name sorting is defined in the core, not by the platform collator

E2 sorts unknown boxes "by name, locale-aware". ICU (Foundation) and `java.text.Collator`
disagree on edge cases, which would break the shared fixture. The core sorts on a **folded
name** — lower-cased, diacritics removed (Swift `folding(options: [.caseInsensitive,
.diacriticInsensitive], locale: nil)`, Kotlin NFD + strip `\p{Mn}` + `lowercase(Locale.ROOT)`)
— compared by Unicode scalars, then by the id in lower-case. It puts "Éfferalgan" next to
"efferalgan" and is identical on both platforms, which is what a French household
expects. Proper locale collation is not worth a cross-platform mismatch.

## 2. Shared fixtures

All follow the existing shape (`formatVersion`, `description`, named `cases`) and are
checked by `check-fixtures.py`. Dates are `"YYYY-MM-DD"` strings for calendar days and
`{year, month, day|null}` for expiries (as in `gs1-datamatrix.json`).

### 2.1 `fixtures/expiry-status.json` (S)

```json
{ "name": "the expiry day itself is expiring soon, 0 days left",
  "today": "2027-03-15", "expiry": { "year": 2027, "month": 3, "day": 15 }, "windowDays": 60,
  "expect": { "status": "expiringSoon", "daysLeft": 0 } }
{ "name": "month precision February of a leap year runs to the 29th",
  "today": "2028-03-01", "expiry": { "year": 2028, "month": 2, "day": null }, "windowDays": 30,
  "expect": { "status": "expired", "daysLeft": -1 } }
{ "name": "no date is unknown, whatever the window",
  "today": "2027-03-15", "expiry": null, "windowDays": 90,
  "expect": { "status": "unknown", "daysLeft": null } }
```

A second list, `todayCases`, proves the conversion of §1.2:

```json
{ "name": "23:30 UTC on 31 March is already 1 April in Paris",
  "instant": "2027-03-31T23:30:00Z", "timeZone": "Europe/Paris", "expect": "2027-04-01" }
```

Cases (S11): the day before / on / after the expiry; the window's last day and the day after,
for 30, 60 and 90; month precision in a 30-day, a 31-day, a leap and a common February; a
year end (`2027-12-31` → `2028-01-01`); a window crossing a year end and a leap day; unknown
expiry; far future (`2099-12-31`). `todayCases`: UTC±0, Paris summer and winter, a zone
ahead (`Pacific/Auckland`) and behind (`America/Los_Angeles`) on either side of midnight, a
DST transition day. IANA zone names only (supported by both Foundation and `java.time`).

### 2.2 `fixtures/expiring-overview.json` (E1, E2, E8)

```json
{ "name": "sections, order and badge",
  "today": "2027-03-15", "windowDays": 60,
  "boxes": [
    { "id": "00000000-0000-0000-0000-000000000003", "name": "Smecta", "expiry": { "year": 2027, "month": 1, "day": null }, "inCabinet": true },
    { "id": "00000000-0000-0000-0000-000000000001", "name": "éfferalgan", "expiry": null, "inCabinet": true },
    { "id": "00000000-0000-0000-0000-000000000002", "name": "Doliprane", "expiry": { "year": 2027, "month": 4, "day": 2 }, "inCabinet": true },
    { "id": "00000000-0000-0000-0000-000000000004", "name": "Spasfon", "expiry": { "year": 2026, "month": 12, "day": 1 }, "inCabinet": false }
  ],
  "expect": { "expired": ["…003"], "expiringSoon": ["…002"], "unknown": ["…001"], "badge": 2 } }
```

(The `…` ids are written in full in the file.) Cases: each section empty; ties on expiry
broken by folded name then id; accents and case in names (`Éfferalgan` / `efferalgan` /
`Efferalgan`); an OK box never listed; a removed box never listed; unknown boxes do not
count towards the badge.

### 2.3 `fixtures/expiry-digest.json` (N5–N8, N10, N12, N14)

Two lists.

`countCases` — content for one digest date:

```json
{ "name": "a box expiring exactly on the digest date counts as expiring soon",
  "digestDay": "2027-04-01", "windowDays": 60,
  "boxes": [ { "expiry": { "year": 2027, "month": 4, "day": 1 }, "inCabinet": true } ],
  "expect": { "expired": 0, "expiringSoon": 1 } }
{ "name": "only unknown boxes: nothing to send",
  "digestDay": "2027-04-01", "windowDays": 60,
  "boxes": [ { "expiry": null, "inCabinet": true } ],
  "expect": null }
```

`scheduleCases` — which digest dates come next, and which one is due:

```json
{ "name": "on the 1st before 09:00 today's digest is still to come",
  "today": "2027-04-01", "minutesSinceMidnight": 539, "count": 3,
  "expect": { "upcoming": ["2027-04-01", "2027-05-01", "2027-06-01"], "due": "2027-03-01" } }
{ "name": "on the 1st at 09:00 today's digest is due",
  "today": "2027-04-01", "minutesSinceMidnight": 540, "count": 2,
  "expect": { "upcoming": ["2027-05-01", "2027-06-01"], "due": "2027-04-01" } }
```

Cases: a box entering the window between two digests (counted by the second one only); an
expired box counted every month while in the cabinet; a removed box never counted; a
December → January rollover; `count` 12; the time-zone case is covered by `todayCases`
(§2.1), because digest dates are computed from local `today` and minutes.

### 2.4 Repository contract and README

- `fixtures/repository-contract.json` gains the observation checks of §4.5.
- `fixtures/README.md` lists the three new files and the cross-platform names: digest hour
  `09:00`, digest day `1`, window choices `30`, `60`, `90` (default `60`), preference keys
  (§5).
- `scripts/tests/test-shared-fixtures-break.sh` gains a broken copy of each new fixture.

## 3. Core (both platforms)

New files in `MediCabinetCore` (`Sources/MediCabinetCore/Expiry/`) and `:core`
(`com.medicabinet.core.expiry`). Same names on both; Kotlin uses `Instant`, `ZoneId`, `Flow`
where Swift uses `Date`, `TimeZone`, `AsyncThrowingStream`.

### 3.1 Calendar day (§1.2)

```swift
public struct CalendarDay: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int, month: Int, day: Int
    public static func make(year: Int, month: Int, day: Int) throws(ExpiryDateError) -> CalendarDay
    public static func from(_ instant: Date, timeZone: TimeZone) -> CalendarDay   // Gregorian
    public static func parse(_ iso: String) -> CalendarDay?                        // "YYYY-MM-DD", fixtures
    public var epochDay: Int                       // days since 1970-01-01, civil algorithm
    public func adding(days: Int) -> CalendarDay
    public func firstOfNextMonth() -> CalendarDay
    public var description: String                 // "YYYY-MM-DD"
}

extension ExpiryDate {
    public var lastValidCalendarDay: CalendarDay  // (year, month, lastValidDay)
}
```

`make` reuses the `ExpiryDate` validation (2000–2099, month, day in month). `from` is the
only function that reads a calendar; it uses a Gregorian calendar with the given time zone
(Kotlin: `instant.atZone(zone).toLocalDate()`).

### 3.2 Window and status (S, W)

```swift
public enum ExpiryWindow: Int, CaseIterable, Sendable {      // Kotlin: enum class ExpiryWindow(val days: Int)
    case days30 = 30, days60 = 60, days90 = 90
    public static let `default`: ExpiryWindow = .days60
    /// Missing or unknown stored values fall back to the default (W4).
    public init(storedDays: Int?)
}

public struct ExpiryAssessment: Hashable, Sendable {
    public let status: ExpiryStatus
    public let daysLeft: Int?                      // nil only for .unknown
    public var expiresToday: Bool { daysLeft == 0 }   // S5 label
}

public enum ExpiryRule {
    public static func assess(_ expiry: ExpiryDate?, today: CalendarDay, window: ExpiryWindow) -> ExpiryAssessment
}
```

`daysLeft = expiry.lastValidCalendarDay.epochDay - today.epochDay`; `< 0` expired, `0…window`
expiring soon, `> window` OK, no expiry unknown. `ExpiryStatus` (already in the model) is
unchanged.

### 3.3 Expiring overview (E1, E2, E8)

```swift
public struct ExpiringEntry: Hashable, Sendable {
    public let box: Box
    public let assessment: ExpiryAssessment
}

public struct ExpiringOverview: Hashable, Sendable {
    public let expired: [ExpiringEntry]           // oldest expiry first
    public let expiringSoon: [ExpiringEntry]      // soonest first
    public let unknown: [ExpiringEntry]           // folded name, then id
    public var attentionCount: Int { expired.count + expiringSoon.count }   // badge
    public var isEmpty: Bool                       // E5: all three empty
    public static func make(boxes: [Box], today: CalendarDay, window: ExpiryWindow) -> ExpiringOverview
}

public enum NameOrder {                            // §1.6
    public static func folded(_ name: String) -> String
    public static func precedes(_ a: Box, _ b: Box) -> Bool   // folded name, then id lower-cased
}
```

`make` ignores boxes not in the cabinet (`removal != nil`; after #12 the repository already
hides deleted boxes) and OK boxes. Ties on expiry use `NameOrder.precedes`.

### 3.4 Digest (N)

```swift
public struct DigestCounts: Hashable, Sendable {
    public let expired: Int
    public let expiringSoon: Int
}

public enum ExpiryDigest {
    public static let dayOfMonth = 1
    public static let minutesSinceMidnight = 9 * 60          // 09:00 local
    public static let scheduledAhead = 12                    // iOS pending digests (N11)
    /// nil when both counts are zero (N7). Unknown and removed boxes are not counted (N8).
    public static func counts(boxes: [Box], on digestDay: CalendarDay, window: ExpiryWindow) -> DigestCounts?
    /// The next `count` digest days strictly after now (today at `minutes`).
    public static func upcoming(today: CalendarDay, minutes: Int, count: Int) -> [CalendarDay]
    /// The most recent digest day at or before now — what a late Android alarm delivers (N12).
    public static func due(today: CalendarDay, minutes: Int) -> CalendarDay
}
```

### 3.5 Change observation port (§1.4)

```swift
extension BoxRepository {   // added to the protocol
    /// Emits the active boxes now, then after every committed change. Never emits for a rolled-back write.
    func activeBoxesUpdates() -> AsyncThrowingStream<[Box], any Error>
}
```

Kotlin: `fun observeActiveBoxes(): Flow<List<Box>>` (`kotlinx-coroutines-core`, already a
`:core` dependency). The in-memory fakes (`InMemoryCabinetStore`, Kotlin fake) emit after
each mutation.

## 4. Behaviour shared by both apps

### 4.1 Today source

`TodaySource` (protocol / interface in the app layer) emits the current `CalendarDay` now
and whenever it changes: at local midnight, on a time-zone change and on a manual clock
change (S9). It is built from the injected `Clock` and a time-zone provider; tests drive it
with a `MutableClock` and an injected zone.

### 4.2 Expiring view model (E)

Inputs: `activeBoxesUpdates()`, `TodaySource`, the window preference. Each emission of any
of them recomputes `ExpiringOverview.make` off the main thread and publishes:
`loading` → `content(overview, window)` / `empty(window)` / `failed` (with **Retry**, which
re-subscribes). **Thrown away**: `markRemoved(id, .thrownAway, clock.now)`, then a
confirmation with **Undo** → `undoRemoval(id)`; an error shows a message and leaves the box
as it was. The badge reads `overview.attentionCount` from the same model, so tab and list
never disagree.

### 4.3 Reminder setting (N1–N4, N13)

`ReminderState`: `off`, `on`, `blocked` (the user turned reminders on but the system does
not allow notifications). Stored flag `reminder.enabled` plus the live system authorisation
give the state, re-read every time the app comes to the foreground (N4). Turning on:
not-determined → system prompt → granted: `on` + schedule / denied: `off` + explanation +
**Open Settings**. Turning off: `off` + cancel everything (N13). The Expiring card (N2)
shows while `reminder.enabled` is false and `reminder.cardDismissed` is false.

### 4.4 Digest delivery rule

- Content: `ExpiryDigest.counts(activeBoxes, on: digestDay, window)`; `nil` → nothing is
  shown for that month.
- Text: title `reminder.digest.title`; body one of `reminder.digest.body.expired`,
  `.soon`, `.both`, with plural forms and the window in days. No name, location, lot,
  serial or date (N6).
- Tap: opens the app on the Expiring tab (N9).

## 5. Preferences (both platforms, same keys)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `expiry.windowDays` | Int | 60 | W1; read through `ExpiryWindow(storedDays:)` |
| `reminder.enabled` | Bool | false | the user's choice (N1) |
| `reminder.cardDismissed` | Bool | false | **Not now** on the Expiring card (N2) |
| `reminder.lastDigestMonth` | String `YYYY-MM` | — | Android only: last month delivered or skipped (N12) |

Behind an `ExpiryPreferences` protocol / interface with a live and an in-memory
implementation, exposing the window and the reminder flag as a stream so views and the
scheduler react to changes (W2).

## 6. iOS app

```
ios/MediCabinet/Sources/Features/Expiring/
  ExpiringView.swift            # sections, empty / loading / error, reminder card
  ExpiringViewModel.swift       # @MainActor @Observable (§4.2)
  ExpiringRow.swift             # name, location, date, status label + SF Symbol
  ReminderCard.swift            # N2
  ExpiryText.swift              # date and status wording (month-year for month precision)
ios/MediCabinet/Sources/Features/Settings/
  ExpirySettingsSection.swift   # window Picker (W1, W5) + reminder Toggle (N2–N4)
ios/MediCabinet/Sources/App/
  ExpiryPreferences.swift       # UserDefaults (protocol + live + in-memory)
  TodaySource.swift             # NSCalendarDayChanged, NSSystemTimeZoneDidChange,
                                # UIApplication.significantTimeChangeNotification
  TabRouter.swift               # @Observable selected tab, used by MainTabView and N9
  Reminders/
    ReminderScheduler.swift     # actor: debounced reschedule (§6.2)
    NotificationClient.swift    # protocol over UNUserNotificationCenter + live + fake
    ReminderModel.swift         # @MainActor ReminderState (§4.3)
```

### 6.1 Screen

- `MainTabView` replaces the Expiring placeholder with `ExpiringView`, binds a
  `TabRouter` selection, and adds `.badge(attentionCount)` (hidden at 0) with an
  accessibility label (E8).
- `List` with `Section`s; month-precision dates use `Date.FormatStyle` with `.month(.wide)
  .year()`, day dates `.dateTime.day().month(.wide).year()` (E4). Status labels with
  `Label(_, systemImage:)`: expired `xmark.octagon`, expiring soon `exclamationmark.triangle`,
  unknown `questionmark.circle` (E3).
- **Thrown away**: `.swipeActions` and `.accessibilityAction(named:)` (E9); the Undo
  confirmation is a bottom overlay announced with `AccessibilityNotification.Announcement`.

### 6.2 Scheduler (N11, N13, N14)

- `ReminderScheduler` (actor) reschedules when it is told to by: cabinet updates,
  preference changes, `scenePhase == .active`, `TodaySource` emissions. Requests are
  debounced (named constant 1 s) so a bulk-scan session does not reschedule per box.
- A reschedule: read the authorisation → not authorised: remove all pending digests →
  done. Otherwise remove pending requests whose id starts with `expiry-digest-`, compute
  `ExpiryDigest.upcoming(count: 12)`, and for each day with non-nil counts add a
  `UNNotificationRequest` id `expiry-digest-YYYY-MM`, `UNCalendarNotificationTrigger`
  (`year, month, day: 1, hour: 9, minute: 0`, no time zone → floating, so it stays 09:00
  local after travel), `repeats: false`, default interruption level and sound, no app-icon
  badge (N5, N7, N11).
- Never more than 12 pending from this app; the 64 cap is never approached.
- Foreground presentation (`willPresent`): banner + list. Tap (`didReceive`):
  `TabRouter.select(.expiring)` (N9). The delegate is set at launch before the scene appears.
- `import UserNotifications` only under `App/Reminders/` (new SwiftLint custom rule +
  fixture), like the scanner rule of #12.

## 7. Android app

```
android/app/src/main/kotlin/com/medicabinet/app/features/expiring/
  ExpiringScreen.kt  ExpiringViewModel.kt  ExpiringRow.kt  ReminderCard.kt  ExpiryText.kt
android/app/src/main/kotlin/com/medicabinet/app/features/settings/
  ExpirySettings.kt             # window radio group + reminder Switch
android/app/src/main/kotlin/com/medicabinet/app/app/
  ExpiryPreferences.kt  TodaySource.kt  TabRouter.kt
android/app/src/main/kotlin/com/medicabinet/app/reminders/
  ReminderScheduler.kt          # AlarmManager.setWindow, PendingIntent (immutable)
  DigestAlarmReceiver.kt        # goAsync: compute + post + re-arm (§7.3)
  RescheduleReceiver.kt         # BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED, TIME_SET
  DigestNotifier.kt             # channel + NotificationCompat
  ReminderModel.kt              # ReminderState (§4.3), permission
```

### 7.1 Screen

- `MainScaffold`'s Expiring destination shows `ExpiringScreen`; the `NavigationBarItem`
  icon is wrapped in `BadgedBox` with a content description for the count (E8).
- `LazyColumn` with sticky section headers. Day dates:
  `DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)`; month precision: the locale's
  best pattern for the skeleton `"MMMMyyyy"` (`DateFormat.getBestDateTimePattern`), never a
  hand-written pattern (E4). Icons: `Icons.Outlined.Block`, `Warning`, `HelpOutline`, each
  with a text label (E3).
- **Thrown away**: `SwipeToDismissBox` plus a `CustomAccessibilityAction` (E9); Undo via the
  `SnackbarHost` (`LiveRegion` polite).

### 7.2 Permission and channel (N3, N4)

- Manifest: `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`; receivers
  `android:exported="false"` (system broadcasts still reach them).
- API 33+: `ActivityResultContracts.RequestPermission(POST_NOTIFICATIONS)`; denied with no
  rationale → **Open Settings** (`Settings.ACTION_APP_NOTIFICATION_SETTINGS`). API 26–32: no
  runtime prompt; `blocked` when `NotificationManagerCompat.areNotificationsEnabled()` is
  false or the channel's importance is `NONE`.
- Channel `expiry_reminders` (localised name and description), default importance, created at
  app start (idempotent).

### 7.3 Alarm and receivers (N12–N14)

- `ReminderScheduler.arm()`: if reminders are not `on`, cancel and return. Otherwise compute
  `due = ExpiryDigest.due(today, minutes)`; if `due`'s month ≠ `reminder.lastDigestMonth`
  and `due` is this month, the target is **now** (a missed digest), else the next
  `ExpiryDigest.upcoming(count: 1)` at 09:00 in the current zone; `setWindow(RTC_WAKEUP,
  target, 1 h, pendingIntent)`. One `PendingIntent` (request code constant), so arming twice
  replaces it.
- `DigestAlarmReceiver.onReceive`: `goAsync()`, on an injected IO dispatcher: run the existing
  `Bootstrap` (same backup/migration rules; a failure logs its kind and re-arms), compute
  `due`; if already delivered (`lastDigestMonth`), re-arm and stop; else
  `counts(activeBoxes, due, window)` → post if non-nil (and notifications allowed) → write
  `lastDigestMonth` → re-arm. Must finish well inside the 10 s receiver budget (1 000 boxes:
  one indexed query).
- `RescheduleReceiver` calls `arm()` for boot, app update, time-zone change and clock change;
  the app also calls `arm()` at start, on foreground and on every reminder or window change.
- Tap: `PendingIntent` to `MainActivity` with extra `destination=expiring` (immutable,
  `FLAG_UPDATE_CURRENT`); `MainActivity` forwards it to `TabRouter` in `onCreate` and
  `onNewIntent` (N9).
- Architecture rule: `android.app.AlarmManager`, `NotificationManager*` and
  `NotificationCompat` only under `app/.../reminders/` (+ fixture). `PrivacyTest` changes to
  "exactly `CAMERA` (#12), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`; never
  `INTERNET` or an exact-alarm permission".

### 7.4 Today source

`TodaySource` combines a coroutine that waits until the next local midnight (injected clock
and zone, `delay` → testable with `runTest`) and a runtime receiver for
`TIMEZONE_CHANGED` / `TIME_SET` registered while the process is started.

## 8. Flows

1. **Open Expiring** — subscribe (boxes, today, window) → `loading` → overview off the main
   thread → sections or empty state (window named) → badge updated.
2. **Throw away** — swipe or accessibility action → `markRemoved(thrownAway, now)` →
   observation emits → row disappears, badge −1 → Undo → `undoRemoval` → row back.
3. **Midnight passes** — `TodaySource` emits → overview recomputed (a box moves from
   expiring soon to expired) → iOS reschedules.
4. **Turn reminders on (iOS)** — card or Settings → `requestAuthorization([.alert, .sound])`
   → granted → `reminder.enabled = true` → `ReminderScheduler` writes ≤ 12 requests.
5. **Turn reminders on (Android 13+)** — permission prompt → granted → enabled → `arm()`.
6. **Digest fires (Android)** — alarm in [09:00, 10:00] (later under Doze) → receiver →
   `due` → counts for `due` → notification → `lastDigestMonth` → next alarm.
7. **Permission revoked** — foreground → state `blocked` → Settings and the card show
   **Open Settings**; iOS removes pending requests, Android cancels the alarm.
8. **Travel** — time-zone change → today recomputed; iOS triggers are floating (still
   09:00 local); Android `RescheduleReceiver` re-arms in the new zone.

## 9. Performance

- `ExpiringOverview.make` + `ExpiryDigest.counts` × 12 on 1 000 boxes: budget **≤ 50 ms**
  (≥ 3× the expected cost), median of 10 runs, in a core test on both platforms.
- Observation → overview on screen with 1 000 boxes: computed off the main actor /
  dispatcher; the list is lazy (`List` / `LazyColumn` with stable ids) — checked against the
  "1 000 items scroll without dropped frames" target in the device measurement task.
- iOS reschedule: debounced, inside an actor, never on the main actor. Android receiver:
  one indexed query (`box_active`), IO dispatcher, under 1 s measured in a Robolectric test.
- No scan-path change. No new index needed (`box_active`, `box_expiry` exist).

## 10. Privacy impact

| Item | iOS | Android |
|------|-----|---------|
| New permission | notification authorisation (no usage string needed) | `POST_NOTIFICATIONS` (13+), `RECEIVE_BOOT_COMPLETED` |
| Notification content | two counts and the window, no names or dates | same |
| Privacy manifest | `UserDefaults` / `CA92.1` (from #12, asserted) | — |
| Data collected / sent | none | none (no WorkManager, no new dependency) |
| Logging | failure kinds only | `AppLog` failure kinds only |
| Stored | four preferences, not exported | same, not in Auto Backup |
| Data protection | unchanged; iOS never reads the cabinet in the background | the receiver reads it after the first unlock (credential-encrypted storage, not direct-boot aware) |

Store labels stay "no data collected".

## 11. Test strategy

| Req | Test (iOS / Android) | Kind |
|-----|----------------------|------|
| S1–S8, S11 | `ExpiryRuleFixtureTests` / `ExpiryRuleFixtureTest`; `CalendarDayTests` (epoch day round trip 2000–2099, `adding`, `firstOfNextMonth`) | core, shared |
| S9 | `CalendarDay.from` via `todayCases`; `TodaySource` tests (midnight, zone change, clock set back) | core + unit |
| S10, E1, E2, E8 | `ExpiringOverviewFixtureTests`; `NameOrderTests` | core, shared |
| W1, W4 | `ExpiryWindow(storedDays:)` tests; preferences round trip and bad values | core + unit |
| W2, W5 | view-model tests (window change recomputes); Settings UI test | unit + UI |
| W3 | export not touched (no archive code yet); preference keys not in any archive fixture — re-checked by #27 | review |
| E3–E6 | `ExpiringViewModelTests` (loading / content / empty / failed / retry); `ExpiringUITests` / Robolectric `ExpiringScreenTest` (labels, icons, month-year text in fr and en) | unit + UI |
| E7 | view-model test with the fake's observation: insert / remove / undo elsewhere updates the list | unit |
| E9 | Thrown away + Undo via swipe and accessibility action; error leaves the box | unit + UI |
| E10 | rows have no navigation (UI test) | UI |
| N1–N4, N13 | `ReminderModelTests` with a fake notification client / fake permission (not determined, granted, denied, revoked) | unit |
| N5–N8, N10 | `ExpiryDigestFixtureTests` (`countCases`, `scheduleCases`) | core, shared |
| N11, N14 | `ReminderSchedulerTests` with `NotificationClient` fake: 12 requests max, ids, floating triggers, empty months skipped, debounce, unauthorised removes all | unit |
| N12, N14 | `ReminderSchedulerTest` (Robolectric `ShadowAlarmManager`): window, target time per zone, missed digest now, re-arm on each broadcast; `DigestAlarmReceiverTest`: due month, no duplicate, counts for the due day, bootstrap failure re-arms | unit |
| N9 | tap routing test (iOS delegate → router; Android intent extra → destination) | unit |
| N15, X5 | `ios-check-strings.sh`, Android string checks, `check-string-parity.py` | gate |
| X1 | `PrivacyTest` exact permission set; privacy-manifest test; notification text never contains a box name (assertion on built content); lint rules | unit + gate |
| X3 | §9 timing assertions | perf |
| X4 | UI tests at the largest accessibility size / font scale 2.0, labels present | UI |

## 12. i18n impact

New keys on both platforms (fr = source, en), same wording:
`expiring.title`, `expiring.section.{expired,expiringSoon,unknown}`,
`expiring.status.{expired,expiresToday,expiringSoon,unknown}`,
`expiring.row.accessibility` (name, status, date, location), `expiring.empty` (window, plural),
`expiring.loading`, `expiring.error`, `expiring.retry`, `expiring.badge.accessibility` (plural),
`expiring.thrownAway`, `expiring.thrownAway.done`, `expiring.undo`, `expiring.thrownAway.failed`,
`reminder.card.{title,message,turnOn,notNow}`,
`reminder.settings.{title,footer,blocked,openSettings,denied}`,
`reminder.digest.title`, `reminder.digest.body.{expired,soon,both}` (plural),
`reminder.channel.{name,description}` (Android only),
`settings.expiryWindow.{title,footer,option}` (option: "%d days", plural).
Placeholder `placeholder.expiring.*` keys are removed on both platforms.

## 13. Risks

- **Dependency on #12** (schema v2 tombstones, `PrivacyInfo` UserDefaults reason, version
  0.2.0): app tasks wait for it on `develop` (tasks T9). If #12 slips, the core tasks still
  land; the epic PR waits.
- **Android delivery time** under Doze can be hours late; acceptable for a monthly digest,
  documented in `docs/expiry-tracking.md`. OEM battery killers may drop the alarm until the
  app is opened; the app re-arms on start.
- **iOS stops after 12 months** if the app is never opened in a year (no background
  refresh). Accepted and documented.
- **Alarms after an app update** are re-armed by `MY_PACKAGE_REPLACED`; verified once on a
  device in the measurement task.
- **Month-year formatting** differs slightly between Foundation and Android skeletons; the
  parity check covers our keys, not system date text — the UI tests assert the month and
  year are present, not an exact string.
