# Proposal

## Why

Ktor is the HTTP client most KMP data layers use. Consumers here use it for XRPC and plain REST.
Yet the library has no adapter for it, even though `DataError`'s own KDoc names Ktor as a source,
and its `Http`, `Network` and `Serialization` cases are HTTP's failure modes. Each consumer is
left writing its own exception mapping. That mapping is easy to get subtly wrong: Ktor doesn't throw
on a non-2xx response by default, a `CancellationException` must never become an error, and error
envelopes need decoding.

## What Changes

- Add a new published module, `dataresult-ktor`, that turns Ktor calls into `Outcome`s:
  - a one-shot call to `Outcome<T>`, catching and classifying failures;
  - a flow form that emits `Loading` and then the call's outcome, for use with
    `produceContentState` and the stream folds;
  - an `HttpResponse` form that reads the status itself, so it is correct whatever the client's
    `expectSuccess` setting;
  - a classification of Ktor's exceptions into `DataError`.
- Add a caller-supplied hook that decodes an error response body, such as an XRPC
  `{"error", "message"}` envelope or a REST problem document, into `DataError.Api`. It falls back to
  `DataError.Http(status)` when the hook returns nothing or itself fails.
- Every outcome carries `Origin.Network`. Ktor doesn't report whether a cached response answered.
- No changes to existing modules. This is not breaking.

## Capabilities

### New Capabilities
- `ktor-adapter`: mapping Ktor HTTP calls and responses onto `Outcome` and `DataError`. That covers
  status handling, exception classification, error-body decoding, cancellation, and the flow form.

### Modified Capabilities

None.

## Impact

- **New module:** `dataresult-ktor`, which depends on `dataresult` and `ktor-client-core`. It covers
  all seven targets, including `macosArm64`.
- **Build wiring:** `settings.gradle.kts`, `kmp-published.gradle.kts` (POM description), version
  catalog floors for Ktor and its serialization artifacts, and Renovate's `consumer-floor` list.
- **API:** a new ABI dump under `dataresult-ktor/api/`.
- **Docs:** a row in the README "Artifacts" table, a README "Ktor" usage section, and a CHANGELOG
  entry under `## Unreleased`.
