# PARROT 669 backend

## Airbnb calendar control verification

Owners can start a fresh-snapshot check of their connected iCal source. Verification
retries at +5/+10/+20 minutes and blocks for 24 hours after three failed attempts.
It is independent of synchronization and confirms an observed availability change,
not identity or legal property ownership. See [the API, lifecycle, tests and limits](docs/calendar-ownership-verification.md).

## Internal messaging

Private guest/host conversations are available under `/api/messaging` using
the existing session cookie. The module is isolated in `com.parrot669.messaging`;
hosts explicitly enable new conversations (off by default). See
[the API contract and lifecycle rules](docs/messaging.md). The frontend connects
the inbox and host opt-in through its Worker; V22 adds participant blocking across
all properties. V23 adds delayed unread-message emails through the existing Resend
configuration, with durable retries and recipient preferences. Manage email
notifications and their language on the Messages page.

## Owner address autocomplete — LocationIQ

Owner lookups use LocationIQ through the authenticated
`GET /api/geocode/autocomplete` endpoint. The API key stays on the backend.

1. `type=city&country=ES&q=barcelona` returns city suggestions with `bounds`
   (`west,south,east,north`) and an opaque `placeId`.
2. `type=street&country=ES&cityId=<placeId>&city=Barcelona&bounds=<west,south,east,north>&q=alf`
   searches roads inside the selected city's envelope. Pass the bounds returned
   with the selected city. The backend sends `q=Barcelona, alf`, `layers=road`,
   country and viewbox constraints in one provider call.
3. The owner selects a street and enters a house number separately. Street
   coordinates describe a road segment, not a provider-verified building.

The query is trimmed and must contain 3–256 characters. The response includes
only `address, countryCode, country, city, latitude, longitude, placeId, street,
houseNumber, resultType, bounds`. Bounds are populated for city suggestions only.
Country labels use the existing English ISO list; city and street names use their
native spelling. City identity and geographic bounds are validated; each street
must also match the selected country/city and lie inside the bounds. A rectangular
envelope is not an administrative polygon, so result checks remain necessary.
POIs cannot become street suggestions. Duplicate road segments are grouped before
returning at most ten suggestions. No matches returns `200 []`; provider 404
also means no matches. The legacy default `type=address` returns complete addresses.

`GET /api/geocode/countries` returns the built-in ISO country list without a provider
request or API key; it is public and cacheable for a day. Guest location lists and
search continue to query PostgreSQL exclusively.

Successful autocomplete responses, including empty lists, are cached for 15 minutes
(up to 512 server entries / 100 browser entries). Cache keys include type, country,
city identity/name and bounds. Concurrent identical requests share one lookup;
errors are evicted. The UI debounces input by 700ms and does not search on focus.
Country and manual house-number input spend no provider requests. There is no
language-specific prefix fallback.

The shared LocationIQ client spaces actual request starts by at least 1.1 seconds,
serializes provider calls, and bounds waiting for a busy client to two seconds.
This fits the free tier's 2 requests/second and 60/minute for the current single
backend replica. Multiple replicas sharing a key would require a shared limiter.
Provider/local quota errors return sanitized 429; the UI preserves input and offers
retry. Authentication is still required and every lookup response is `no-store`.

Set optional Railway variable `LOCATIONIQ_API_KEY` on the backend service.
An absent/blank key allows startup and returns
`503 {"error":"Address autocomplete is not configured"}`.
Missing/invalid input returns 400; missing/expired sessions return 401. Other
provider failures/timeouts return sanitized 503. Connect timeout is three seconds,
HTTP timeout eight seconds, and redirects are not followed. Keys, provider errors
and request URLs never reach users or logs. Geoapify is no longer called.

The host form displays the free-tier attribution link, **Search by LocationIQ.com**.
No new tables, property migration, map or street catalog are introduced.
Existing saved addresses remain unchanged until their owner replaces them.

Verification uses captured public LocationIQ responses from Barcelona, Madrid and
Paris, mapping/scope/cache/rate-limit tests, and disposable-database HTTP smoke tests.
Optional read-only live check (two provider calls, then a cached repeat):

`java -cp target/scala-2.13/parrot669-backend.jar com.parrot669.integration.GeocodingSmoke`

It reads the key in-place, checks Barcelona/`alf` through the production service,
writes no database records and prints only fixed public street names.

## Property addresses — 2026-09-23

`POST /api/properties` requires a nested `address`; `PUT /api/properties/:id` accepts
it optionally. Both require a complete address on save:
country code, country, city, street, house number, street/building/amenity result type,
valid coordinates and place ID. Strings are trimmed, country codes uppercased,
and field lengths validated before writing. The nested address is authoritative for
country/city. Omitting it on update preserves the saved location (including when
saving minimum stay or cleaning fee). City-only creates, including the old Barcelona
payload, return 400. V20 stores the new components without changing existing records.
Validation checks the submitted components; it does not independently confirm property
existence or ownership, nor re-query the address provider during saving.
When the house number is entered independently, resultType remains `street`; saved
coordinates and place ID refer to the selected street, not a verified building.

Migration V19 adds nullable address/coordinate/place ID columns and removes the legacy
Barcelona-only city-code constraint. Existing property locations and availability are
preserved. The owner dashboard and property create/update responses return the address;
public profiles and guest search never expose the full address or precise coordinates.
Guest location lists/search still use PostgreSQL, including newly added cities/countries.

The host UI now implements the [address workflow](docs/host-address-ui-plan.md).
`scripts/test-property-address.py` checks persistence, editing, validation, owner isolation,
address preservation when saving other settings, guest search and public response privacy.

### Cascading address input — 2026-09-23

- Split country, city, street and house number, fixing missing street suggestions.
- Restrict lookups geographically and show 10 candidates; retain street-level results.
- Add bounded caches and coalesce identical concurrent requests to reduce credit usage.
- Preserve existing property addresses and database-only guest search; no migration.

### Complete-address fix — 2026-09-23 (superseded for suggestions)

- Reject city-only and street-only locations on create/update and in autocomplete.
- Preserve historical addresses when an owner changes other property settings.
- Cover the Minsk city-only regression, missing house numbers and full address round-trips.

### Geoapify changelog — 2026-09-23

- Added `NormalizedAddress`, a small Geoapify client and authenticated autocomplete
  route so owners can get normalized suggestions without exposing the provider key.
- Added optional environment configuration and sanitized errors so provider setup or
  outages do not break startup or appear as unexplained 500s.
- Added mock-provider tests for mapping, missing key, validation, URL encoding and
  failures, plus HTTP smoke checks for auth/400/503 and no-store responses.

## Manual unavailable periods

Owners can create/list `GET|POST /api/properties/:id/unavailability` and edit/delete
`PUT|DELETE /api/unavailability/:id`. The body is `{ "from": "2030-04-10", "to": "2030-04-15" }`;
there is no price. The end date is exclusive: April 15 is available again if covered by availability.
The dashboard returns these periods separately in `unavailability`.

Manual blocks override available periods in search without splitting them or modifying prices.
They work without listings/calendars and remain independent of calendar synchronization.
Overlapping manual blocks return 409 (enforced by PostgreSQL even for concurrent writes);
adjacent blocks and overlaps with available dates are allowed. Only the property's owner can
manage them. Deleting the property cascades to its blocks. Migration V18 adds the empty table
without changing existing availability. `scripts/smoke-test.sh` covers CRUD, boundaries,
authorization, search, preserved prices and concurrent overlap on a disposable database.

Small, deliberately non-magical backend for the first PARROT vertical slice.

The current product model is not “user is verified”. It stores **specific claims proven by specific methods**. The first claim is:

```text
controls_listing
method = calendar_challenge
expires = 30 days
```

That means exactly “this profile demonstrated control of this external listing”. It does **not** mean “owns the property”. One green tick is how the internet got into this mess in the first place.

## Stack

- Scala 2.13.18
- Cats Effect 3.7.1
- http4s / Ember
- Circe
- Doobie + HikariCP
- PostgreSQL
- Flyway SQL migrations
- Java 21 recommended
- sbt 1.11.6

## Domain and authentication

```text
Account
  ├─ Session
  └─ Host Profile
       └─ Property
            └─ ExternalListing (Airbnb for now)
                 └─ VerificationChallenge

Host Profile + ExternalListing
  └─ Verification(claim = controls_listing)
```

`Account` is the login identity. It has a normalized unique email, a unique username, and an Argon2id password hash. Usernames are compared without case or surrounding spaces, allow 1–120 characters, and cannot contain `@` or control characters. A Host Profile is the domain identity shown to guests and owns properties. Display names remain non-unique. For the current MVP, one account owns one Host Profile.

Migration V16 copies each existing profile's display name into its account's username without renaming it. It checks for missing/invalid names and case-insensitive duplicates before writing; a conflict aborts the entire migration transaction. Existing sessions and passwords remain valid.

`POST /api/auth/login` accepts `{"login":"email or username","password":"..."}`. The old `email` request field remains supported. Both identifiers share the same account-level failed-password limit. Responses from login, verification, and `/api/auth/me` include `username`.

A profile still gets a human-readable ID like:

```text
P669-K7M2Q8XZ
```

Owner authorization is ownership-based, not role-based: the authenticated session resolves an Account and its Host Profile, and owner mutations verify that the target resource belongs to that profile. Guest search is public and "host" is not an RBAC role. The existing `PARROT_ADMIN_TOKEN` remains a separate technical mechanism for the internal verification endpoint.

Sessions use opaque 256-bit random tokens in an HttpOnly, SameSite=Lax cookie; production cookies are also Secure. PostgreSQL stores only SHA-256 hashes of session tokens. Sessions expire after 30 days, multiple active sessions are allowed, and logout invalidates the current server-side session.

## 1. Start PostgreSQL

```bash
docker compose up -d postgres
```

Default local database:

```text
jdbc:postgresql://localhost:5432/parrot669
user: parrot
password: parrot
```

## 2. Configure

```bash
cp .env.example .env
set -a
source .env
set +a
```

In `prod`, `PARROT_ADMIN_TOKEN` is mandatory.

## 3. Run

```bash
sbt run
```

The app starts on:

```text
http://localhost:8080
```

Flyway runs `src/main/resources/db/migration/*.sql` automatically before the HTTP server starts.

Health check:

```bash
curl http://localhost:8080/health
```

## 4. End-to-end example

The browser frontend uses the same endpoints through the Cloudflare Worker and never reads the session token. For command-line testing, use a curl cookie jar.

### Register

```bash
curl -s -c cookies.txt http://localhost:8080/api/auth/register \
  -H 'content-type: application/json' \
  -d '{
    "email": "alex@example.com",
    "username": "alex",
    "password": "correct-horse-battery-staple",
    "displayName": "Alex"
  }'
```

Registration creates an unverified Account and Host Profile, then sends an email verification link. Verification creates the session; registering alone does not log the user in. Old clients that omit `username` use their display name as the username. Retrying an unverified registration requires the same username and password and resends verification without renaming the account.

After email verification, log in with either identifier:

```bash
curl -s -c cookies.txt http://localhost:8080/api/auth/login \
  -H 'content-type: application/json' \
  -d '{"login":"alex","password":"correct-horse-battery-staple"}'
```

The raw session token is returned only through `Set-Cookie`.

Password recovery is available from both login screens via `/recover.html`.
`POST /api/auth/password-reset/request` accepts `{email, language?}` and returns
202 with the same generic message for existing and unknown accounts. No account
lookup or provider call is made before this response. A bounded background queue
sends a localized Resend link; per-account database limits allow one request every
90 seconds and at most three per hour, without invalidating earlier valid links.

`POST /api/auth/password-reset/confirm` accepts `{token, password, language?}`.
Tokens contain 32 random bytes, are stored only as SHA-256 hashes, expire after
30 minutes and are single-use. Passwords follow the existing 10–256 character
policy and Argon2id parameters. A successful reset atomically changes the password,
verifies possession of the account email, invalidates other recovery/verification
links and revokes every session. Login and verification lock the same account row
so an in-flight old credential cannot recreate a session after reset. The user
logs in normally afterwards; account/profile/property identity is retained.

Recovery URLs put the token in a fragment, not an HTTP query. The page removes it
from browser history immediately, uses `no-referrer` and never persists it or the
password in web storage. APIs are `no-store` and bounded to 16 KiB. The existing
Resend key/from/public URL configuration is reused; no schema change is required.
The process-local email queue is bounded to 64 requests and is **not durable**:
a restart or provider failure can require the user to request another link.
The UI says to check spam and retry if no email arrives; provider failures are
logged without addresses or tokens. Password-change notices are best effort.
`APP_ENV=test` uses a no-op sender; integration tests use a recording fake sender.

Existing properties accept an optional `title` in the settings PUT. It is trimmed,
validated to 1–160 characters and preserved when omitted by older clients. Owner
checks still apply; address, availability and external integrations are retained.

Authentication integration tests use isolated schemas in an explicitly configured test PostgreSQL database:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/parrot669 \
TEST_DATABASE_USER=parrot TEST_DATABASE_PASSWORD=parrot sbt test
```

Check the current identity:

```bash
curl -s -b cookies.txt http://localhost:8080/api/auth/me
```

### Create property

```bash
curl -s -b cookies.txt http://localhost:8080/api/properties \
  -H 'content-type: application/json' \
  -d '{
    "title": "Apartment near the sea",
    "city": "Barcelona",
    "accommodationType": "entire_place",
    "bedrooms": 2,
    "sleeps": 4
  }'
```

The authenticated Host Profile is derived from the session. The caller does not provide a profile id as a credential.

### Attach Airbnb listing

```bash
curl -s -b cookies.txt http://localhost:8080/api/properties/<PROPERTY_UUID>/listings \
  -H 'content-type: application/json' \
  -d '{
    "platform": "airbnb",
    "externalId": "123456789"
  }'
```

### Create calendar challenge

```bash
curl -s -b cookies.txt -X POST \
  http://localhost:8080/api/listings/<LISTING_UUID>/challenges
```

Example response:

```json
{
  "id": "...",
  "listingId": "...",
  "kind": "calendar_block",
  "blockDates": ["2026-11-10", "2026-11-12"],
  "leaveAvailable": ["2026-11-11"],
  "expiresAt": "2026-09-19T20:00:00Z",
  "status": "pending"
}
```

The host temporarily makes the real calendar match that pattern. The middle date remains available, which makes accidental matches less likely.

### Manually confirm challenge

For v0 the actual observation is manual. After checking the public calendar:

```bash
curl -s -X POST http://localhost:8080/api/challenges/<CHALLENGE_UUID>/verify \
  -H 'X-Parrot-Admin: dev-admin-token-change-me'
```

This transaction atomically:

1. locks the challenge;
2. checks that it is still pending and unexpired;
3. marks it passed;
4. creates a `controls_listing` verification;
5. sets verification expiry to 30 days.

### Public profile

```bash
curl -s http://localhost:8080/api/p/P669-K7M2Q8XZ
```

The public response contains:

- public profile data;
- properties;
- external listing URLs;
- verification claims and methods;
- whether each verification is still active.

It intentionally does **not** expose private account credentials, password hashes, or session tokens.

## API summary

```text
GET  /health

POST /api/auth/register
POST /api/auth/login
POST /api/auth/logout
GET  /api/auth/me

GET  /api/dashboard                  # authenticated owner dashboard
POST /api/properties                 # authenticated owner
PUT  /api/properties/:propertyId
DELETE /api/properties/:propertyId
GET|POST /api/properties/:propertyId/availability
PUT|DELETE /api/availability/:availabilityId
POST /api/properties/:propertyId/listings
PUT|DELETE /api/listings/:listingId
POST /api/properties/:propertyId/calendars
POST /api/calendars/:calendarId/sync
PUT|DELETE /api/calendars/:calendarId
POST /api/listings/:listingId/challenges

GET  /api/search                     # public
GET  /api/p/:parrotId                # public
POST /api/challenges/:challengeId/verify  # admin only
```


Manual verification still uses:

```text
X-Parrot-Admin: <admin token>
```

## Database migrations

Flyway migrations live in:

```text
src/main/resources/db/migration/
```

The schema is additive through V12. V11 adds `accounts`, server-side `sessions`, account/profile ownership and `password_reset_tokens` storage (now used by password recovery). V12 removes the pre-account edit-token mechanism, deletes any remaining unowned legacy profiles, makes `profiles.account_id` mandatory and drops `access_token_hash`.

Do not rewrite already-applied migrations. Flyway remembers checksums and will quite reasonably complain when humans attempt time travel.

## Build fat JAR

```bash
sbt assembly
```

Output:

```text
target/scala-2.13/parrot669-backend.jar
```

Run:

```bash
java -jar target/scala-2.13/parrot669-backend.jar
```

This shape is convenient for Railway/Fly.io/a small VM. Point `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` at the hosted PostgreSQL instance.

## What is intentionally not here yet

- OAuth;
- general RBAC/roles;
- booking/payment flow;
- reviews;
- automatic Airbnb scraping;
- automatic calendar verification;
- identity verification;
- right-to-rent verification;
- ownership verification.

## Next sensible backend steps

1. Add recovery email delivery metrics and retry handling if operational traffic requires them.
2. Add `identity` and `right_to_rent` as separate claims, never as a generic `verified=true`.
3. Add an admin UI or tiny internal endpoint to list pending challenges.
4. Replace the simple in-process login limiter only if traffic or horizontal scaling makes a distributed limiter worth the complexity.
