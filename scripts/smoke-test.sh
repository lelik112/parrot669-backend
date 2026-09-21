#!/usr/bin/env bash
set -euo pipefail

: "${DATABASE_URL:=jdbc:postgresql://localhost:5432/parrot669}"
: "${DATABASE_USER:=parrot}"
: "${DATABASE_PASSWORD:=parrot}"
: "${PARROT_ADMIN_TOKEN:=ci-admin-token}"
: "${HTTP_PORT:=8080}"
: "${APP_ENV:=test}"

export DATABASE_URL DATABASE_USER DATABASE_PASSWORD PARROT_ADMIN_TOKEN HTTP_PORT APP_ENV

LOG_FILE="${TMPDIR:-/tmp}/parrot669-smoke.log"

sbt -batch run >"$LOG_FILE" 2>&1 &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
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

profile_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/profiles"   -H 'content-type: application/json'   -d '{"displayName":"CI Host","contact":"ci@example.com"}')

profile_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$profile_json")
parrot_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["profile"]["parrotId"])' <<<"$profile_json")
edit_token=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["editToken"])' <<<"$profile_json")

property_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/profiles/$profile_id/properties"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"title":"CI Apartment","city":"Barcelona","bedrooms":2,"sleeps":5,"minStayDays":7}')

property_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$property_json")

listing_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/listings"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"platform":"airbnb","externalId":"123456789","cleaningFeeCents":5500}')

listing_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$listing_json")

availability_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-01-01","to":"2027-02-28","nightlyPriceCents":10000}')

availability_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$availability_json")

availability_list_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H "X-Parrot-Token: $edit_token")

dashboard_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/profiles/$profile_id/dashboard"   -H "X-Parrot-Token: $edit_token")

DASHBOARD_JSON="$dashboard_json" python3 - "$property_id" "$listing_id" "$availability_id" <<'PY'
import json, os, sys
property_id, listing_id, availability_id = sys.argv[1:4]
data = json.loads(os.environ["DASHBOARD_JSON"])
assert data["profile"]["displayName"] == "CI Host", data
assert len(data["properties"]) == 1, data
p = data["properties"][0]
assert p["id"] == property_id, p
assert p["title"] == "CI Apartment", p
assert p["listings"][0]["id"] == listing_id, p
assert p["listings"][0]["cleaningFeeCents"] == 5500, p
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

updated_availability_json=$(curl --fail --silent   -X PUT "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-01-05","to":"2027-03-05","nightlyPriceCents":10000}')

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

search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=4")

updated_boundary_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-05&to=2027-03-05&bedrooms=2&sleeps=4")

old_left_boundary_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-01&to=2027-01-20&bedrooms=2&sleeps=4")

outside_right_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-20&to=2027-03-06&bedrooms=2&sleeps=4")

too_many_bedrooms_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=3&sleeps=4")

too_many_guests_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2&sleeps=6")

too_short_stay_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-15&bedrooms=2&sleeps=4")

minimum_stay_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-17&bedrooms=2&sleeps=4")

zero_night_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-23&to=2027-01-23&bedrooms=2&sleeps=4")

test "$zero_night_status" = "400"

SEARCH_JSON="$search_json" UPDATED_BOUNDARY_JSON="$updated_boundary_json" OLD_LEFT_BOUNDARY_JSON="$old_left_boundary_json" OUTSIDE_RIGHT_JSON="$outside_right_json" TOO_MANY_BEDROOMS_JSON="$too_many_bedrooms_json" TOO_MANY_GUESTS_JSON="$too_many_guests_json" TOO_SHORT_STAY_JSON="$too_short_stay_json" MINIMUM_STAY_JSON="$minimum_stay_json" python3 - "$property_id" "$listing_id" <<'PY'
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

assert len(results) == 1, results
result = results[0]
assert result["propertyId"] == property_id, result
assert result["propertyTitle"] == "CI Apartment", result
assert result["ownerDisplayName"] == "CI Host", result
assert result["city"] == "Barcelona", result
assert result["bedrooms"] == 2, result
assert result["sleeps"] == 5, result
assert result["minStayDays"] == 7, result
assert result["links"][0]["externalId"] == "123456789", result
assert result["links"][0]["url"] == "https://www.airbnb.com/rooms/123456789", result
assert result["links"][0]["cleaningFeeCents"] == 5500, result
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

print("Updated availability search passed")
PY

wrong_delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X DELETE "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -H 'X-Parrot-Token: definitely-wrong-token')

test "$wrong_delete_status" = "401"

curl --fail --silent   -X DELETE "http://localhost:$HTTP_PORT/api/availability/$availability_id"   -H "X-Parrot-Token: $edit_token"   --output /dev/null

after_delete_list_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H "X-Parrot-Token: $edit_token")

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

period_a_json=$(curl --fail --silent -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-04-01","to":"2027-04-05","nightlyPriceCents":11000}')

period_a_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$period_a_json")

period_b_json=$(curl --fail --silent -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-04-05","to":"2027-04-10","nightlyPriceCents":12000}')

period_b_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$period_b_json")

adjacent_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-03&to=2027-04-08&bedrooms=2&sleeps=4&pricedOnly=true")

ADJACENT_SEARCH_JSON="$adjacent_search_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["ADJACENT_SEARCH_JSON"])
assert len(data) == 1, data
price = data[0]["price"]
assert price["nights"] == 5, price
assert price["nightlySubtotalCents"] == 58000, price
assert price["estimatedAmountCents"] == 63500, price
print("Adjacent availability search passed")
PY

overlap_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST   "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-04-04","to":"2027-04-06","nightlyPriceCents":9999}')

test "$overlap_status" = "409"

curl --fail --silent -X PUT   "http://localhost:$HTTP_PORT/api/availability/$period_b_id"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-04-05","to":"2027-04-10","nightlyPriceCents":null}'   >/dev/null

all_prices_missing_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-03&to=2027-04-08&bedrooms=2&sleeps=4")

priced_only_missing_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-04-03&to=2027-04-08&bedrooms=2&sleeps=4&pricedOnly=true")

ALL_PRICES_MISSING_JSON="$all_prices_missing_json" PRICED_ONLY_MISSING_JSON="$priced_only_missing_json" python3 - <<'PY'
import json, os
all_results = json.loads(os.environ["ALL_PRICES_MISSING_JSON"])
priced = json.loads(os.environ["PRICED_ONLY_MISSING_JSON"])
assert len(all_results) == 1, all_results
assert all_results[0]["price"] is None, all_results
assert priced == [], priced
print("Incomplete price behavior passed")
PY

challenge_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/listings/$listing_id/challenges"   -H "X-Parrot-Token: $edit_token")

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

updated_listing_json=$(curl --fail --silent -X PUT   "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"cleaningFeeCents":6500}')

UPDATED_LISTING_JSON="$updated_listing_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["UPDATED_LISTING_JSON"])
assert data["cleaningFeeCents"] == 6500, data
print("Listing cleaning fee update passed")
PY

wrong_listing_delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X DELETE "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -H 'X-Parrot-Token: definitely-wrong-token')

test "$wrong_listing_delete_status" = "401"

curl --fail --silent   -X DELETE "http://localhost:$HTTP_PORT/api/listings/$listing_id"   -H "X-Parrot-Token: $edit_token"   --output /dev/null

after_listing_delete_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/p/$parrot_id")
AFTER_LISTING_DELETE_JSON="$after_listing_delete_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["AFTER_LISTING_DELETE_JSON"])
assert data["properties"][0]["listings"] == [], data
print("External listing deletion passed")
PY

wrong_property_delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}'   -X DELETE "http://localhost:$HTTP_PORT/api/properties/$property_id"   -H 'X-Parrot-Token: definitely-wrong-token')

test "$wrong_property_delete_status" = "401"

curl --fail --silent   -X DELETE "http://localhost:$HTTP_PORT/api/properties/$property_id"   -H "X-Parrot-Token: $edit_token"   --output /dev/null

after_property_delete_json=$(curl --fail --silent "http://localhost:$HTTP_PORT/api/p/$parrot_id")
AFTER_PROPERTY_DELETE_JSON="$after_property_delete_json" python3 - <<'PY'
import json, os
data = json.loads(os.environ["AFTER_PROPERTY_DELETE_JSON"])
assert data["properties"] == [], data
print("Property deletion passed")
PY
