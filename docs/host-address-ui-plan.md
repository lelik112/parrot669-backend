# Host address autocomplete

Implemented 2026-09-23 in host create/edit forms, replacing the Barcelona-only selector.

- A labeled address field uses our authenticated autocomplete endpoint after three
  characters and a 300ms debounce. Superseded requests are canceled and ignored.
- Up to five complete suggestions support keyboard arrows/Enter/Escape and touch.
  Country and city are filled from the selected result; incomplete broad places are
  excluded with guidance to refine the query.
- Loading, empty results, retry and provider failures are shown beside the address.
  Typed text is preserved on failure. An expired session opens the existing login flow.
- Editing selected text clears the old selection and coordinates until another result
  is selected. Provider text is rendered as plain text. Geoapify attribution is visible.
- Country/city/address/coordinates/place ID are saved together on the existing Property
  using the nested `address` DTO, after backend validation. No new address/city table.
- Existing records can keep their location without an address; other settings can still
  be edited. Omitting address during a settings update preserves its stored value.
- New UI property creation requires selecting an address. The backend still accepts
  older Barcelona-only create requests for cached/older clients during rollout.
- Only the authenticated owner receives exact addresses and coordinates. Guest search
  and location lists stay database-only; no map or guest provider calls were added.

Validation: component/controller tests cover stale requests, keyboard selection,
failed lookup retry, create/edit payloads and preservation on validation failure.
Backend unit tests cover normalization/validation; disposable-database HTTP smoke tests
cover round-trip, ownership, settings preservation, guest discovery and public privacy.

Provider contract: https://apidocs.geoapify.com/docs/geocoding/address-autocomplete/
