# Airbnb calendar control verification

## Scope and integration

The host can demonstrate a change in the connected Airbnb iCal source. This does
not verify their identity or legal ownership of the property. Existing calendar
connection, listing-ID matching, hourly/manual synchronization, event imports,
enable/disable and deletion remain in `ParrotService` unchanged.

The `calendarverification` package reuses `IcalFetcher` and `AirbnbIcal.parse`.
It adds one table (`V24__calendar_ownership_verification.sql`), three challenge columns
(`V25__calendar_verification_challenge.sql`), a service/repository,
private routes and a background worker wired into `Main`. It does not write to
`external_calendar_events` or change guest availability/search. The legacy
admin-operated listing challenge is independent; passing it does not grant this
new calendar status.

## Owner API

All routes authenticate the existing session and check ownership of the current
calendar. Responses use `Cache-Control: no-store`; another owner's calendar is 404.
The Worker exposes these as `/api/host/calendars/...` with its existing Origin guard.

- `GET /api/calendars/{id}/verification`: current status and available actions.
- `POST /api/calendars/{id}/verification/start` with JSON
  `{ "from": "2030-11-01", "to": "2030-11-02" }`: select owner nights with both
  dates inclusive, create an attempt and fetch a fresh baseline from the stored
  iCal URL. Dates must be future/today, ordered, and at most 365 nights. A duplicate
  Start returns the existing attempt without replacing selected nights.
- `POST /api/calendars/{id}/verification/check`: immediately fetch/compare, then
  persist automatic retries if no availability change is observed. Duplicate Check
  only returns status; it never adds requests or resets the schedule.

The response includes `status` (`required`, `pending`, `verified`, `failed`, `blocked`, `rejected`),
`attemptId`, `attemptsCount`, `maxAttempts`, `baselineReady`, `checksCount`,
`startedAt`, `expiresAt`, `nextCheckAt`, `verifiedAt`, `blockedUntil`, `lastError`,
`canStart`, `canCheck`, `selectedFrom`, `selectedTo` and `expectedAction` (`close` or `open`).
The selected dates are plain local calendar dates; timestamps are UTC ISO and the UI uses local display
time. Error codes are sanitized (`fetch_failed`, `invalid_calendar`, `source_changed`,
`calendar_disabled`, `expired`, `no_change`, `choose_unreserved_dates`,
`choose_uniform_dates`). Neither the secret URL, its hash, the
baseline nor lease tokens are returned.

## Attempt and retry semantics

- One attempt saves the selected nights before fetching the baseline. Once a usable
  snapshot is saved, the server picks one action and the UI shows exactly what to
  change in Airbnb, with a 30-minute deadline. Pending/Check/reload keep the same
  selected range and action. A rejected baseline does not use up an attempt.
- The first Check runs immediately. If unchanged/unavailable, retry at +5, +10 and
  +20 minutes **relative to that first Check**, not +5/+10/+20 cumulatively.
- The worker checks persisted jobs every 15 seconds with up to four concurrent
  fetches. A browser tab is not needed. Late workers skip missed retry slots instead
  of making a burst of catch-up requests. There is a five-minute grace period after
  the final due time for restarts/backlog; older attempts expire without verification.
- Attempts use expiring 60-second leases with fencing tokens. Concurrent clicks,
  multiple workers and crashed processes cannot consume another attempt or allow
  an old response to overwrite the current attempt. No DB transaction spans a fetch.
- An unavailable/invalid initial baseline stays pending and retries after one minute
  until its initial deadline. An unavailable/invalid check follows the normal retry
  schedule and can never be interpreted as an empty calendar.
- Failure after the final check, abandonment or source replacement consumes that
  attempt. After the third failure, Start/Check return 429 until `blockedUntil`,
  24 hours after failure. After cooldown, a new attempt starts at count 1.
- Disabling pauses provider requests. Pending attempts still expire. Verified
  status survives disable/enable. A changed iCal URL or a re-created calendar needs
  a new baseline and cannot inherit a former source's verified status.
- History/cooldown is retained for the property if its calendar/listing is removed
  and reconnected (`calendar_id ON DELETE SET NULL`); deleting the property cascades
  its verification history. This is a per-property limit, not an account-wide limit.

## What counts as a change

First validate the entire iCal feed. If every selected night is free, ask the owner
to block all selected nights (`close`). If every selected night is covered by Airbnb's
`Airbnb (Not available)` events, ask them to open all those nights (`open`). A mixed
range, reservation, or unknown event is rejected before giving instructions. On Check,
the full selected range must reach the required opposite state; a change outside the
range, a partial change, reservation, UID/metadata change or fetch failure is not
proof. `DTEND` is exclusive in iCal while the owner's last selected night is inclusive.
The canonical full-calendar snapshot also has to differ from the stored baseline.

Old V24 verified rows have no selected range and are displayed as `required` until
a V25 attempt succeeds. V24 pending rows are failed on migration so their old
whole-feed comparison cannot grant V25 verification. V24 blocked cooldown remains.
iCal still cannot establish who made the change or the exact edit timestamp; export
delay beyond the retry window can cause failure. No OAuth, Airbnb API or scraping.

## Validation

- `CalendarSnapshotSuite`: block/unblock and empty feeds, canonical equivalence,
  past-only changes, malformed/truncated feeds.
- `CalendarVerificationSuite`: actual PostgreSQL, isolated schemas and a controllable
  clock/fetcher; covers success, persistent 5/10/20 retries, duplicate/concurrent work,
  three failures, 24-hour cooldown and success afterwards, reconnect bypass,
  stale leases/source changes, unavailable feeds, disable/enable and owner-only HTTP.
- CI runs this alongside existing calendar lifecycle/guest-search/auth HTTP smoke tests.
- Frontend regression tests cover translated states/actions, duplicate clicks,
  polling, hidden/disposed panels, cooldown, errors, sessions and Worker forwarding.

Production verification must never toggle a real owner's Airbnb dates automatically.
A real external-control check requires the owner to change a period in Airbnb.
