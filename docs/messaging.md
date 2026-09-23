# Internal messaging backend

Implemented in `com.parrot669.messaging`: models, routes, service and repository.
`Main` composes its routes with the existing API. Flyway V21 adds three messaging
tables; V22 adds participant blocks; V23 adds email preferences and the notification
outbox. Property, address, availability and listing models are unchanged.

The frontend uses `/messages.html`, a search contact action and a host opt-in
control, with the same API namespace proxied by the Worker. Message email
notifications reuse the existing Resend configuration. Email verification is unchanged.

## Access and privacy

- Reuse `parrot_session`; the account must have a verified email and an unexpired
  session. There are no new host/guest roles. Either participant can own properties.
- A host explicitly opts in to **new** conversations. The setting belongs to their
  profile and covers all their properties; the default is **false**.
- Only the authenticated guest can initiate a thread about another owner's
  property. The server determines the owner and both participants. A listing or
  published external link is not required.
- One thread per property + guest. Starting again adds to the same thread.
  Turning off new conversations does not prevent existing participants replying.
- Either participant can block the other **across all properties**. Both lose
  the ability to send or start new threads together, while history remains readable.
  Each participant can remove only their own block. A shared PostgreSQL advisory
  transaction lock serializes sends and block changes for the pair.
- Only the two participants can list/read/send/acknowledge messages. A stranger
  gets the same 404 as a nonexistent thread. Account emails, raw contacts, exact
  addresses, coordinates and session tokens are never included in messaging DTOs.
- All responses use `Cache-Control: no-store`. Clients must render `body` and
  `lastMessagePreview` as **plain text**, never HTML.

## API

All paths below start with `/api/messaging`. Only `contact-options` is public.
Authenticated browser integration should proxy this namespace through the Worker
with the same cookie forwarding and Origin protection used by `/api/host/*`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/contact-options/:propertyId` | `{propertyId, acceptingNewConversations, propertyTitle, hostProfileId, hostDisplayName}`; public labels only |
| GET / PUT | `/settings` | Read/set `{acceptingNewConversations: boolean}` for the signed-in profile |
| GET / PUT | `/notification-settings` | Read/set own `{enabled: boolean, language: "en"\|"es"\|"ca"\|"ru"}`; independent of host opt-in |
| POST | `/conversations` | Start/reuse a conversation and atomically save its first/new message |
| GET | `/conversations?limit=20&cursor=...` | Inbox, newest activity first; `{items, nextCursor}` |
| GET | `/conversations/:id` | Participant-only conversation metadata and unread count |
| GET | `/conversations/for-property/:propertyId` | Current guest's conversation view or `null`; does not create a thread |
| GET | `/conversations/:id/messages?afterSequence=0&limit=50` | Ascending history; `{items, nextAfterSequence}` |
| POST | `/conversations/:id/messages` | Send a message |
| PUT | `/conversations/:id/read` | Acknowledge `{throughSequence: 42}` |
| PUT | `/conversations/:id/block` | Set/remove the signed-in participant's block: `{blocked: true/false}` |
| GET | `/unread` | `{conversations, messages}` counts across all participant threads |

Starting a conversation:

```json
{
  "propertyId": "<UUID>",
  "clientMessageId": "<new client-generated UUID>",
  "body": "Is this apartment available for these dates?",
  "from": "2027-05-01",
  "to": "2027-05-04"
}
```

Sending to an existing conversation uses the same body without `propertyId`.
`from` and `to` are optional, but must be supplied together and form a nonempty
hotel-style `[check-in, checkout)` interval. Dates belong to the message so later
enquiries can discuss a different stay in the same thread. A message is not a
booking and never reserves dates, changes prices or writes availability.

Successful writes return 200, including retries. Start returns
`{conversationId, message}`; send returns the message. A message contains `id`,
`sequence`, `senderProfileId`, `clientMessageId`, `body`, `from`, `to`, `createdAt`.
The conversation view supplies property ID/title, participant profile IDs, the
other participant's public PARROT ID/display name, last/read sequences, unread
count, a 160-character last-message preview, updated time, `blockedByMe`,
`blockedByOther` and `canReply` (false if deleted or blocked by either side).

Pagination limits are 1–100. Keep using `nextAfterSequence` while non-null; for
polling after the final page, pass the highest received sequence. For an initial
recent window, get `lastSequence` from conversation metadata and request
`afterSequence=max(0,lastSequence-50)`. Inbox cursors are opaque and bound to the
current query's ordering; newly active threads can move to the first page, so
refresh the first page for new activity and deduplicate by conversation ID.

## Delivery, concurrency and lifecycle

- Generate a `clientMessageId` **once per intended send** and reuse it on network
  retries. Same conversation + sender + ID + normalized body/dates returns the
  original message. Reusing that ID with different content returns 409.
- The first message and thread creation commit together. PostgreSQL uniqueness
  and row locks prevent duplicate threads/messages under concurrent requests.
- Per-thread sequences are assigned while holding a conversation row lock.
  Sequence order agrees with commit order within the thread; polling cannot skip
  an earlier in-flight message.
- Reading does not implicitly mark messages read. Explicit acknowledgements only
  advance to a sequence actually displayed by the client, never move backwards,
  and reject sequences beyond the latest message. Sending a reply does not mark
  earlier incoming messages read.
- Deleting a property retains its private conversation history and saved title,
  sets `propertyId=null` and `canReply=false`, and rejects new sends with 409.
  Profile/account deletion cascades their conversations and messages.
- Body: trimmed plain text, 1–4000 Unicode code points, no control characters
  except newline/tab/carriage return. JSON request bodies are capped at 16 KiB.
- Per authenticated profile: 30 new messages/minute and 10 new conversations/hour.
  Limits are database-backed and serialized across service instances; retries do
  not consume another allowance. Exceeding a limit returns 429 and rolls back the
  entire write, including a newly created conversation.
- Errors keep the standard `{error: "..."}` shape: 400 invalid input, 401 missing/
  invalid session, 404 inaccessible/missing resource, 409 blocked pair, opted-out host, deleted
  property or reused idempotency key, 429 rate limit.

## Message email notifications

- Enabled by default for verified recipients; language defaults to English. The
  Messages page provides an on/off control and EN/ES/CA/RU email-language selection.
  Changing these preferences never changes whether the host accepts new enquiries.
- A **new** message upserts one outbox row for its conversation and recipient in
  the same transaction. Client retries do not enqueue twice. No historical
  messages are backfilled when V23 is deployed.
- Wait two minutes after the first pending arrival; coalesce bursts. After an
  accepted email, wait at least 15 minutes before another email to that recipient
  about the same conversation. New messages during delivery remain queued.
- Before claiming and immediately before the provider call, check unread state,
  both participants' blocks, email preferences, verified/current recipient email
  and property existence. Skip ineligible notifications. An email already in
  flight can still arrive after a read, block or opt-out.
- Mail contains only a generic unread notice and an authenticated conversation
  link. No message body, property title, nickname, address, stay dates or other
  participant's email is included. Reply on the website, not by email.
- PostgreSQL `FOR UPDATE SKIP LOCKED` and two-minute leases coordinate workers.
  Provider I/O happens outside transactions. Persist a frozen request payload and
  delivery UUID before the first attempt; retries and recovered leases reuse both.
- Resend requests have five-second connection and 15-second request timeouts,
  redirects disabled, and `Idempotency-Key: messaging/<delivery UUID>`.
  [Resend retains idempotency keys for 24 hours](https://resend.com/docs/dashboard/emails/idempotency-keys).
  Stop uncertain retries after 23 hours, or eight attempts, to stay within that
  window. Backoff grows from two minutes to a 30-minute cap; retry network errors,
  408/409/429 and 5xx. Other failures are terminal for that delivery.
- Clear frozen recipient/payload data after completion, suppression or terminal
  failure. Keep only scheduling state and a sanitized last-error code. Logs include
  delivery IDs and status codes, never recipient addresses, API keys or provider bodies.
- The resource-managed loop polls every 15 seconds, drains up to 20 jobs with a
  one-second pause between deliveries, and survives transient failures. It starts
  with the existing Resend configuration; `APP_ENV=test` disables live delivery.
- A provider outage does not affect chat writes. Monitor the worker's sanitized
  warnings and outbox `last_error`; provider acceptance is not proof of inbox delivery.

## Verification

`MessagingSuite` runs against isolated schemas in the configured **test**
PostgreSQL database. The existing CI smoke script already runs `sbt test` with
`TEST_DATABASE_URL`, then migrates/starts the full application and builds Docker.
Tests cover participant isolation through HTTP, private-data minimization, opt-in,
half-open dates, bounded requests, pagination, concurrency across service
instances, retry idempotency, read races, deletion, blocks (including another-property
bypass attempts and concurrent sends) and rollback under rate limits. Email tests
cover coalescing, cooldown, both participants, suppression, preference isolation,
concurrent workers, stale leases, ambiguous responses, frozen retries and expiry.

Browser integration polls visible conversations every 15 seconds and unread badges
every 30 seconds. It renders plain text and acknowledges read only when the active
history is visible, focused and scrolled to the latest received message. Drafts
and pending idempotency keys are scoped to the account in sessionStorage (24-hour
expiry), never used as a source of truth for messages. Explicit logout clears drafts.

Abuse reporting/moderation and bounce/delivery webhook handling are follow-up work;
attachments, realtime sockets, public contact publishing and booking are out of scope.
