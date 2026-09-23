# Host address autocomplete

Updated 2026-09-23: country → city → street → house number.

- Countries use a built-in ISO list from our backend, with localized names in the
  browser. They never require a provider call and load once per host page.
- Cities use LocationIQ autocomplete restricted to the chosen country. The selected
  city carries its provider ID and bounding box into the street query.
- Every street lookup sends `<selected city>, <typed fragment>` with `layers=road`,
  country and geographic restrictions. The selected city is checked on backend and
  frontend; an envelope can also contain neighboring municipalities. Duplicate road
  segments are collapsed. No language-specific fallback or POI-to-street conversion.
- The backend paces provider requests and reports quota errors without losing input.
  The form displays the visible Search by LocationIQ.com attribution link.
- Street suggestions do not require a house number. The owner enters it separately;
  this does not spend provider credits or claim that the building was verified.
- Lookups require 3 characters and a 700ms pause; focusing fields does not send a
  request. Enter can explicitly search. Up to 10 scoped suggestions are shown.
- Browser results are cached for 15 minutes (100 entries); server results for 15
  minutes (512 entries). Identical concurrent server lookups share one provider call.
  Scope and type are part of the key. Errors are not cached; stale responses are ignored.
- Changing a country resets city/street/number; changing a city resets street/number.
  Keyboard navigation, mobile selection, retry and preserved drafts work at each step.
- Existing saved locations are shown with a Change address action. Until the owner
  changes the address, settings updates omit it and preserve historical data.
- POST/PUT still require a full address when setting a new location, including the
  house number. Street-level coordinates retain resultType=street, not building.
- Exact addresses remain private to owners; guest search and guest location lists
  continue to use only PostgreSQL. No new database migration or city table is needed.

Validation: scoped request and quota-saving tests, mobile blur/click sequencing,
selection invalidation, cache isolation/expiry/error retry, legacy preservation,
full property round-trips and guest privacy in disposable-database HTTP checks.

Provider contract: https://docs.locationiq.com/reference/autocomplete-2
