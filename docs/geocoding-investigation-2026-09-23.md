# Owner street autocomplete investigation — 2026-09-23

Status: research, not a production fix. Main remains `7853cfb`.
No users, properties, migrations or guest search were changed. The temporary
Railway pre-deploy check was removed and the main binary restored.

## Reproduced regression

Splitting country/city/street introduced Geoapify `type=street`. A full input
`alfons el magnanim` previously returned the intended Barcelona street through
general autocomplete, but returns no results with `type=street`. The current
`carrer d'` fallback is language-specific and is not a general solution.

Live comparisons used the same country/place scope and English response language,
with IP bias disabled. General autocomplete used a result limit of 20. Results
below distinguish raw provider output from the desired street.

| General autocomplete input | Observed result |
| --- | --- |
| `alf, Barcelona` | Empty |
| `Barcelona, alf` | Alfons XII / Alfonso Comín, but not Magnànim |
| `alfons el magnanim, Barcelona` | Intended street found |
| `Barcelona, alfons` | Intended street found |
| `Barcelona, alfons el mag` | Intended street found |
| `Barcelona, alfonso el magnanim` | Empty; spelling aliases are not reliably resolved |
| `mallor, Barcelona` | Empty |
| `Barcelona, mallor` | Carrer de Mallorca found |
| `alc, Madrid` | Calle de Alcalá missing |
| `Madrid, alc` | Calle de Alcalá found |
| `riv, Paris` | Rue de Rivoli missing |
| `Paris, riv` | Rue de Rivoli found |

City-first general autocomplete improves coverage across cities without road-name
prefixes, but does not meet the original `alf` case. It also returns businesses
whose matching name differs from their street. Blindly projecting every result's
street into a street suggestion is therefore incorrect. Neighboring municipalities
occur despite the place filter. Increasing the limit is not a complete fix.

A bounded public Photon comparison also found the complete Magnànim name but
returned unrelated suggestions for `alf` and `riv`; switching providers has not
been demonstrated to solve the issue. Its public instance is not a production SLA.

## Verified alternative: a city street catalog

One read-only OpenStreetMap/Overpass query selected named highway ways in the
administrative area with Wikidata Q1492 (Barcelona):

```overpass
[out:json][timeout:25];
area["wikidata"="Q1492"]["boundary"="administrative"]->.city;
way(area.city)["highway"]["name"];
out tags;
```

Observed: 60,112 way segments, 4,538 distinct primary names. Case-folding,
Unicode accent-folding and matching word prefixes locally produced these 11
distinct primary names for `alf`, with no language-specific prefix substitution:

- Carrer d'Alfambra
- Carrer d'Alfarràs
- Carrer d'Alfons XII
- Carrer d'Alfons el Magnànim
- Carrer del Mestre Alfonso
- Passatge d'Alfonso Lafuente
- Plaça d'Alfons X
- Plaça d'Alfons el Savi
- Plaça d'Alfonso Comín
- Plaça dels Jardins d'Alfàbia
- Plaça dels Jardins de l'Alfàbia

`mallor` found Carrer de Mallorca, Passatge de la Ciutat de Mallorca, and Passeig
de la Ciutat de Mallorca. This demonstrates retrieval from a finite catalog,
not completeness of OSM or verification of a building/address. Overpass area
selection includes ways intersecting the area; border streets need explicit
geometry handling. Primary names alone can contain near duplicates and omit
Spanish or other alternative names.

## Recommended implementation scope

1. Retain separate country, city, street and house fields. Keep guest search in
   PostgreSQL and external keys on the backend.
2. Import a versioned city street catalog into PostgreSQL in a background job.
   Resolve the selected city to an unambiguous administrative area; do not infer
   that mapping from its name alone or decode opaque provider IDs as an API.
3. Preserve source IDs, primary and alternative language names, source revision,
   city/geometry membership and a representative coordinate. Group road segments
   without merging distinct streets merely because they share a name.
4. Serve local prefix search first, with accent/case folding and deterministic
   ranking. A trigram index can support spelling similarity as a second tier.
   Match stored aliases; never manufacture translations or street prefixes.
5. Import/update only supported cities through scheduled extract processing or
   a suitable hosted data source. Public Overpass is useful for this experiment,
   not a synchronous dependency for production keystrokes or city selection.
   Keep the previous successful catalog during refreshes and expose missing
   catalog/loading states honestly.
6. Store provider-neutral street identity and validate the selected catalog entry
   and city on save. A street coordinate does not establish that the supplied
   house number exists. Do not label this as verified building geocoding.
7. Test Barcelona `alf`, Madrid `alc`, Paris `riv`, accents, stored aliases,
   homonymous cities, border streets, catalog refresh/failure and zero provider
   requests during typing. The experiment currently proves only the Barcelona
   catalog case, not worldwide production readiness.

This requires a small imported dataset and update process, rather than another
query rewrite. It should be implemented as a separate change with explicit
supported-city coverage and a migration, not silently added to the current fix.

## Sources

- [Geoapify autocomplete API](https://apidocs.geoapify.com/docs/geocoding/address-autocomplete/)
- [Photon API](https://github.com/komoot/photon/blob/master/docs/api-v1.md)
- [Photon deployment/public-instance notes](https://github.com/komoot/photon/blob/master/README.md)
- [Overpass area selection](https://dev.overpass-api.de/overpass-doc/en/full_data/area.html)
- [Overpass public-instance operating guidance](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)
- [OpenStreetMap attribution and ODbL](https://www.openstreetmap.org/copyright)

The small list above derives from © OpenStreetMap contributors, available under
the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/).
