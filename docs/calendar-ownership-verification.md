# Airbnb calendar control verification

## Scope and integration

The host can demonstrate a change in the connected Airbnb iCal source. This does
not verify their identity or legal ownership of the property. Existing calendar
connection, listing-ID matching, hourly/manual synchronization, event imports,
enable/disable and deletion remain in `ParrotService` unchanged.

The `calendarverification` package reuses `IcalFetcher` and `AirbnbIcal.parse`.
It adds one table (`V24__calendar_ownership_verification.sql`), a service/repository,
private routes and a background worker wired into `Main`. It does not write to
`external_calendar_events` or change guest availability/search. The legacy
admin-operated listing challenge is independent; passing it does not grant this
new calendar status.

## Owner API

All routes authenticate the existing session and check ownership of the current
calendar. Responses use `Cache-Control: no-store`; another owner's calendar is 404.
The Worker exposes these as `/api/host/calendars/...` with its existing Origin guard.

- `GET /api/calendars/{id}/verification`: current status and available actions.
- `POST /api/calendars/{id}/verification/start`: create an attempt and fetch a fresh
  baseline from the stored iCal URL. A duplicate Start returns the existing attempt.
- `POST /api/calendars/{id}/verification/check`: immediately fetch/compare, then
  persist automatic retries if no availability change is observed. Duplicate Check
  only returns status; it never adds requests or resets the schedule.

The response includes `status` (`required`, `pending`, `verified`, `failed`, `blocked`),
`attemptId`, `attemptsCount`, `maxAttempts`, `baselineReady`, `checksCount`,
`startedAt`, `expiresAt`, `nextCheckAt`, `verifiedAt`, `blockedUntil`, `lastError`,
`canStart` and `canCheck`. Dates are UTC ISO timestamps; the UI uses local display
time. Error codes are sanitized (`fetch_failed`, `invalid_calendar`, `source_changed`,
`calendar_disabled`, `expired`, `no_change`). Neither the secret URL, its hash, the
baseline nor lease tokens are returned.

## Attempt and retry semantics

- One attempt starts with a fresh baseline and lasts up to 30 minutes while the
  owner makes their chosen change in Airbnb. The interface shows its deadline.
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

Use a canonical union of all unavailable date ranges, including manually blocked
Airbnb dates. Merge adjacent/overlapping ranges and ignore order, duplicate events,
UID, summary, DTSTAMP and other metadata. Only today/future nights relative to the
fixed UTC date captured at Start participate. Both blocking and reopening dates work;
an empty complete calendar is valid. An incomplete/malformed iCal is rejected by a
verification-specific completeness check before the existing parser is called.

The owner chooses the period in Airbnb; there is no prescribed date/status and no
selected range submitted to PARROT. Consequently, any observed availability change
within the attempt can pass, including an unrelated new/cancelled reservation or an
automatic Airbnb rule change. iCal cannot establish who made the change or its exact
edit timestamp. The status means a change was observed after the fresh baseline; it
is not cryptographic proof of control. Export delay beyond the retry window can
cause a legitimate attempt to fail. No OAuth, Airbnb API or scraping is introduced.

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
