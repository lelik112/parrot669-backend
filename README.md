# PARROT 669 backend

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

`Account` is the login identity. It has a normalized unique email, an Argon2id password hash and an email-verification timestamp. A Host Profile is the domain identity shown to guests and owns properties. For the current MVP, one account owns one Host Profile.

A profile still gets a human-readable ID like:

```text
P669-K7M2Q8XZ
```

Owner authorization is ownership-based, not role-based: the authenticated session resolves an Account and its Host Profile, and owner mutations verify that the target resource belongs to that profile. Guest search is public and "host" is not an RBAC role. The existing `PARROT_ADMIN_TOKEN` remains a separate technical mechanism for the internal verification endpoint.

New registrations must confirm their email before receiving a session. Email-verification tokens are opaque 256-bit random values, stored only as SHA-256 hashes, expire after 24 hours and are one-time use. A successful verification creates the first session. Sessions use opaque 256-bit random tokens in an HttpOnly, SameSite=Lax cookie; production cookies are also Secure. PostgreSQL stores only SHA-256 hashes of session tokens. Sessions expire after 30 days, multiple active sessions are allowed, and logout invalidates the current server-side session.

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
curl -s http://localhost:8080/api/auth/register \
  -H 'content-type: application/json' \
  -d '{
    "email": "alex@example.com",
    "password": "correct-horse-battery-staple",
    "displayName": "Alex"
  }'
```

Registration returns `202 Accepted` and does not create a session. Production sends a verification email; test/dev without provider credentials logs the verification URL.

The verification link opens `/host.html#verify=<TOKEN>`. The host UI posts the token to:

```bash
curl -s -c cookies.txt http://localhost:8080/api/auth/verify-email \
  -H 'content-type: application/json' \
  -d '{"token":"<TOKEN_FROM_EMAIL>"}'
```

Successful verification marks the account verified, consumes the token and creates the first session. The token cannot be reused.

Resend uses a deliberately generic response to avoid exposing account existence:

```bash
curl -s http://localhost:8080/api/auth/resend-verification \
  -H 'content-type: application/json' \
  -d '{"email":"alex@example.com"}'
```

After verification:

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
POST /api/auth/verify-email
POST /api/auth/resend-verification
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

The schema is additive through V13. V11 adds `accounts`, server-side `sessions`, account/profile ownership and reserved `password_reset_tokens` storage. V12 removes the pre-account edit-token mechanism. V13 adds `accounts.email_verified_at` and hashed, expiring, one-time email-verification tokens; existing accounts are grandfathered as verified.

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

- password-reset request/confirm endpoints (the V11 reset-token table is reserved and can reuse the transactional email sender);
- OAuth;
- general RBAC/roles;
- messaging;
- booking/payment flow;
- reviews;
- automatic Airbnb scraping;
- automatic calendar verification;
- identity verification;
- right-to-rent verification;
- ownership verification.

## Next sensible backend steps

1. Add password-reset request/confirm endpoints using the existing transactional email sender.
2. Add `identity` and `right_to_rent` as separate claims, never as a generic `verified=true`.
3. Add an admin UI or tiny internal endpoint to list pending challenges.
4. Replace the simple in-process login limiter only if traffic or horizontal scaling makes a distributed limiter worth the complexity.
