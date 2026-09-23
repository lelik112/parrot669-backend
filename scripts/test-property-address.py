"""Property address round-trip on the disposable smoke database; no provider calls."""
import copy
import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

base, owner_jar, other_jar = sys.argv[1:]

def session(path):
    jar = http.cookiejar.MozillaCookieJar(path)
    jar.load(ignore_discard=True, ignore_expires=True)
    return "; ".join(c.name + "=" + c.value for c in jar if c.name == "parrot_session")

owner, other = session(owner_jar), session(other_jar)

def api(path, method="GET", body=None, cookie=owner, expected=200):
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    request = urllib.request.Request(base + "/api" + path, method=method, headers=headers,
        data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.status == expected, (method, path, response.status, raw)
        return json.loads(raw) if raw else None

address = dict(address="10 Rue de Rivoli, Paris", countryCode="fr", country="France",
    city="Paris", latitude=48.855, longitude=2.36, placeId="ci-paris")
payload = dict(title="CI address round-trip", city="Paris", accommodationType="entire_place",
    bedrooms=1, sleeps=2, address=address)
api("/properties", "POST", payload, cookie="", expected=401)
for field, value in [("city", None), ("countryCode", "INVALID"), ("latitude", 91), ("placeId", "")]:
    invalid = copy.deepcopy(payload)
    invalid["address"][field] = value
    api("/properties", "POST", invalid, expected=400)
created = api("/properties", "POST", payload, expected=201)
property_id = created["id"]
address["countryCode"] = "FR"
assert created["address"] == address, created
assert (created["city"], created["countryCode"], created["country"]) == ("Paris", "FR", "France")

def dashboard_property():
    return next(p for p in api("/dashboard")["properties"] if p["id"] == property_id)

assert dashboard_property()["address"] == address
assert any(c["code"] == "FR" for c in api("/locations/countries", cookie=""))
assert {"countryCode":"FR", "name":"Paris"} in api("/locations/cities?country=FR", cookie="")

api(f"/properties/{property_id}/availability", "POST", {"from":"2032-05-01", "to":"2032-05-10", "nightlyPriceCents":9000}, expected=201)
def search(country, city):
    query = urllib.parse.urlencode(dict(country=country, city=city, **{"from":"2032-05-02", "to":"2032-05-04"}))
    return api("/search?" + query, cookie="")

result = next(p for p in search("FR", "Paris") if p["propertyId"] == property_id)
for private in ["address", "latitude", "longitude", "placeId"]:
    assert private not in result
profile = api("/auth/me")["profile"]["parrotId"]
public = next(p for p in api("/p/" + profile, cookie="")["properties"] if p["id"] == property_id)
assert "address" not in public and "latitude" not in public

settings = dict(accommodationType="entire_place", bedrooms=2, sleeps=3, minStayDays=1, cleaningFeeCents=7000)
api(f"/properties/{property_id}", "PUT", settings, cookie=other, expected=404)
assert api(f"/properties/{property_id}", "PUT", settings)["address"] == address
assert dashboard_property()["address"] == address
replacement = dict(address="Calle de Alcalá 42, Madrid", countryCode="ES", country="Spain",
    city="Madrid", latitude=40.418, longitude=-3.697, placeId="ci-madrid")
updated = api(f"/properties/{property_id}", "PUT", dict(settings, address=replacement))
assert updated["address"] == replacement and updated["city"] == "Madrid"
assert dashboard_property()["address"] == replacement
assert any(p["propertyId"] == property_id for p in search("ES", "Madrid"))
assert all(p["propertyId"] != property_id for p in search("FR", "Paris"))
api(f"/properties/{property_id}", "PUT", dict(settings, address=dict(replacement, city="")), expected=400)
assert dashboard_property()["address"] == replacement
api(f"/properties/{property_id}", "DELETE", expected=204)
print("Property address create/edit, preservation, validation, owner authorization, DB search and public privacy passed")
