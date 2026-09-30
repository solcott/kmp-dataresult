---
paths:
  - "dataresult-apollo/src/**"
  - "dataresult-store5/src/**"
---

# Source adapters

A cache miss is not `Data`. An empty cached read while a fetch is pending is a miss, and an adapter
holds it back rather than emitting it as a result.

- **Apollo:** `mapToOutcome` drops `CacheMissException`.
- **Store5:** `asOutcomes(fetching, isEmpty)` holds an empty *first* read until the fetch settles.
  `NoNewData` or completion releases it, and a fetcher error discards it.
- `ContentState.hasAnswer(isEmpty)` in `uistate` is the consumer-side backstop, not a replacement
  for any of this.

Adapters depend only on `dataresult`. The Apollo normalized-cache dependency stays `implementation`,
because nothing from it reaches the public API.
