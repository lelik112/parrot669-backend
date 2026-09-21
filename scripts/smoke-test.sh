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

property_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/profiles/$profile_id/properties"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"title":"CI Apartment","city":"Barcelona","bedrooms":2}')

property_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$property_json")

listing_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/listings"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"platform":"airbnb","url":"https://www.airbnb.com/rooms/123456789"}')

listing_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$listing_json")

availability_json=$(curl --fail --silent   -X POST "http://localhost:$HTTP_PORT/api/properties/$property_id/availability"   -H 'content-type: application/json'   -H "X-Parrot-Token: $edit_token"   -d '{"from":"2027-01-01","to":"2027-02-28"}')

search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=2")

empty_search_json=$(curl --fail --silent   "http://localhost:$HTTP_PORT/api/search?city=Barcelona&from=2027-01-10&to=2027-01-20&bedrooms=3")

SEARCH_JSON="$search_json" EMPTY_SEARCH_JSON="$empty_search_json" python3 - "$property_id" "$listing_id" <<'PY'
import json
import os
import sys

property_id = sys.argv[1]
listing_id = sys.argv[2]
results = json.loads(os.environ["SEARCH_JSON"])
empty_results = json.loads(os.environ["EMPTY_SEARCH_JSON"])

assert len(results) == 1, results
result = results[0]
assert result["propertyId"] == property_id, result
assert result["city"] == "Barcelona", result
assert result["bedrooms"] == 2, result
assert result["availableFrom"] == "2027-01-01", result
assert result["availableTo"] == "2027-02-28", result
assert result["links"][0]["id"] == listing_id, result
assert empty_results == [], empty_results

print("Availability search passed")
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

AVAILABILITY_JSON="$availability_json" python3 - <<'PY'
import json
import os

data = json.loads(os.environ["AVAILABILITY_JSON"])
assert data["from"] == "2027-01-01", data
assert data["to"] == "2027-02-28", data
print("Availability creation passed")
PY
