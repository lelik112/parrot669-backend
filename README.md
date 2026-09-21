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

## Domain in v0

```text
Profile
  └─ Property
       └─ ExternalListing (Airbnb for now)
            └─ VerificationChallenge

Profile + ExternalListing
  └─ Verification(claim = controls_listing)
```

A profile gets a human-readable ID like:

```text
P669-K7M2Q8XZ
```

The profile owner also receives a high-entropy edit token once at creation time. Only its SHA-256 hash is stored in PostgreSQL. We do not need passwords/OAuth yet merely to prove that computers remain capable of making simple ideas complicated.

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

### Create profile

```bash
curl -s http://localhost:8080/api/profiles \
  -H 'content-type: application/json' \
  -d '{
    "displayName": "Alex",
    "contact": "alex@example.com"
  }'
```

Response shape:

```json
{
  "id": "<PROFILE_UUID>",
  "profile": {
    "parrotId": "P669-K7M2Q8XZ",
    "displayName": "Alex",
    "createdAt": "2026-09-19T18:00:00Z"
  },
  "editToken": "...returned once..."
}
```

Keep `editToken`. It is deliberately **not** exposed by the public profile endpoint. The returned top-level `id` is the internal profile UUID used by owner mutation URLs.

### Create property

```bash
curl -s http://localhost:8080/api/profiles/<PROFILE_UUID>/properties \
  -H 'content-type: application/json' \
  -H 'X-Parrot-Token: <EDIT_TOKEN>' \
  -d '{
    "title": "Apartment near the sea",
    "city": "Barcelona"
  }'
```

### Attach Airbnb listing

```bash
curl -s http://localhost:8080/api/properties/<PROPERTY_UUID>/listings \
  -H 'content-type: application/json' \
  -H 'X-Parrot-Token: <EDIT_TOKEN>' \
  -d '{
    "platform": "airbnb",
    "url": "https://www.airbnb.com/rooms/123456789"
  }'
```

### Create calendar challenge

```bash
curl -s -X POST http://localhost:8080/api/listings/<LISTING_UUID>/challenges \
  -H 'X-Parrot-Token: <EDIT_TOKEN>'
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

It intentionally does **not** expose the private contact field or edit-token hash.

## API summary

```text
GET  /health
POST /api/profiles
POST /api/profiles/:profileId/properties
POST /api/properties/:propertyId/listings
POST /api/listings/:listingId/challenges
POST /api/challenges/:challengeId/verify   # admin only
GET  /api/p/:parrotId                     # public
```

Owner mutation endpoints use:

```text
X-Parrot-Token: <edit token>
```

Manual verification uses:

```text
X-Parrot-Admin: <admin token>
```

## Database migrations

Flyway migrations live in:

```text
src/main/resources/db/migration/
```

Current migration:

```text
V1__init.sql
```

It creates:

- `profiles`
- `properties`
- `external_listings`
- `verification_challenges`
- `verifications`
- FK constraints and useful indexes

Future changes should be additive migrations, for example:

```text
V2__identity_verification.sql
V3__right_to_rent_claim.sql
```

Do not rewrite V1 after it has been applied anywhere persistent. Flyway remembers checksums and will quite reasonably complain when humans attempt time travel.

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

- accounts/password login;
- OAuth;
- public catalogue/search;
- messaging;
- booking/payment flow;
- reviews;
- automatic Airbnb scraping;
- automatic calendar verification;
- identity verification;
- right-to-rent verification;
- ownership verification.

Those are later claims/features. First we need one owner to create a profile, attach a real listing and prove one thing.

## Next sensible backend steps

1. Add tests with Testcontainers/Postgres.
2. Add `identity` and `right_to_rent` as separate claims, never as a generic `verified=true`.
3. Add an admin UI or tiny internal endpoint to list pending challenges.
4. Only then automate calendar observation if manual verification becomes annoying enough to deserve code.
