#!/usr/bin/env bash
set -euo pipefail

: "${DATABASE_URL:=jdbc:postgresql://localhost:5432/parrot669}"
: "${DATABASE_USER:=parrot}"
: "${DATABASE_PASSWORD:=parrot}"
: "${PARROT_ADMIN_TOKEN:=ci-admin-token}"
: "${HTTP_PORT:=8080}"
: "${APP_ENV:=test}"

export DATABASE_URL DATABASE_USER DATABASE_PASSWORD PARROT_ADMIN_TOKEN HTTP_PORT APP_ENV

# Run the username/session integration tests against isolated test schemas.
TEST_DATABASE_URL="$DATABASE_URL" TEST_DATABASE_USER="$DATABASE_USER" \
  TEST_DATABASE_PASSWORD="$DATABASE_PASSWORD" sbt -batch test

LOG_FILE="${TMPDIR:-/tmp}/parrot669-smoke.log"
COOKIE_JAR=$(mktemp)
OTHER_COOKIE_JAR=$(mktemp)
ICAL_DIR=$(mktemp -d)
mkdir -p "$ICAL_DIR/calendar/ical"
cat >"$ICAL_DIR/calendar/ical/123456789.ics" <<'ICS'
BEGIN:VCALENDAR
PRODID:-//Airbnb Inc//Hosting Calendar 1.0//EN
VERSION:2.0
BEGIN:VEVENT
DTSTART;VALUE=DATE:20270115
DTEND;VALUE=DATE:20270118
SUMMARY:Reserved
UID:reservation-ci@airbnb.com
DESCRIPTION:Reservation URL: https://www.airbnb.com/hosting/reservations/details/CI
END:VEVENT
BEGIN:VEVENT
DTSTART;VALUE=DATE:20270122
DTEND;VALUE=DATE:20270125
SUMMARY:Airbnb (Not available)
UID:not-available-ci@airbnb.com
END:VEVENT
END:VCALENDAR
ICS

python3 -m http.server 18080 --bind 127.0.0.1 --directory "$ICAL_DIR" >/dev/null 2>&1 &
ICAL_SERVER_PID=$!

sbt -batch run >"$LOG_FILE" 2>&1 &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  kill "$ICAL_SERVER_PID" 2>/dev/null || true
  rm -rf "$ICAL_DIR"
  rm -f "$COOKIE_JAR" "$OTHER_COOKIE_JAR"
  pkill -f 'com.parrot669.Main' 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 60); do
  if curl --fail --silent "http://localhost:$HTTP_PORT/health" >/dev/null; then
    break
  fi

  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "Server exited before becoming healthy"
    cat "$LOG_FILE"
    exit 1
  fi

  sleep 2
done

curl --fail --silent "http://localhost:$HTTP_PORT/health" >/dev/null || {
  echo "Server did not become healthy"
  cat "$LOG_FILE"
  exit 1
}

if command -v psql >/dev/null 2>&1; then
  constraint_count=$(PGPASSWORD="$DATABASE_PASSWORD" psql \
    -h localhost \
    -p 5432 \
    -U "$DATABASE_USER" \
    -d parrot669 \
    -Atc "select count(*) from pg_constraint where conname = 'availability_periods_no_overlap' and contype = 'x'")

  test "$constraint_count" = "1"
  echo "Availability exclusion constraint installed"
fi

AUTH_TEST_PASSWORD="ci-auth-password-12345"

register_json=$(curl --fail --silent \
  -X POST "http://localhost:$HTTP_PORT/api/auth/register" \
  -H 'content-type: application/json' \
  -d "{\"email\":\"ci@example.com\",\"password\":\"$AUTH_TEST_PASSWORD\",\"displayName\":\"CI Host\"}")

REGISTER_JSON="$register_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["REGISTER_JSON"])
assert data["email"] == "ci@example.com", data
assert "verify" in data["message"].lower(), data
print("Registration requires email verification")
PY

preverify_login_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -X POST "http://localhost:$HTTP_PORT/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"email\":\"ci@example.com\",\"password\":\"$AUTH_TEST_PASSWORD\"}")
test "$preverify_login_status" = "401"

command -v psql >/dev/null 2>&1 || {
  echo "psql is required for the email-verification smoke test"
  exit 1
}

CI_VERIFY_TOKEN="ci-verification-token"
CI_VERIFY_HASH=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())' "$CI_VERIFY_TOKEN")

PGPASSWORD="$DATABASE_PASSWORD" psql \
  -h localhost \
  -p 5432 \
  -U "$DATABASE_USER" \
  -d parrot669 \
  -v ON_ERROR_STOP=1 \
  -c "update email_verification_tokens t set token_hash = '$CI_VERIFY_HASH' from accounts a where t.account_id = a.id and a.email_normalized = 'ci@example.com' and t.used_at is null;" >/dev/null

verify_json=$(curl --fail --silent -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/auth/verify-email" \
  -H 'content-type: application/json' \
  -d "{\"token\":\"$CI_VERIFY_TOKEN\"}")

profile_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["profile"]["id"])' <<<"$verify_json")
parrot_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["profile"]["parrotId"])' <<<"$verify_json")

me_json=$(curl --fail --silent -b "$COOKIE_JAR" "http://localhost:$HTTP_PORT/api/auth/me")
ME_JSON="$me_json" python3 - "$profile_id" <<'PY'
import json, os, sys
data = json.loads(os.environ["ME_JSON"])
assert data["email"] == "ci@example.com", data
assert data["profile"]["id"] == sys.argv[1], data
print("Verify email and /me passed")
PY

unauthenticated_dashboard_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "http://localhost:$HTTP_PORT/api/dashboard")
test "$unauthenticated_dashboard_status" = "401"

curl --fail --silent -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/auth/logout" --output /dev/null

logged_out_me_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -b "$COOKIE_JAR" "http://localhost:$HTTP_PORT/api/auth/me")
test "$logged_out_me_status" = "401"

wrong_password_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -X POST "http://localhost:$HTTP_PORT/api/auth/login" \
  -H 'content-type: application/json' \
  -d '{"email":"ci@example.com","password":"wrong-password-value"}')
test "$wrong_password_status" = "401"

login_json=$(curl --fail --silent -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"email\":\"CI@EXAMPLE.COM\",\"password\":\"$AUTH_TEST_PASSWORD\"}")

LOGIN_JSON="$login_json" python3 - "$profile_id" <<'PY'
import json, os, sys
data = json.loads(os.environ["LOGIN_JSON"])
assert data["profile"]["id"] == sys.argv[1], data
print("Login passed")
PY

username_login_json=$(curl --fail --silent -c "$COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"login\":\"  ci HOST  \",\"password\":\"$AUTH_TEST_PASSWORD\"}")
LOGIN_JSON="$username_login_json" python3 - "$profile_id" <<'PY'
import json, os, sys
data = json.loads(os.environ["LOGIN_JSON"])
assert data["profile"]["id"] == sys.argv[1], data
assert data["username"] == "CI Host", data
print("Case-insensitive username login passed")
PY

legacy_claim_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -b "$COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/auth/claim-legacy" \
  -H 'content-type: application/json' \
  -d '{"profileId":"11111111-1111-4111-8111-111111111111","editToken":"obsolete"}')
test "$legacy_claim_status" = "404"

legacy_dashboard_alias_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -b "$COOKIE_JAR" "http://localhost:$HTTP_PORT/api/profiles/$profile_id/dashboard")
test "$legacy_dashboard_alias_status" = "404"

property_json=$(curl --fail --silent -b "$COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/properties" \
  -H 'content-type: application/json' \
  -d '{"title":"CI Apartment","city":"Barcelona","accommodationType":"entire_place","bedrooms":2,"sleeps":5}')

property_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$property_json")

curl --fail --silent \
  -X POST "http://localhost:$HTTP_PORT/api/auth/register" \
  -H 'content-type: application/json' \
  -d '{"email":"other@example.com","password":"other-ci-password-12345","displayName":"Other Host"}' >/dev/null

OTHER_VERIFY_TOKEN="other-verification-token"
OTHER_VERIFY_HASH=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())' "$OTHER_VERIFY_TOKEN")

PGPASSWORD="$DATABASE_PASSWORD" psql \
  -h localhost \
  -p 5432 \
  -U "$DATABASE_USER" \
  -d parrot669 \
  -v ON_ERROR_STOP=1 \
  -c "update email_verification_tokens t set token_hash = '$OTHER_VERIFY_HASH' from accounts a where t.account_id = a.id and a.email_normalized = 'other@example.com' and t.used_at is null;" >/dev/null

curl --fail --silent -c "$OTHER_COOKIE_JAR" -b "$OTHER_COOKIE_JAR" \
  -X POST "http://localhost:$HTTP_PORT/api/auth/verify-email" \
  -H 'content-type: application/json' \
  -d "{\"token\":\"$OTHER_VERIFY_TOKEN\"}" >/dev/null

other_owner_update_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -b "$OTHER_COOKIE_JAR" \
  -X PUT "http://localhost:$HTTP_PORT/api/properties/$property_id" \
  -H 'content-type: application/json' \
  -d '{"accommodationType":"entire_place","bedrooms":2,"sleeps":5,"minStayDays":2,"cleaningFeeCents":null}')
test "$other_owner_update_status" = "404"

property_settings_json=$(curl --fail --silent -X PUT "http://localhost:$HTTP_PORT/api/properties/$property_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"accommodationType":"private_room","bedrooms":3,"sleeps":6,"minStayDays":7,"cleaningFeeCents":5500}')

PROPERTY_SETTINGS_JSON="$property_settings_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["PROPERTY_SETTINGS_JSON"])
assert data["accommodationType"] == "private_room", data
assert data["bedrooms"] == 3, data
assert data["sleeps"] == 6, data
assert data["minStayDays"] == 7, data
assert data["cleaningFeeCents"] == 5500, data
print("Property settings update passed")
PY

property_settings_reset_json=$(curl --fail --silent -X PUT "http://localhost:$HTTP_PORT/api/properties/$property_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"accommodationType":"entire_place","bedrooms":2,"sleeps":5,"minStayDays":7,"cleaningFeeCents":5500}')

PROPERTY_SETTINGS_RESET_JSON="$property_settings_reset_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["PROPERTY_SETTINGS_RESET_JSON"])
assert data["accommodationType"] == "entire_place", data
assert data["bedrooms"] == 2, data
assert data["sleeps"] == 5, data
print("Property characteristics edit passed")
PY

listing_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/listings"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"platform":"airbnb","externalId":"123456789"}')

listing_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$listing_json")

availability_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-01-01","to":"2027-02-28","nightlyPriceCents":10000}')

availability_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$availability_json")

availability_list_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -b "$COOKIE_JAR")

dashboard_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/dashboard"   -b "$COOKIE_JAR")

DASHBOARD_JSON="$dashboard_json" python3 - "$property_id" "$listing_id" "$availability_id" <<'PY'
import json, os, sys
property_id, listing_id, availability_id = sys.argv[1:4]
data = json.loads(os.environ["DASHBOARD_JSON"])
assert data["profile"]["displayName"] == "CI Host", data
assert len(data["properties"]) == 1, data
p = data["properties"][0]
assert p["id"] == property_id, p
assert p["title"] == "CI Apartment", p
assert p["accommodationType"] == "entire_place", p
assert p["cleaningFeeCents"] == 5500, p
assert p["minStayDays"] == 7, p
assert p["listings"][0]["id"] == listing_id, p
assert p["listings"][0]["cleaningFeeCents"] is None, p
assert p["listings"][0]["showInSearch"] is True, p
assert p["availability"][0]["id"] == availability_id, p
assert p["availability"][0]["nightlyPriceCents"] == 10000, p
print("Host dashboard passed")
PY

AVAILABILITY_LIST_JSON="$availability_list_json" python3 - "$availability_id" <<'PY'
import json
import os
import sys

availability_id = sys.argv[1]
data = json.loads(os.environ["AVAILABILITY_LIST_JSON"])
assert len(data) == 1, data
assert data[0]["id"] == availability_id, data
assert data[0]["from"] == "2027-01-01", data
assert data[0]["to"] == "2027-02-28", data
assert data[0]["nightlyPriceCents"] == 10000, data
print("Availability listing passed")
PY

wrong_update_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X PUT "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -H 'content-type: application/json'   -H 'X-Parrot-Token: definitely-wrong-token'   -d '{"from":"2027-01-05","to":"2027-03-05","nightlyPriceCents":10000}')

test "$wrong_update_status" = "401"

updated_availability_json=$(curl --fail --silent   -X PUT "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-01-05","to":"2027-03-05","nightlyPriceCents":10000}')

UPDATED_AVAILABILITY_JSON="$updated_availability_json" python3 - "$availability_id" <<'PY'
import json
import os
import sys

availability_id = sys.argv[1]
data = json.loads(os.environ["UPDATED_AVAILABILITY_JSON"])
assert data["id"] == availability_id, data
assert data["from"] == "2027-01-05", data
assert data["to"] == "2027-03-05", data
assert data["nightlyPriceCents"] == 10000, data
print("Availability update passed")
PY

hidden_listing_json=$(curl --fail --silent -X PUT "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"showInSearch":false}')

hidden_link_search_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

HIDDEN_LISTING_JSON="$hidden_listing_json" HIDDEN_LINK_SEARCH_JSON="$hidden_link_search_json" python3 - <<'PY'
import json, os
listing = json.loads(os.environ["HIDDEN_LISTING_JSON"])
search = json.loads(os.environ["HIDDEN_LINK_SEARCH_JSON"])
assert listing["showInSearch"] is False, listing
assert len(search) == 1, search
assert search[0]["links"] == [], search
print("External listing search visibility passed")
PY

curl --fail --silent -X PUT "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"showInSearch":true}' >/dev/null

search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

updated_boundary_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-05&to=2027-03-05&bedrooms=2&sleeps=4")

old_left_boundary_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-01&to=2027-01-20&bedrooms=2&sleeps=4")

outside_right_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-20&to=2027-03-06&bedrooms=2&sleeps=4")

too_many_bedrooms_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=3&sleeps=4")

too_many_guests_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=6")

too_short_stay_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-15&bedrooms=2&sleeps=4")

minimum_stay_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-17&bedrooms=2&sleeps=4")

entire_place_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4&accommodationType=entire_place")

private_room_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4&accommodationType=private_room")

invalid_accommodation_type_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4&accommodationType=castle")

test "$invalid_accommodation_type_status" = "400"

zero_night_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-23&to=2027-01-23&bedrooms=2&sleeps=4")

test "$zero_night_status" = "400"

SEARCH_JSON="$search_json" UPDATED_BOUNDARY_JSON="$updated_boundary_json" OLD_LEFT_BOUNDARY_JSON="$old_left_boundary_json" OUTSIDE_RIGHT_JSON="$outside_right_json" TOO_MANY_BEDROOMS_JSON="$too_many_bedrooms_json" TOO_MANY_GUESTS_JSON="$too_many_guests_json" TOO_SHORT_STAY_JSON="$too_short_stay_json" MINIMUM_STAY_JSON="$minimum_stay_json" ENTIRE_PLACE_JSON="$entire_place_json" PRIVATE_ROOM_JSON="$private_room_json" python3 - "$property_id" "$listing_id" <<'PY'
import json
import os
import sys

property_id = sys.argv[1]
listing_id = sys.argv[2]

results = json.loads(os.environ["SEARCH_JSON"])
updated_boundary = json.loads(os.environ["UPDATED_BOUNDARY_JSON"])
old_left_boundary = json.loads(os.environ["OLD_LEFT_BOUNDARY_JSON"])
outside_right = json.loads(os.environ["OUTSIDE_RIGHT_JSON"])
too_many_bedrooms = json.loads(os.environ["TOO_MANY_BEDROOMS_JSON"])
too_many_guests = json.loads(os.environ["TOO_MANY_GUESTS_JSON"])
too_short_stay = json.loads(os.environ["TOO_SHORT_STAY_JSON"])
minimum_stay = json.loads(os.environ["MINIMUM_STAY_JSON"])
entire_place = json.loads(os.environ["ENTIRE_PLACE_JSON"])
private_room = json.loads(os.environ["PRIVATE_ROOM_JSON"])

assert len(results) == 1, results
result = results[0]
assert result["propertyId"] == property_id, result
assert result["propertyTitle"] == "CI Apartment", result
assert result["ownerDisplayName"] == "CI Host", result
assert result["city"] == "Barcelona", result
assert result["accommodationType"] == "entire_place", result
assert result["bedrooms"] == 2, result
assert result["sleeps"] == 5, result
assert result["minStayDays"] == 7, result
assert result["links"][0]["externalId"] == "123456789", result
assert result["links"][0]["url"] == "https://www.airbnb.com/rooms/123456789", result
assert result["links"][0]["cleaningFeeCents"] is None, result
assert result["price"]["currency"] == "EUR", result
assert result["price"]["nights"] == 10, result
assert result["price"]["nightlySubtotalCents"] == 100000, result
assert result["price"]["cleaningFeeCents"] == 5500, result
assert result["price"]["estimatedAmountCents"] == 105500, result
assert result["availableFrom"] == "2027-01-10", result
assert result["availableTo"] == "2027-01-20", result
assert result["links"][0]["id"] == listing_id, result

assert len(updated_boundary) == 1, updated_boundary
assert old_left_boundary == [], old_left_boundary
assert outside_right == [], outside_right
assert too_many_bedrooms == [], too_many_bedrooms
assert too_many_guests == [], too_many_guests
assert too_short_stay == [], too_short_stay
assert len(minimum_stay) == 1, minimum_stay
assert len(entire_place) == 1, entire_place
assert private_room == [], private_room

print("Updated availability search passed")
PY

wrong_calendar_listing_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/calendars"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"provider":"airbnb","icalUrl":"http://127.0.0.1:18080/calendar/ical/23456789.ics?t=ci-secret"}')

test "$wrong_calendar_listing_status" = "400"

localized_calendar_mismatch_json=$(curl --silent -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/calendars"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"provider":"airbnb","icalUrl":"https://www.airbnb.ru/calendar/ical/23456789.ics?t=ci-secret"}')

LOCALIZED_CALENDAR_MISMATCH_JSON="$localized_calendar_mismatch_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["LOCALIZED_CALENDAR_MISMATCH_JSON"])
assert data["error"] == "Airbnb calendar listing id does not match this property's Airbnb listing", data
print("Localized Airbnb calendar URL validation passed")
PY

lookalike_airbnb_host_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/calendars"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"provider":"airbnb","icalUrl":"https://airbnb.com.evil.invalid/calendar/ical/123456789.ics?t=ci-secret"}')

test "$lookalike_airbnb_host_status" = "400"

calendar_json=$(curl --fail --silent -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/calendars"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"provider":"airbnb","icalUrl":"http://127.0.0.1:18080/calendar/ical/123456789.ics?t=ci-secret"}')

calendar_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$calendar_json")

CALENDAR_JSON="$calendar_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["CALENDAR_JSON"])
assert data["provider"] == "airbnb", data
assert data["status"] == "connected", data
assert len(data["reservationBlocks"]) == 1, data
assert data["reservationBlocks"][0]["from"] == "2027-01-15", data
assert data["reservationBlocks"][0]["to"] == "2027-01-18", data
assert data["platformUnavailableCount"] == 1, data
assert data["unknownCount"] == 0, data
assert "icalUrl" not in data, data
print("Airbnb iCal connection passed")
PY

reserved_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

platform_unavailable_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-20&to=2027-01-30&bedrooms=2&sleeps=4")

RESERVED_SEARCH_JSON="$reserved_search_json" PLATFORM_UNAVAILABLE_SEARCH_JSON="$platform_unavailable_search_json" python3 - <<'PY'
import json, os
reserved = json.loads(os.environ["RESERVED_SEARCH_JSON"])
platform_unavailable = json.loads(os.environ["PLATFORM_UNAVAILABLE_SEARCH_JSON"])
assert reserved == [], reserved
assert len(platform_unavailable) == 1, platform_unavailable
print("Airbnb reservation blocking semantics passed")
PY

disabled_calendar_json=$(curl --fail --silent -X PUT   "http://localhost:$HTTP_PORT/api/calendars/$calendar_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"enabled":false}')

disabled_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

DISABLED_CALENDAR_JSON="$disabled_calendar_json" DISABLED_SEARCH_JSON="$disabled_search_json" python3 - <<'PY'
import json, os
calendar = json.loads(os.environ["DISABLED_CALENDAR_JSON"])
search = json.loads(os.environ["DISABLED_SEARCH_JSON"])
assert calendar["enabled"] is False, calendar
assert len(search) == 1, search
print("Disabled calendar no longer blocks search")
PY

enabled_calendar_json=$(curl --fail --silent -X PUT   "http://localhost:$HTTP_PORT/api/calendars/$calendar_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"enabled":true}')

enabled_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

ENABLED_CALENDAR_JSON="$enabled_calendar_json" ENABLED_SEARCH_JSON="$enabled_search_json" python3 - <<'PY'
import json, os
calendar = json.loads(os.environ["ENABLED_CALENDAR_JSON"])
search = json.loads(os.environ["ENABLED_SEARCH_JSON"])
assert calendar["enabled"] is True, calendar
assert search == [], search
print("Re-enabled calendar blocks search again")
PY

calendar_dashboard_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/dashboard"   -b "$COOKIE_JAR")

CALENDAR_DASHBOARD_JSON="$calendar_dashboard_json" python3 - "$calendar_id" <<'PY'
import json, os, sys
calendar_id = sys.argv[1]
data = json.loads(os.environ["CALENDAR_DASHBOARD_JSON"])
calendars = data["properties"][0]["calendars"]
assert len(calendars) == 1, calendars
assert calendars[0]["id"] == calendar_id, calendars
assert calendars[0]["status"] == "connected", calendars
assert len(calendars[0]["reservationBlocks"]) == 1, calendars
print("Calendar dashboard passed")
PY

kill "$ICAL_SERVER_PID"
wait "$ICAL_SERVER_PID" 2>/dev/null || true

failed_reconnect_json=$(curl --fail --silent -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/calendars"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"provider":"airbnb","icalUrl":"http://127.0.0.1:18080/calendar/ical/123456789.ics?t=ci-secret"}')

FAILED_RECONNECT_JSON="$failed_reconnect_json" python3 - "$calendar_id" <<'PY'
import json, os, sys
calendar_id = sys.argv[1]
data = json.loads(os.environ["FAILED_RECONNECT_JSON"])
assert data["id"] == calendar_id, data
assert data["status"] == "error", data
assert data["lastSuccessAt"] is not None, data
assert len(data["reservationBlocks"]) == 1, data
assert data["reservationBlocks"][0]["from"] == "2027-01-15", data
assert data["reservationBlocks"][0]["to"] == "2027-01-18", data
print("Failed reconnect preserves the last good calendar snapshot")
PY

preserved_reservation_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

PRESERVED_RESERVATION_SEARCH_JSON="$preserved_reservation_search_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["PRESERVED_RESERVATION_SEARCH_JSON"])
assert data == [], data
print("Last good reservation snapshot still blocks search")
PY

wrong_delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X DELETE "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -H 'X-Parrot-Token: definitely-wrong-token')

test "$wrong_delete_status" = "401"

curl --fail --silent   -X DELETE "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -b "$COOKIE_JAR"   --output /dev/null

after_delete_list_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -b "$COOKIE_JAR")

after_delete_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

AFTER_DELETE_LIST_JSON="$after_delete_list_json" AFTER_DELETE_SEARCH_JSON="$after_delete_search_json" python3 - <<'PY'
import json
import os

periods = json.loads(os.environ["AFTER_DELETE_LIST_JSON"])
search = json.loads(os.environ["AFTER_DELETE_SEARCH_JSON"])
assert periods == [], periods
assert search == [], search
print("Availability deletion passed")
PY

period_a_json=$(curl --fail --silent -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-04-01","to":"2027-04-05","nightlyPriceCents":11000}')

period_a_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$period_a_json")

period_b_json=$(curl --fail --silent -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-04-05","to":"2027-04-10","nightlyPriceCents":12000}')

period_b_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$period_b_json")

adjacent_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4&pricedOnly=true")

ADJACENT_SEARCH_JSON="$adjacent_search_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["ADJACENT_SEARCH_JSON"])
assert len(data) == 1, data
price = data[0]["price"]
assert price["nights"] == 7, price
assert price["nightlySubtotalCents"] == 81000, price
assert price["estimatedAmountCents"] == 86500, price
print("Adjacent availability search passed")
PY

overlap_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-04-04","to":"2027-04-06","nightlyPriceCents":9999}')

test "$overlap_status" = "409"

curl --fail --silent -X PUT   "http://localhost:$HTTP_PORT/api/availability/$period_b_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-04-05","to":"2027-04-10","nightlyPriceCents":null}'   >/dev/null

all_prices_missing_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4")

priced_only_missing_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4&pricedOnly=true")

ALL_PRICES_MISSING_JSON="$all_prices_missing_json" PRICED_ONLY_MISSING_JSON="$priced_only_missing_json" python3 - <<'PY'
import json, os
all_results = json.loads(os.environ["ALL_PRICES_MISSING_JSON"])
priced = json.loads(os.environ["PRICED_ONLY_MISSING_JSON"])
assert len(all_results) == 1, all_results
assert all_results[0]["price"] is None, all_results
assert priced == [], priced
print("Incomplete price behavior passed")
PY

sort_property_json=$(curl --fail --silent -X POST "http://localhost:$HTTP_PORT/api/properties"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"title":"Budget Apartment","city":"Barcelona","accommodationType":"entire_place","bedrooms":2,"sleeps":4}')

sort_property_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$sort_property_json")

curl --fail --silent -X POST "http://localhost:$HTTP_PORT/api/properties/$sort_property_id/availability"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"from":"2027-04-02","to":"2027-04-09","nightlyPriceCents":9000}' >/dev/null

sorted_price_search_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4")
price_range_search_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4&minPriceCents=62000&maxPriceCents=64000")
price_range_empty_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4&minPriceCents=64001")

SORTED_PRICE_SEARCH_JSON="$sorted_price_search_json" PRICE_RANGE_SEARCH_JSON="$price_range_search_json" PRICE_RANGE_EMPTY_JSON="$price_range_empty_json" python3 - "$sort_property_id" "$property_id" <<'PY'
import json, os, sys
priced_id, unpriced_id = sys.argv[1:3]
sorted_results = json.loads(os.environ["SORTED_PRICE_SEARCH_JSON"])
ranged = json.loads(os.environ["PRICE_RANGE_SEARCH_JSON"])
empty = json.loads(os.environ["PRICE_RANGE_EMPTY_JSON"])

assert len(sorted_results) == 2, sorted_results
assert sorted_results[0]["propertyId"] == priced_id, sorted_results
assert sorted_results[0]["price"]["estimatedAmountCents"] == 63000, sorted_results
assert sorted_results[1]["propertyId"] == unpriced_id, sorted_results
assert sorted_results[1]["price"] is None, sorted_results

assert len(ranged) == 1, ranged
assert ranged[0]["propertyId"] == priced_id, ranged
assert empty == [], empty
print("Price sorting and range filters passed")
PY

curl --fail --silent -X DELETE "http://localhost:$HTTP_PORT/api/properties/$sort_property_id"   -b "$COOKIE_JAR"   --output /dev/null

challenge_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/listings/$listing_id/challenges"   -b "$COOKIE_JAR")

challenge_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$challenge_json")

verification_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/challenges/$challenge_id/verify"   -H "X-Parrot-Admin: $PARROT_ADMIN_TOKEN")

public_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/p/$parrot_id")

PUBLIC_JSON="$public_json" python3 - "$listing_id" "$parrot_id" <<'PY'
import json
import os
import sys

listing_id = sys.argv[1]
parrot_id = sys.argv[2]
data = json.loads(os.environ["PUBLIC_JSON"])

assert data["profile"]["parrotId"] == parrot_id, data
assert len(data["properties"]) == 1, data
assert data["properties"][0]["bedrooms"] == 2, data
assert data["properties"][0]["sleeps"] == 5, data
assert data["properties"][0]["minStayDays"] == 7, data
assert data["properties"][0]["accommodationType"] == "entire_place", data
assert data["properties"][0]["listings"][0]["id"] == listing_id, data

claims = data["verifications"]
assert len(claims) == 1, data
claim = claims[0]
assert claim["claim"] == "controls_listing", data
assert claim["method"] == "calendar_challenge", data
assert claim["active"] is True, data

print("Verification profile passed")
PY

VERIFICATION_JSON="$verification_json" python3 - <<'PY'
import json
import os

data = json.loads(os.environ["VERIFICATION_JSON"])
assert data["claim"] == "controls_listing", data
assert data["method"] == "calendar_challenge", data
print("Verification response passed")
PY

updated_property_json=$(curl --fail-with-body --silent -X PUT   "http://localhost:$HTTP_PORT/api/properties/$property_id"   -H 'content-type: application/json'   -b "$COOKIE_JAR"   -d '{"accommodationType":"entire_place","bedrooms":2,"sleeps":5,"minStayDays":7,"cleaningFeeCents":6500}')

UPDATED_PROPERTY_JSON="$updated_property_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["UPDATED_PROPERTY_JSON"])
assert data["cleaningFeeCents"] == 6500, data
print("Property cleaning fee update passed")
PY

wrong_listing_delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X DELETE "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -H 'X-Parrot-Token: definitely-wrong-token')

test "$wrong_listing_delete_status" = "401"

curl --fail --silent   -X DELETE "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -b "$COOKIE_JAR"   --output /dev/null

after_listing_delete_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/p/$parrot_id")
after_listing_delete_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-02&to=2027-04-09&bedrooms=2&sleeps=4")
after_listing_delete_dashboard_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/dashboard"   -b "$COOKIE_JAR")

AFTER_LISTING_DELETE_JSON="$after_listing_delete_json" AFTER_LISTING_DELETE_SEARCH_JSON="$after_listing_delete_search_json" AFTER_LISTING_DELETE_DASHBOARD_JSON="$after_listing_delete_dashboard_json" python3 - "$property_id" <<'PY'
import json, os
import sys
data = json.loads(os.environ["AFTER_LISTING_DELETE_JSON"])
search = json.loads(os.environ["AFTER_LISTING_DELETE_SEARCH_JSON"])
dashboard = json.loads(os.environ["AFTER_LISTING_DELETE_DASHBOARD_JSON"])
assert data["properties"][0]["listings"] == [], data
assert dashboard["properties"][0]["calendars"] == [], dashboard
assert len(search) == 1, search
assert search[0]["propertyId"] == sys.argv[1], search
assert search[0]["links"] == [], search
print("External listing deletion passed")
PY

wrong_property_delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X DELETE "http://localhost:$HTTP_PORT/api/properties/$property_id"   -H 'X-Parrot-Token: definitely-wrong-token')

test "$wrong_property_delete_status" = "401"

curl --fail --silent   -X DELETE "http://localhost:$HTTP_PORT/api/properties/$property_id"   -b "$COOKIE_JAR"   --output /dev/null

after_property_delete_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/p/$parrot_id")
AFTER_PROPERTY_DELETE_JSON="$after_property_delete_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["AFTER_PROPERTY_DELETE_JSON"])
assert data["properties"] == [], data
print("Property deletion passed")
PY
