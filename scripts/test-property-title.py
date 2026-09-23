"""QA rename regression against the disposable CI database, never production."""
import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

base, owner_file, other_file = sys.argv[1:]
def cookie(path):
    jar = http.cookiejar.MozillaCookieJar(path)
    jar.load(ignore_discard=True, ignore_expires=True)
    return '; '.join(c.name + '=' + c.value for c in jar if c.name == 'parrot_session')
owner, other = cookie(owner_file), cookie(other_file)
def api(path, method='GET', body=None, session=owner, status=200):
    req = urllib.request.Request(base + '/api' + path, method=method,
        headers={'Cookie':session, 'Content-Type':'application/json'},
        data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.status == status, (path, response.status, raw)
        return json.loads(raw) if raw else None
address = dict(address='10 Rue de Rivoli, Paris', countryCode='FR', country='France', city='Paris',
    latitude=48.855, longitude=2.36, placeId='ci-title-address', street='Rue de Rivoli', houseNumber='10', resultType='building')
created = api('/properties','POST',dict(title='CI original name',city='Paris',accommodationType='entire_place',bedrooms=2,sleeps=3,address=address),status=201)
prop = created['id']
period = api(f'/properties/{prop}/availability','POST',{'from':'2033-05-01','to':'2033-05-10','nightlyPriceCents':5000},status=201)
settings = dict(accommodationType='entire_place',bedrooms=2,sleeps=3,minStayDays=1,cleaningFeeCents=1500)
new_name = 'Новое имя 🏠 & <test>'
api(f'/properties/{prop}','PUT',dict(settings,title=new_name),session=other,status=404)
api(f'/properties/{prop}','PUT',dict(settings,title=new_name),session='',status=401)
for invalid in ['', '   ', 'x'*161]:
    api(f'/properties/{prop}','PUT',dict(settings,title=invalid),status=400)
renamed = api(f'/properties/{prop}','PUT',dict(settings,title='  '+new_name+'  '))
assert renamed['title'] == new_name and renamed['address'] == address
preserved = api(f'/properties/{prop}','PUT',settings)
assert preserved['title'] == new_name
saved = next(p for p in api('/dashboard')['properties'] if p['id'] == prop)
assert saved['title'] == new_name and saved['address'] == address
assert saved['availability'][0]['id'] == period['id']
query = urllib.parse.urlencode(dict(country='FR',city='Paris',**{'from':'2033-05-02','to':'2033-05-04'}))
found = next(p for p in api('/search?'+query,session='') if p['propertyId'] == prop)
assert found['propertyTitle'] == new_name and found['price']['estimatedAmountCents'] == 11500
api(f'/properties/{prop}','DELETE',status=204)
print('Property rename: ownership, validation, persistence, legacy payloads, search, address and availability preservation passed')
