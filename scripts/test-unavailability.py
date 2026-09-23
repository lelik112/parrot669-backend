"""HTTP regression tests against the disposable smoke-test server, never production."""
import concurrent.futures
import http.cookiejar
import json
import sys
import urllib.error
import urllib.request

base, owner_cookie, other_cookie = sys.argv[1:]
assert base.startswith("http://localhost:"), "Disposable tests: localhost only"

def client(cookie=None):
    jar = http.cookiejar.MozillaCookieJar(cookie)
    if cookie:
        jar.load(ignore_discard=True, ignore_expires=True)
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

owner, other, guest = client(owner_cookie), client(other_cookie), client()

def request(method, path, body=None, expected=200, who=owner):
    req = urllib.request.Request(base + "/api" + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method, headers={"Content-Type": "application/json"})
    try:
        response = who.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        result = json.loads(raw) if raw else None
        assert response.code == expected, (method, path, response.code, expected, result)
        return result

prop = request("POST", "/properties", {"title": "Manual blocks smoke test", "city": "Barcelona",
    "bedrooms": 1, "sleeps": 2, "accommodationType": "entire_place"}, 201)
pid = prop["id"]
collection = f"/properties/{pid}/unavailability"

def dates(start, end):
    return {"from": "2030-04-" + start, "to": "2030-04-" + end}

def search(start, end, expected=True):
    found = request("GET", f"/search?country=ES&city=Barcelona&from=2030-04-{start}&to=2030-04-{end}&bedrooms=1&sleeps=1")
    matches = [p for p in found if p["propertyId"] == pid]
    assert bool(matches) == expected, (start, end, expected, found)
    return matches[0] if matches else None

try:
    available = request("POST", f"/properties/{pid}/availability",
                        {**dates("01", "30"), "nightlyPriceCents": 10000}, 201)
    baseline = search("10", "12")
    assert request("GET", collection) == []
    block = request("POST", collection, dates("10", "15"), 201)
    bid = block["id"]
    path = f"/unavailability/{bid}"
    assert set(block) == {"id", "propertyId", "from", "to", "createdAt"}, block
    for start, end in [("10", "12"), ("09", "11"), ("14", "16"), ("01", "30")]:
        search(start, end, False)
    search("08", "10")  # checkout at block start
    search("15", "17")  # arrival at block end
    card = next(p for p in request("GET", "/dashboard")["properties"] if p["id"] == pid)
    assert card["unavailability"] == [block]
    assert card["availability"] == [available]  # no splitting or price changes
    assert card["calendars"] == [] and card["listings"] == []
    for who, denied in [(guest, 401), (other, 404)]:
        request("GET", collection, expected=denied, who=who)
        request("POST", collection, dates("20", "22"), denied, who)
        request("PUT", path, dates("20", "22"), denied, who)
        request("DELETE", path, expected=denied, who=who)
    for bad in [dates("15", "10"), dates("10", "10"), {"from": "invalid", "to": "2030-04-15"}]:
        request("POST", collection, bad, 400)
        request("PUT", path, bad, 400)
    request("POST", collection, dates("12", "16"), 409)
    adjacent = request("POST", collection, dates("15", "17"), 201)
    request("PUT", path, dates("14", "16"), 409)
    assert request("GET", collection)[0] == block
    request("DELETE", f'/unavailability/{adjacent["id"]}', expected=204)
    moved = request("PUT", path, dates("20", "22"))
    assert moved["id"] == bid and moved["from"] == "2030-04-20"
    assert search("10", "12") == baseline
    search("20", "21", False)
    request("DELETE", path, expected=204)
    request("DELETE", path, expected=404)
    search("20", "21")
    assert request("GET", collection) == []
    outside = request("POST", collection, {"from": "2030-05-01", "to": "2030-05-03"}, 201)
    request("DELETE", f'/unavailability/{outside["id"]}', expected=204)
    may = request("GET", "/search?country=ES&city=Barcelona&from=2030-05-01&to=2030-05-02")
    assert all(p["propertyId"] != pid for p in may)
    def competing_create(_):
        who = client(owner_cookie)
        req = urllib.request.Request(base + "/api" + collection,
            data=json.dumps(dates("24", "26")).encode(), method="POST",
            headers={"Content-Type": "application/json"})
        try:
            with who.open(req, timeout=15) as response:
                return response.code
        except urllib.error.HTTPError as error:
            error.close()
            return error.code
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(competing_create, range(2))) == [201, 409]
    assert len(request("GET", collection)) == 1
    assert request("GET", f"/properties/{pid}/availability") == [available]
    print("Manual unavailability CRUD, search boundaries, prices, ownership and concurrent overlap passed")
finally:
    request("DELETE", f"/properties/{pid}", expected=204)

request("GET", collection, expected=404)
print("Manual unavailability property deletion passed")
