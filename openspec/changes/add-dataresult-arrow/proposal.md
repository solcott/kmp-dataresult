# Proposal

## Why

Repositories written with Arrow return `Either<E, T>` from their `suspend` functions, or build
results with the `Raise` DSL. Meeting this library's `Outcome` means hand-written conversions
at every boundary, each deciding again what `Origin` to use and what a `Loading` means as an
`Either`. A small interop module makes the two vocabularies meet in one place.

## What Changes

- Add a new published module, `dataresult-arrow`, which provides:
  - `Either<DataError, T>` to `Outcome<T>`, with a caller-chosen `Origin` (default `Network`);
  - `Either<E, T>` to `Outcome<T>`, with a mapping from the caller's error type to `DataError`;
  - `Outcome<T>` to `Either<DataError, T>`, where `Loading` has no `Either` and becomes `null`;
  - an `outcome { }` builder over `Raise<DataError>`, mirroring Arrow's `either { }`.
- Exceptions thrown inside the builder propagate, as with `either { }`. They are not caught and
  turned into `Unknown`.
- No changes to existing modules. This is not breaking.

## Capabilities

### New Capabilities
- `dataresult-arrow/arrow-interop`: conversion between Arrow's `Either` and `Raise` and the library's `Outcome` and
  `DataError`.

### Modified Capabilities

None.

## Impact

- **New module:** `dataresult-arrow`, which depends on `dataresult` and `arrow-core`. It covers all
  seven targets, if `arrow-core` publishes them; task 1.1 verifies that.
- **Build wiring:** `settings.gradle.kts`, `kmp-published.gradle.kts`, a catalog floor for
  `arrow-core`, and Renovate's `consumer-floor` list.
- **API:** a new ABI dump under `dataresult-arrow/api/`. This module isn't exported to Swift
  wholesale, so `Either` in its signatures is fine.
- **Docs:** a README "Artifacts" row, a short README "Arrow" section, and a CHANGELOG entry.
