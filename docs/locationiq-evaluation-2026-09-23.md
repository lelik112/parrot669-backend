# LocationIQ owner autocomplete evaluation

Date: 2026-09-23. This is provider evaluation, not a product migration.
Production/main remains `534e1b810c8d6bffc7538aceece3cc66cd1b27ed`.
The CLI and these notes live on `diagnostics/locationiq-streets` only.

## Result

LocationIQ passed the concrete street-search examples in Barcelona, Madrid and
Paris. Use the selected city in the query, alongside country and geographic
constraints. Do not repeat the Geoapify Catalan-prefix fallback.

The same request shape was tested for all 13 street queries:

```
GET https://api.locationiq.com/v1/autocomplete
q=<selected city>, <typed street fragment>
layers=road
countrycodes=<selected ISO country>
viewbox=<selected city's bounding box>
bounded=1
limit=20
dedupe=1
normalizecity=1
accept-language=native
```

Authentication is read from `LOCATIONIQ_API_KEY` inside Railway. It is never
printed, returned to the browser or committed. The separate city request uses
`layers=city` and the selected country. Its returned `boundingbox` provides the
street search scope; convert `[minLat,maxLat,minLon,maxLon]` to viewbox order
`minLon,minLat,maxLon,maxLat`.

| City | Typed fragment | Observed result |
| --- | --- | --- |
| Barcelona | `alf` | 8 unique street/square names, including Carrer d'Alfons el Magnànim |
| Barcelona | `alfo` | 6 unique names, including Carrer d'Alfons el Magnànim |
| Barcelona | `alfons` | 4 unique names, including Carrer d'Alfons el Magnànim |
| Barcelona | `alfonso el magnanim` | Carrer d'Alfons el Magnànim |
| Barcelona | `alfons el magnanim` | Carrer d'Alfons el Magnànim |
| Barcelona | `mallor` | Carrer de Mallorca, multiple road segments |
| Barcelona | `rambl` | La Rambla and Rambla de Catalunya |
| Madrid | `alc` | 8 names including Calle de Alcalá |
| Madrid | `alcala` | Calle de Alcalá, multiple road segments |
| Madrid | `gran` | 5 unique names including Gran Vía |
| Paris | `riv` | 4 unique names including Rue de Rivoli |
| Paris | `rivoli` | Rue de Rivoli, multiple road segments |
| Paris | `volta` | 6 unique names including Boulevard Voltaire and Rue Volta |

All returned street records in the city-qualified suite had class `highway`,
the selected city and the selected country. No POIs were promoted to streets.
These are observed examples, not a guarantee of exhaustive worldwide recall.

## Controls and limitations

- A plain `alf` query with only the geographic box missed Magnànim and returned
  neighboring municipalities. A box is not the city's administrative polygon.
  Keep result-level country/city checks even when the provider returns no
  neighbors in the city-qualified suite.
- Native and Spanish full-name queries both found the street when the user wrote
  `alfonso el magnanim`. No hardcoded translation or Catalan prefix was added.
- `dedupe=1` still returns multiple segments of one road. Deduplicate normalized
  street names within the selected city before applying the UI limit.
- Road records use `address.name` / `display_place` for the road name; their
  `address.road` may be absent. Only use this mapping after confirming a road
  record, never blindly map a POI name or its containing road.
- Provider `place_id` is not Geoapify's hexadecimal ID. The current backend
  validation and city-scope contract must change during integration.
- City results include useful bounds. Preserve the selected city's bounds in
  the owner autocomplete DTO/requests; validate finite, ordered coordinates.
- Street coordinates identify a road segment, not a verified building. A manual
  house number must not be represented as provider-verified.
- This suite does not prove support for every country's administrative hierarchy
  or multilingual city names. Native city names must be preserved consistently.

## Verification and production status

36 API requests total, sequentially spaced by at least 1.1 seconds, no retries:
20 comparison requests, then 16 city-qualified verification requests (3 cities
plus 13 streets). Every request returned HTTP 200. No production users or
properties were created or changed by the probe. No DB access exists in the CLI.

- Comparison CLI commit: `f7cb882f0502d343d699b726ecf47d07752cdc1d`.
- Verification CLI commit: `80252f53b47ccceb466dcd31a5d5dfd18eb0177e`.
- Verification Railway deployment: `5b17594c-d3c1-4e5d-967c-d23e1ba371b6`, SUCCESS.
- Sanitized real provider examples: `src/test/resources/geocoding/locationiq-live-evaluation.json`.
- Runtime pre-deploy command was cleared after the evaluation. The ordinary
  production application is restored separately to the existing main commit.

## Integration requirements

Keep country → city → street → manual house number. One provider call per
uncached debounced street lookup; preserve coalescing and error handling.
Replace Geoapify client/config and remove the Catalan-prefix fallback only as
part of the tested migration. Keep guests querying PostgreSQL exclusively.
Respect LocationIQ rate limits and add the required visible attribution for the
free tier. Keep caching within the provider's permitted duration. No map or new
address/city tables are needed for this integration.

Official API reference: https://docs.locationiq.com/reference/autocomplete-2
Attribution/pricing: https://locationiq.com/pricing
Storage terms: https://locationiq.com/tos
