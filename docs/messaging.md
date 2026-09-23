# Internal messaging backend

Implemented in `com.parrot669.messaging`: models, routes, service and repository.
`Main` composes its routes with the existing API. Flyway V21 adds three messaging
tables; property, address, availability and listing models are unchanged.

This release is backend only. The frontend/Worker proxy and message email
notifications are not connected yet. Do not display a working "Write to host"
button until those frontend routes are wired. Existing email verification is unchanged.

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
| GET | `/contact-options/:propertyId` | `{propertyId, acceptingNewConversations}`; no raw contact |
| GET / PUT | `/settings` | Read/set `{acceptingNewConversations: boolean}` for the signed-in profile |
| POST | `/conversations` | Start/reuse a conversation and atomically save its first/new message |
| GET | `/conversations?limit=20&cursor=...` | Inbox, newest activity first; `{items, nextCursor}` |
| GET | `/conversations/:id` | Participant-only conversation metadata and unread count |
| GET | `/conversations/:id/messages?afterSequence=0&limit=50` | Ascending history; `{items, nextAfterSequence}` |
| POST | `/conversations/:id/messages` | Send a message |
| PUT | `/conversations/:id/read` | Acknowledge `{throughSequence: 42}` |
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
count, a 160-character last-message preview, updated time and `canReply`.

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
  invalid session, 404 inaccessible/missing resource, 409 opted-out host, deleted
  property or reused idempotency key, 429 rate limit.

## Verification

`MessagingSuite` runs against isolated schemas in the configured **test**
PostgreSQL database. The existing CI smoke script already runs `sbt test` with
`TEST_DATABASE_URL`, then migrates/starts the full application and builds Docker.
Tests cover participant isolation through HTTP, private-data minimization, opt-in,
half-open dates, bounded requests, pagination, concurrency across service
instances, retry idempotency, read races, deletion and rollback under rate limits.

Before exposing to broad public traffic, add per-conversation block/report controls
alongside the UI. Message email notifications belong with the working inbox link;
attachments, realtime sockets, public contact publishing and booking are out of scope.
