# Host address autocomplete: next phase

Status: plan only. Backend autocomplete is implemented; this change does not add a host UI,
property address columns or a property write contract.

## Current boundary

- `GET /api/geocode/autocomplete?q=...` requires the existing `parrot_session` cookie.
- A trimmed query must have 3–256 characters. Responses are not cached.
- Success is an array with `address`, `countryCode`, `country`, `city`, `latitude`,
  `longitude`, `placeId`. Country codes are uppercase. Results use English names
  consistently, independently of the host page language.
- Geoapify can return broad places without a city or country. These fields are nullable;
  the UI must not mistake a continent/state suggestion for a complete property address.
- `400`: invalid query; `401`: sign in; `503`: unavailable or not configured.
  All errors use the existing `{ "error": "..." }` envelope. Empty results are `200 []`.
- Guest country/city lists and availability search continue to read PostgreSQL only.

## UI work

1. Add a labeled **Property address** field to host create/edit forms, with a visible
   example and an explanation that selecting a suggestion fills country and city.
   Keep the existing property name separate from the address.
2. At three characters, debounce requests by about 300 ms. Call our endpoint through
   the existing same-origin Worker proxy with the session cookie. Cancel superseded
   requests and ignore stale responses; never call Geoapify directly from the browser.
3. Render up to five suggestions under the input. Support keyboard arrows, Enter,
   Escape and appropriate combobox/listbox semantics. Use at least 44px touch targets
   and a full-width dropdown on mobile. Render provider strings as text, not HTML.
4. Show loading, no matches and request errors beside the field. A 401 opens the
   existing sign-in flow. A 503 retains the typed address and allows retry.
5. On selection, retain the complete normalized DTO in form state, show country/city,
   and validate that the selected suggestion has the components required by Property.
   If components are absent, ask the owner to refine the address rather than guessing.
   Editing the address text clears the prior selection and coordinates/place ID so
   stale location data cannot be saved with a different address.
6. Show Geoapify attribution beside the suggestions as appropriate for the configured
   provider plan; no map is needed.

## Saving requires a separate backend change

Before enabling Save for address data:

- Add nullable address/latitude/longitude/place ID columns to the existing `properties`
  table with a new additive migration. Keep existing records and availability intact;
  do not create Address/City/GeoLocation tables.
- Extend create/update/dashboard DTOs and repository queries to round-trip the selected
  address plus the existing `country_code`, `country`, `city`. Today country is in the
  database, while `PropertyRecord`/host writes still need that round-trip implemented.
- Validate country codes, coordinate ranges, required components and string lengths
  server-side; retain owner authorization. A browser-submitted DTO is not trusted just
  because it originally came from autocomplete.
- Do not geocode on every save or perform any provider lookup during guest search.
- Keep the full address and precise coordinates out of guest/public responses until
  the intended public address visibility is explicitly designed.
- Test create/edit round-trip, clearing/changing selection, unauthorized writes,
  provider failure, keyboard operation and mobile layout. Verify that saved properties
  appear in the existing database-derived country/city lists and guest results.

Provider contract: https://apidocs.geoapify.com/docs/geocoding/address-autocomplete/
