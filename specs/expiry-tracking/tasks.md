# Expiry tracking and notifications — Tasks

Status: **Approved 2026-10-19**
Epic: #22 · Branch: `feature/22-expiry-tracking`

One task = one focused commit (code + tests + this file checked off). Tags: `[shared]`,
`[ios]`, `[android]`. Child issue in brackets; requirement IDs refer to
[`requirements.md`](requirements.md), sections (§) to [`design.md`](design.md).

Order: shared fixtures → both cores → observation port → **wait for #12 on `develop`
(T9)** → preferences and today source → Expiring view → reminders, per platform → privacy,
docs, measurements, version. Blocks 1–3 can start now. After T9, the iOS block (T10, T12,
T14, T16) and the Android block (T11, T13, T15, T17) are independent of each other.

## 1. Status rules (#23)

- [x] **T1 — Expiry fixtures** `[shared]` [#23, #24, #25] (S1, S11, E2, N10; §2)
  `fixtures/expiry-status.json` (`cases` + `todayCases`), `fixtures/expiring-overview.json`,
  `fixtures/expiry-digest.json` (`countCases` + `scheduleCases`) with the cases listed in
  §2.1–2.3; `fixtures/README.md` (files and cross-platform names, §2.4); a broken copy of each
  under `scripts/tests/fixtures-broken/`. *Test:* `check-fixtures.py` green and the break
  script fails on each broken copy.
- [x] **T2 — `CalendarDay`, `ExpiryWindow`, `ExpiryRule` in `MediCabinetCore`** `[ios]` [#23] (S1–S9, S11, W1, W4; §1.2, §3.1, §3.2)
  *Tests first:* `ExpiryRuleFixtureTests` (one per case, plus `todayCases`),
  `CalendarDayTests` (epoch-day round trip for every day 2000–2099, `adding`,
  `firstOfNextMonth`, `make` errors, `parse`), `ExpiryWindowTests` (stored 30/60/90, nil,
  45, negative → default). `SharedFixtures` loads the new file. 100 % branch coverage.
- [x] **T3 — Kotlin twin in `:core`** `[android]` [#23] (S1–S9, S11, W1, W4; §3.1, §3.2)
  Same types and tests (`@TestFactory` per case), `CalendarDay.from` via `java.time`, no wall
  clock (architecture check). 100 % branch coverage.

## 2. Overview and digest rules (#24, #25)

- [x] **T4 — `ExpiringOverview`, `NameOrder`, `ExpiryDigest` in `MediCabinetCore`** `[ios]` [#24, #25] (S10, E1, E2, E8, N5–N8, N10, N12; §1.6, §3.3, §3.4)
  *Tests first:* `ExpiringOverviewFixtureTests`, `ExpiryDigestFixtureTests` (`countCases`,
  `scheduleCases`), `NameOrderTests` (case, accents, ties by id lower-cased); timing assertion
  §9 (1 000 boxes, overview + 12 digests ≤ 50 ms, median of 10). 100 % branch coverage.
- [ ] **T5 — Kotlin twin** `[android]` [#24, #25] (same; §3.3, §3.4)
  Same tests, NFD folding, same timing assertion.

## 3. Cabinet change observation (#24)

- [ ] **T6 — Observation contract checks** `[shared]` [#24] (E7; §1.4, §2.4)
  Add to `fixtures/repository-contract.json`, in order: `observation emits the active boxes
  first`, `observation emits after insert, update, markRemoved and undoRemoval`,
  `observation ignores removed boxes`, `observation does not emit for a failed write`.
- [ ] **T7 — `activeBoxesUpdates()` on iOS** `[ios]` [#24] (E7; §3.5)
  Port method, `InMemoryCabinetStore` emission, `GRDBBoxRepository` via `ValueObservation`
  (tracking the `box` table, active filter); `RepositoryContract` runs the T6 checks on both.
- [ ] **T8 — `observeActiveBoxes()` on Android** `[android]` [#24] (E7; §1.4, §3.5)
  Port method, fake emission, `CabinetDatabase.transaction` bumps a `MutableStateFlow` after
  commit only; `SqlBoxRepository` maps it to a re-query on the injected dispatcher;
  `RepositoryContract` runs the T6 checks (Turbine).

## 4. Integration point with bulk scan (#12)

- [ ] **T9 — Bring `develop` in after #12 merges** `[shared]` (open point 1; §13)
  **Blocked until epic #12 is on `develop`.** Merge `develop`; check that deleted boxes
  (schema v2) are absent from both observations (add the contract case `observation ignores
  deleted boxes`); check `PrivacyInfo.xcprivacy` has `UserDefaults` / `CA92.1`; reuse #12's
  expiry text helpers if any instead of duplicating them in §6/§7. Full core and app suites
  green on both platforms.

## 5. Preferences, today source and window setting (#23)

- [ ] **T10 — iOS preferences, `TodaySource`, window setting** `[ios]` [#23] (S9, W1–W5; §4.1, §5, §6)
  `ExpiryPreferences` (protocol + `UserDefaults` + in-memory, stream of changes),
  `TodaySource` (day change, zone change, significant time change, injected clock and zone),
  `ExpirySettingsSection` window `Picker` with footer. *Tests:* preference round trip and bad
  stored values; `TodaySource` emits at midnight / zone change with a `MutableClock`; Settings
  UI test changes the window. Strings fr + en.
- [ ] **T11 — Android preferences, `TodaySource`, window setting** `[android]` [#23] (S9, W1–W5; §4.1, §5, §7.4)
  `SharedPreferences` `expiry_preferences`, `TodaySource` (midnight `delay` + runtime
  `TIMEZONE_CHANGED`/`TIME_SET` receiver), window radio group. *Tests:* same as T10 with
  `runTest` and Robolectric. Strings fr + en, same wording.

## 6. Expiring view (#24)

- [ ] **T12 — iOS Expiring view, badge, Thrown away + Undo** `[ios]` [#24] (E1–E10, X4; §4.2, §6.1)
  `ExpiringViewModel`, `ExpiringView`, `ExpiringRow`, `ExpiryText`, `TabRouter`; the
  placeholder and its strings are removed; `.badge` with accessibility label. *Tests:*
  view-model states (loading, content, empty naming the window, failed + Retry), live update
  through the fake's observation and `TodaySource`, Thrown away + Undo, error path;
  `ExpiringUITests` (sections, month-year text, status words, swipe and accessibility action,
  largest accessibility text size, no row navigation).
- [ ] **T13 — Android Expiring screen, badge, Thrown away + Undo** `[android]` [#24] (E1–E10, X4; §4.2, §7.1)
  `ExpiringViewModel` (`StateFlow`, injected dispatcher), `ExpiringScreen`, `ExpiringRow`,
  `ExpiryText`, `TabRouter`, `BadgedBox`; placeholder removed. *Tests:* same as T12 with
  Turbine and Robolectric Compose (font scale 2.0, `CustomAccessibilityAction`).

## 7. Reminders (#25)

- [ ] **T14 — iOS reminder setting, card and permission** `[ios]` [#25] (N1–N4, N13; §4.3, §6)
  `NotificationClient` (protocol + `UNUserNotificationCenter` live + fake), `ReminderModel`
  (`off` / `on` / `blocked`, re-read on foreground), `ReminderCard` on Expiring, Settings
  `Toggle` with blocked state and **Open Settings**; SwiftLint rule confining
  `UserNotifications` to `App/Reminders/` + lint fixture. *Tests:* every permission path
  (not determined → granted / denied, revoked later), card **Not now** remembered, turning off
  cancels; lint fixture fails.
- [ ] **T15 — iOS digest scheduler and tap routing** `[ios]` [#25] (N5–N9, N11, N14, X1; §4.4, §6.2)
  `ReminderScheduler` actor (debounce, ≤ 12 requests `expiry-digest-YYYY-MM`, floating
  `UNCalendarNotificationTrigger` at 09:00 on the 1st, empty months skipped, unauthorised
  removes all), delegate set at launch (`willPresent`, `didReceive` → `TabRouter`).
  *Tests:* scheduler with the fake client and `MutableClock` (counts per month, window change,
  cabinet change, zone change, 12 max, debounce), built content never contains a box name,
  tap → Expiring.
- [ ] **T16 — Android reminder setting, card, permission and channel** `[android]` [#25] (N1–N4, N13; §4.3, §7.2)
  `ReminderModel`, `ReminderCard`, Settings `Switch`, `POST_NOTIFICATIONS` request (API 33+),
  enabled/channel checks (API 26–32), channel `expiry_reminders`. *Tests:* Robolectric on SDK
  33 and 30 for every permission path, card **Not now** remembered, turning off cancels.
- [ ] **T17 — Android alarm, receivers, notifier and tap routing** `[android]` [#25] (N5–N9, N12, N14, X1; §4.4, §7.3)
  `ReminderScheduler.arm()` (`setWindow`, `RTC_WAKEUP`, 1 h, immutable `PendingIntent`),
  `DigestAlarmReceiver` (`goAsync`, bootstrap, `due`, `lastDigestMonth`, re-arm),
  `RescheduleReceiver` (boot, package replaced, zone, time set), `DigestNotifier`, intent
  extra → `TabRouter`; manifest permissions and receivers; `PrivacyTest` exact set (§7.3);
  architecture rule confining alarm/notification APIs to `reminders/` + fixture. *Tests:*
  `ShadowAlarmManager` target per zone and missed digest, receiver no-duplicate / due-day
  counts / bootstrap failure re-arms / under 1 s with 1 000 boxes, each broadcast re-arms,
  content never contains a box name, tap → Expiring.

## 8. Docs, measurements, version

- [ ] **T18 — Docs** `[shared]` (X6)
  `docs/expiry-tracking.md` (status rule, window, Expiring view, reminders per platform,
  late Android delivery and the 12-month iOS horizon, privacy), `docs/architecture.md`
  (observation, scheduling), `docs/README.md` link; amend requirements decision 5 if not
  already done at design approval.
- [ ] **T19 — Device measurements** `[shared]` (X3; §9, §13)
  **Needs the user's devices** (`ANDROID_SERIAL` = the device the user names): 1 000-box
  Expiring list scrolls without dropped frames on both; a digest delivered on a real
  iPhone and Android phone (clock moved forward), and still armed after an Android app
  update. Results in `reports/expiry-tracking/README.md`.
- [ ] **T20 — Versions and gate** `[shared]` (X7)
  Next minor on both apps from what `develop` holds (0.3.0 / build and versionCode +1 if #12
  shipped 0.2.0), changelog entries; `bash scripts/local-ci.sh` green; close delivered child
  issues (#23, #24, #25) with commit references.
