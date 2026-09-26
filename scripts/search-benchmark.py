#!/usr/bin/env python3
"""PM-037: synthetic, reproducible benchmark. ONLY a fresh local test database.

The exact production SQL is extracted from SearchRepository (no copied query).
100/500 city properties are explicit pilot/scale assumptions, not production counts.
Run after Main has migrated an empty database; finish before its next hourly sync.
"""
import concurrent.futures
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

assert os.environ.get("APP_ENV") == "test"
assert os.environ.get("PGHOST") in ("localhost", "127.0.0.1")
assert os.environ.get("PGDATABASE") == "parrot_search_benchmark"
OUT = Path("benchmark-results")
OUT.mkdir(exist_ok=True)


def sql(query):
    return subprocess.check_output(["psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-c", query], text=True).strip()


assert sql("select count(*) from accounts") == "0", "requires a fresh database"
sql("""
insert into accounts(id,email_normalized,password_hash,username,email_verified,created_at)
select md5('account-'||i)::uuid,'bench-'||i||'@example.invalid','not-a-password',
       'bench-'||i,true,now() from generate_series(1,1000) i;
insert into profiles(id,account_id,parrot_id,display_name,contact,created_at)
select md5('profile-'||i)::uuid,md5('account-'||i)::uuid,'BENCH-'||i,'Host '||i,'',now()
from generate_series(1,1000) i;
insert into properties(id,profile_id,title,city,country_code,country,bedrooms,sleeps,
                       min_stay_days,accommodation_type,cleaning_fee_cents,created_at)
select md5('property-'||i)::uuid,md5('profile-'||((i-1)%1000+1))::uuid,'Home '||i,
       case when i<=100 then 'Pilot' when i<=600 then 'Scale' else 'Other' end,
       'ES','Spain',2,4,1,case when i%2=0 then 'entire_place' else 'private_room' end,5000,now()
from generate_series(1,5000) i;
insert into availability_periods(id,property_id,date_from,date_to,nightly_price_cents,created_at)
select md5('offer-'||i||'-'||j)::uuid,md5('property-'||i)::uuid,
       date '2030-01-01'+31*j,date '2030-01-01'+31*(j+1),
       case when i%10=3 and j=11 then null when j=11 then 11000 else 10000 end,now()
from generate_series(1,5000) i cross join generate_series(0,11) j
where not(i%10=2 and j=1);
insert into unavailability_periods(id,property_id,date_from,date_to,created_at)
select md5('manual-'||i)::uuid,md5('property-'||i)::uuid,'2030-01-02','2030-01-03',now()
from generate_series(1,5000) i where i%10=0;
insert into external_listings(id,property_id,platform,external_id,url,created_at)
select md5('listing-'||i)::uuid,md5('property-'||i)::uuid,'airbnb',i::text,
       'https://www.airbnb.com/rooms/'||i,now() from generate_series(1,5000) i;
insert into external_calendars(id,property_id,provider,ical_url,status,
                              last_synced_at,last_success_at,created_at,updated_at)
select md5('calendar-'||i)::uuid,md5('property-'||i)::uuid,'airbnb',
       'https://www.airbnb.com/calendar/ical/'||i||'.ics?t=synthetic-not-a-secret',
       'connected',now(),now(),now(),now() from generate_series(1,5000) i;
insert into external_calendar_events(id,calendar_id,external_uid,kind,date_from,date_to,observed_at)
select md5('event-'||i||'-'||j)::uuid,md5('calendar-'||i)::uuid,'event-'||j,
       case when i%10=1 then 'reservation' when j%2=0 then 'platform_unavailable' else 'unknown' end,
       date '2030-01-03'+30*j,date '2030-01-05'+30*j,now()
from generate_series(1,5000) i cross join generate_series(0,11) j;
analyze;
""")

SOURCE = Path("src/main/scala/com/parrot669/search/SearchRepository.scala").read_text()
match = re.search(r'sql"""(\s*with candidates as .*?)"""\.query\[AvailablePropertyRecord\]', SOURCE, re.S)
assert match, "Search SQL changed; update extraction explicitly"
QUERY = match.group(1)
START = dt.date(2030, 1, 1)


def concrete_sql(city, nights):
    literals = dict(countryCode="'ES'", city=f"'{city}'", bedrooms="1", sleeps="1",
                    stayDays=str(nights), accommodationType="NULL::varchar", pricedOnly="false",
                    requestedFrom=f"DATE '{START}'", requestedTo=f"DATE '{START+dt.timedelta(days=nights)}'")
    return re.sub(r'\$(\w+)', lambda m: literals[m[1]], QUERY)


def request(city, nights, priced=False):
    params = urllib.parse.urlencode(dict(country="ES", city=city, bedrooms=1, sleeps=1,
                                        **{"from": START.isoformat(), "to": str(START+dt.timedelta(days=nights))},
                                        pricedOnly=str(priced).lower()))
    begin = time.perf_counter()
    try:
        with urllib.request.urlopen("http://localhost:8080/api/search?"+params, timeout=60) as response:
            status, body = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, body = error.code, json.load(error)
    elapsed = (time.perf_counter()-begin)*1000
    if nights > 366:
        assert status == 400 and body == {"error": "A search request can cover at most 366 nights"}, (status, body)
        return elapsed, 0
    assert status == 200, (status, body)
    size, first = (100, 1) if city == "Pilot" else (500, 101)
    expected = {str(uuid.UUID(hashlib.md5(f"property-{i}".encode()).hexdigest())) for i in range(first, first+size)
                if (nights == 1 or i%10 not in (0, 1)) and not(nights>31 and i%10==2)
                and not(priced and nights>341 and i%10==3)}
    assert {item['propertyId'] for item in body} == expected, (city, nights, len(body), len(expected))
    for item in body:
        assert item['availableFrom'] == str(START) and item['availableTo'] == str(START+dt.timedelta(days=nights))
        if item['price'] is not None:
            price = item['price']
            assert price['nights'] == nights
            assert price['nightlySubtotalCents'] == nights*10000+max(0,nights-341)*1000
            assert price['cleaningFeeCents'] == 5000
            assert price['estimatedAmountCents'] == nights*10000+max(0,nights-341)*1000+5000
    if nights == 366 and not priced:
        assert sum(item['price'] is None for item in body) == size//10
    return elapsed, len(body)


def summary(samples):
    ordered = sorted(samples)
    return dict(p50_ms=round(statistics.median(samples), 2),
                p95_ms=round(ordered[math.ceil(.95*len(ordered))-1], 2),
                samples_ms=[round(x, 2) for x in samples])


report = dict(commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              measured_at=dt.datetime.now(dt.timezone.utc).isoformat(),
              environment=dict(postgres=sql('select version()'), platform=platform.platform(),
                               cpu_count=os.cpu_count(), memory=Path('/proc/meminfo').read_text().splitlines()[0],
                               java=subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True),
                               db_settings=sql("select name||'='||setting from pg_settings where name in ('shared_buffers','work_mem','max_connections','jit')")),
              dataset=dict(properties=5000, hosts=1000, availability_periods=int(sql('select count(*) from availability_periods')),
                           calendars=5000, events=60000, manual_blocks=500,
                           description='12 adjacent 31-night offers; 10% gap, 10% last-period unpriced, 10% reserved, 10% manual block; all listings attached; 100/500 city properties'),
              sql_sha256=hashlib.sha256(QUERY.encode()).hexdigest(), measurements=[])
for city in ('Pilot', 'Scale'):
    request(city, 1)
    request(city, 366, priced=True)
    samples = {n: [] for n in (7, 30, 90, 366, 367)}
    for n in samples:
        for _ in range(3):
            request(city, n)
    order = list(samples)*25
    random.Random(37).shuffle(order)
    counts = {}
    for n in order:
        latency, counts[n] = request(city, n)
        samples[n].append(latency)
    for n, timings in samples.items():
        row = dict(city=city, nights=n, concurrency=1, results=counts[n], **summary(timings))
        if n <= 366:
            query = concrete_sql(city, n)
            candidates = query.split('),\n      nights as (')[0]+') select count(*) from candidates'
            row['candidates'] = int(sql(candidates))
            plan = sql('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+query)
            OUT.joinpath(f'{city}-{n}-explain.json').write_text(plan+'\n')
            OUT.joinpath(f'{city}-{n}.sql').write_text(query+';\n')
            row['sql_execution_ms'] = json.loads(plan)[0]['Execution Time']
        report['measurements'].append(row)
        print(json.dumps({k:v for k,v in row.items() if k!='samples_ms'}), flush=True)
    begin = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        parallel = list(pool.map(lambda _: request(city, 366)[0], range(24)))
    row = dict(city=city, nights=366, concurrency=4, throughput_rps=round(24/(time.perf_counter()-begin), 2), **summary(parallel))
    report['measurements'].append(row)
    print(json.dumps({k:v for k,v in row.items() if k!='samples_ms'}), flush=True)
    OUT.joinpath('report.json').write_text(json.dumps(report, indent=2)+'\n')
print('PM-037 benchmark and real-DB boundary/price checks passed', flush=True)
