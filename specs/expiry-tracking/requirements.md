# Expiry tracking and notifications — Requirements

Status: **Draft — awaiting approval**
Epic: #22 · Children: #23, #24, #25 · Branch: `feature/22-expiry-tracking` · Platforms: iOS + Android

## Introduction

A cabinet the household cannot read at a glance is a cabinet nobody checks: expired boxes
stay in the drawer until someone takes one by mistake. Every box in MediCabinet already carries
its expiry date (or an explicit "unknown"). This epic turns those dates into a status per box
(expired, expiring soon, OK, unknown), an **Expiring** tab that lists what needs attention, and
an opt-in monthly reminder, so the household learns about an expired or soon-to-expire box
without having to open the app — and a box with no known date is never shown as safe.

> We are adding an expiry status (expired / expiring soon / OK / unknown, with a 60-day default
> window), an Expiring view with a "thrown away" action, and an opt-in monthly reminder that
> shows counts only, on iOS and Android, so that the household never uses an expired medicine
> without knowing it. We are **not** adding shelf life after opening, per-box notifications,
> editing a box's date, or any change to the export format.

### Decisions taken in the challenge (2026-10-19)

1. **Status rule.** "Today" is the device's local calendar date. The expiry day itself is
   still usable; month precision runs to the last day of the month; no date means *unknown*,
   never OK. One core rule on both platforms, proven by a shared fixture.
2. **Window.** A setting with three choices — 30, 60, 90 days — named default **60**. It is a
   device preference, not exported.
3. **Reminder.** One monthly digest, on the 1st at 09:00 local time, with **counts only** (no
   medicine names on the lock screen). Nothing is sent when there is nothing to report.
   Expired boxes still in the cabinet are counted every month; unknown expiries are not.
4. **Opt-in.** Reminders are off by default, offered by a card on the Expiring view and a
   switch in Settings; the system permission is asked only when the user turns them on.
5. **Mechanics.** One core digest function on both platforms. iOS schedules the next 12
   digests ahead; Android runs a WorkManager job at each digest time. No exact alarms.
6. **Expiring view.** Sections Expired / Expiring soon / Expiry unknown; a **Thrown away**
   action with Undo; a tab badge. Editing a box (including adding a missing date) stays with
   the box detail (#20).
7. **Out of scope.** Shelf life after opening, per-box notifications, an app-icon badge,
   choosing the reminder day or time, exporting settings, push or email.
8. **Sequencing.** Core and fixtures are built off `develop` now; the app tasks follow once
   the bulk-scan epic (#12) is on `develop`. Version: next minor on both apps (0.3.0 if this
   ships after #12).

## Glossary

- **Last valid day** — the last day a box may still be used: its printed day, or the last day
  of the month for a month-precise date (`ExpiryDate.lastValidDay`).
- **Today** — the current calendar date (year, month, day) in the device's current time zone,
  derived from the injected clock and calendar.
- **Days left** — the number of calendar days from today to the last valid day (0 on the
  expiry day, negative once expired).
- **Window** — the "expiring soon" setting, in days.
- **Box in the cabinet** — a box that is neither removed (used up / thrown away) nor deleted
  (added by mistake, schema v2 from #12).
- **Digest** — the monthly reminder notification.
- **Digest date** — the 1st of a month at 09:00 in the device's current time zone.
- **Shared fixture** — a JSON test-vector file under `fixtures/` run by both platforms.

## User stories and acceptance criteria

Requirement IDs: **S** expiry status, **W** window setting, **E** Expiring view, **N**
notifications, **X** cross-cutting.

### S — Expiry status (#23)

*As a household member, I want every box to say plainly whether it is expired, expiring soon,
OK, or of unknown date, so that I never take an expired medicine and never trust a box whose
date nobody knows.*

1. The status rule SHALL live in both cores (`MediCabinetCore`, Kotlin `:core`), with no
   platform framework and no wall clock, and SHALL pass every case of
   `fixtures/expiry-status.json` on both platforms.
2. The rule SHALL take the expiry (or none), today as a calendar date, and the window in days,
   and SHALL return the status and, for a known date, the days left.
3. WHEN a box has no expiry, its status SHALL be **unknown**. An unknown box SHALL never be
   presented, counted or sorted as OK.
4. WHEN today is after the last valid day, the status SHALL be **expired**.
5. WHEN today is the last valid day, the status SHALL be **expiring soon** and the box SHALL be
   labelled "expires today".
6. WHEN the days left are between 0 and the window, both included, the status SHALL be
   **expiring soon**.
7. WHEN the days left are greater than the window, the status SHALL be **OK**.
8. WHEN the expiry is month-precise (GS1 `DD = 00`), the last valid day SHALL be the last day
   of that month, including 29 February in a leap year.
9. Today SHALL be computed in the device's current time zone. WHEN the time zone or the date
   changes (midnight, travel, manual clock change) while the app is running, every displayed
   status, the tab badge and the scheduled reminders SHALL be brought up to date.
10. Removed and deleted boxes SHALL have no status: they SHALL NOT appear in the Expiring view,
    the badge or the digest.
11. The fixture SHALL cover at least: the expiry day itself, the day after, the window's last
    day and the day after, month precision in 30-day, 31-day and February months (leap and
    common), year ends, unknown expiry, and each window value (30, 60, 90).

### W — "Expiring soon" window (#23)

*As a household member, I want to choose how early a box counts as expiring soon, so that I
have time to use it up or replace it.*

1. Settings SHALL offer the window as a choice of **30, 60 or 90 days**, with a named default of
   **60 days** defined once in each core.
2. WHEN the user changes the window, the Expiring view, the tab badge and the scheduled
   reminders SHALL use the new value immediately.
3. The window SHALL be stored on the device only; it SHALL NOT be written to the export file.
4. WHEN the stored value is missing or not one of the allowed choices, the system SHALL use the
   default and SHALL NOT crash.
5. The setting SHALL explain in one sentence what the window does (for example "Boxes expiring
   within this many days are shown as expiring soon").

### E — Expiring view (#24)

*As a household member, I want one screen listing what is expired, what expires soon and what
has no known date, so that I can sort out the cabinet in one pass.*

1. The Expiring tab SHALL list the boxes in the cabinet in three sections, in this order:
   **Expired**, **Expiring soon**, **Expiry unknown**. A section with no box SHALL be hidden.
   OK boxes SHALL NOT be listed.
2. Expired boxes SHALL be sorted oldest expiry first; expiring-soon boxes soonest first;
   unknown boxes by name (locale-aware). Ties SHALL be broken by name, then by a stable order,
   so the list never reshuffles between refreshes.
3. Each row SHALL show the product name, the location name, the expiry date (or "expiry
   unknown") and the status as text plus an icon — never colour alone.
4. Dates SHALL be formatted with the user's locale; a month-precise date SHALL show the month
   and year only (e.g. "March 2027"), never an invented day.
5. WHEN no box is expired, expiring soon or of unknown date, the view SHALL show an empty state
   that names the current window (e.g. "Nothing expires in the next 60 days").
6. WHILE the list is loading, the view SHALL show a loading state; WHEN loading fails, an error
   state with **Retry**.
7. WHEN the cabinet changes (a box is added, edited, removed or restored anywhere in the app),
   or the date, time zone or window changes, the view SHALL update without a manual refresh.
8. The Expiring tab SHALL show a badge with the number of expired plus expiring-soon boxes,
   hidden at zero, with an accessible label (e.g. "3 boxes need attention"). Unknown boxes
   SHALL NOT count towards the badge.
9. Each row SHALL offer **Thrown away**, as a swipe action and as an accessibility action. It
   SHALL mark the box removed with reason `thrownAway` at the injected clock's time and show a
   confirmation with **Undo**, which restores the box. Nothing SHALL be removed without that
   confirmation being shown.
10. Rows SHALL NOT open a box detail or allow editing in this epic; the box detail and date
    editing come with #20.
11. WHILE reminders are off and the card has not been dismissed, the view SHALL show the
    reminder card of N2.

### N — Monthly reminder (#25)

*As a household member, I want a short monthly reminder when something in the cabinet is
expired or about to expire, so that I do not have to remember to check.*

1. Reminders SHALL be **off by default**. The app SHALL NOT ask for the notification
   permission at launch or during a scan.
2. The Expiring view SHALL show a card offering a monthly reminder, with **Turn on** and **Not
   now**. **Not now** SHALL hide the card for good (remembered across launches); Settings SHALL
   always offer a **Monthly reminder** switch.
3. WHEN the user turns reminders on and the permission is not decided, the system SHALL show
   the system permission prompt. WHEN it is granted, reminders SHALL be on and scheduled.
   WHEN it is denied, reminders SHALL stay off and the app SHALL say why, with **Open
   Settings** where the platform allows it.
4. WHEN the notification permission is revoked in the system settings, the app SHALL show the
   reminder as blocked (with **Open Settings**) the next time it is in the foreground, and SHALL
   NOT pretend reminders are on.
5. WHILE reminders are on, the system SHALL deliver at most one digest per month, at the
   digest date (the 1st at 09:00 local time).
6. The digest SHALL contain only two counts, computed for its digest date: boxes in the cabinet
   that are expired, and boxes that are expiring soon under the current window (e.g. "2 boxes
   expired · 3 expire within 60 days"), with correct plural forms. It SHALL NOT contain product
   names, locations, lots, serials or dates.
7. WHEN both counts are zero for a digest date, no notification SHALL be delivered for that
   month.
8. Boxes of unknown expiry SHALL NOT be counted in the digest.
9. WHEN the user taps the digest, the app SHALL open on the Expiring tab.
10. The digest content and the next digest dates SHALL be computed by one function in both
    cores, passing every case of `fixtures/expiry-digest.json` on both platforms (including a
    month with only unknown boxes, a box expiring exactly on a digest date, a box entering the
    window between two digests, and a time-zone change).
11. **iOS**: the system SHALL schedule the next 12 digests as local calendar notifications at
    09:00 local time, each with its own precomputed counts, and SHALL never keep more than 12
    pending (well below the 64 per-app limit). It SHALL reschedule them whenever the cabinet
    changes, the window or the reminder setting changes, the app comes to the foreground, and
    the time zone or the clock changes.
12. **Android**: the system SHALL schedule one background job for the next digest date, which
    computes the counts from the cabinet as of that digest date, posts the digest when they are
    not both zero, and schedules the next one. It SHALL survive a reboot and an app update, SHALL
    NOT use exact alarms, and SHALL NOT deliver two digests for the same month even when the job
    runs late.
13. WHEN the user turns reminders off, every pending digest SHALL be cancelled.
14. WHEN the device's time zone changes, the next digest SHALL be at 09:00 in the new time zone.
15. The digest text SHALL be identical on both platforms (same keys, same French and English
    wording).

### X — Cross-cutting

1. **Privacy**: notifications SHALL contain counts only (N6). Product names, expiry dates and
   cabinet contents SHALL never be logged. The only new permissions SHALL be Android's
   `POST_NOTIFICATIONS` (Android 13+) and `RECEIVE_BOOT_COMPLETED`; iOS needs no new usage
   description. No network access, analytics or push service. Data-at-rest protection is
   unchanged (iOS keeps `CompleteUntilFirstUserAuthentication`; Android stays in internal
   storage). The privacy declarations (`PrivacyInfo.xcprivacy`, manifest comment, docs) SHALL
   match what ships.
2. **Clock**: all date logic SHALL use the injected clock and calendar/time zone; tests SHALL
   cover midnight rollover, month ends, leap years, `DD = 00`, the expiry day and time-zone
   changes.
3. **Performance**: status, grouping and digest computation for a 1 000-box cabinet SHALL run
   off the main thread, with a timing assertion in each core; the Expiring list with 1 000
   boxes SHALL scroll without dropped frames; rescheduling reminders SHALL NOT block the UI.
4. **Accessibility**: every control and row labelled for VoiceOver/TalkBack (status read as
   words); 44 pt / 48 dp targets; Dynamic Type and 200 % font scale; WCAG AA contrast in light
   and dark mode; Thrown away reachable without a swipe.
5. **i18n**: every new string in French and English on both platforms, same wording; plural
   variants for counts; dates formatted with the user's locale.
6. **Docs**: `docs/expiry-tracking.md` (status rule, window, Expiring view, reminders,
   privacy), `docs/architecture.md` (notification scheduling per platform), `fixtures/README.md`
   (new fixtures and cross-platform names).
7. **Version**: the next minor version on both apps (0.3.0 if this ships after #12), with
   changelog entries.
8. Each feature SHALL have success, failure and edge-case tests on both platforms; core
   coverage targets 100 % of branches, new app code 90 %.

## Out of scope

- **Shelf life after opening** (e.g. eye drops usable for 28 days once opened): a real safety
  gap, but the data is not in BDPM and choosing durations would edge towards medical advice;
  proposed as its own issue.
- Per-box or per-event notifications; notifications for unknown expiries.
- An app-icon badge; choosing the reminder day, time or frequency.
- Box detail, editing a box or adding a missing date (#20); inventory list (#19).
- Writing the window or reminder settings into the export file (#26).
- Push, email or any other notification channel; a server.

## Open points to confirm during design

1. **Dependency on #12.** "Box in the cabinet" excludes deleted boxes, which exist only with
   schema v2 from the bulk-scan epic. The app tasks start after #12 is on `develop`; if they
   must start earlier, the design states how deleted boxes are handled once v2 arrives.
2. **WorkManager** is a new AndroidX dependency: its size impact is checked in the design.
3. **Version number**: fixed when the epic PR is prepared, from whatever is on `develop` then.
