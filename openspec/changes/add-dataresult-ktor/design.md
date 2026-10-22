# Design

## Context

The existing adapters set the pattern. `dataresult-apollo` exposes `mapToOutcome` on the client's
response flow, plus `ApolloException.toDataError()`, and takes an `onException` callback instead of
a logger. `dataresult-store5` exposes `asOutcomes`. Each is one file in `commonMain`, and the
`/new-module` skill lists every file a new module touches. Ktor differs from both: a call is
one-shot, not a stream, and a non-2xx response is an ordinary return unless `expectSuccess` is on.

## Goals / Non-Goals

**Goals:**
- Correct outcomes whatever the `expectSuccess` setting, and for Ktorfit-generated clients, whose
  calls surface the same Ktor exceptions.
- No knowledge of any wire format. Error envelopes come through a hook.

**Non-Goals:**
- Retries, auth refresh, and caching policy. Those belong to Ktor plugins.
- Detecting a response served by `HttpCache`. Ktor doesn't expose it, so `Origin` is always `Network`.
- Streaming bodies or SSE. A streaming source can map its own events with `Outcome.Data`.

## Decisions

### 1. API surface

```kotlin
// One-shot: any call; maps thrown ResponseException (expectSuccess = true) and everything else.
suspend fun <T> httpOutcome(
  onException: (Throwable) -> Unit = {},
  errorBody: suspend (HttpResponse) -> DataError? = { null },
  call: suspend () -> T,
): Outcome<T>

// Flow: Loading, then httpOutcome(call). Cold; a request per collection.
fun <T> httpOutcomeFlow(onException, errorBody, call: suspend () -> T): Flow<Outcome<T>>

// Response form: checks the status itself, so it is right with expectSuccess = false.
suspend fun <T> HttpResponse.toOutcome(
  onException: (Throwable) -> Unit = {},
  errorBody: suspend (HttpResponse) -> DataError? = { null },
  mapSuccess: suspend HttpResponse.() -> T,
): Outcome<T>

fun Throwable.toDataError(): DataError
```

- **The `http` prefix.** It names the transport at the call site. It also leaves `outcomeOf` free
  for `dataresult` core, and keeps clear of the Arrow change's `outcome { }` builder. Two top-level
  builders taking lambdas would be ambiguous in a file that imports both.
- **Two shapes, by `expectSuccess`.** With `expectSuccess = true`, client-wide or per request,
  `httpOutcome { client.get(url).body<T>() }` is enough, because a non-2xx response throws
  `ResponseException`, which carries the response. With the default `false`, that same lambda would
  try to decode an error page as `T` and report `Serialization`, which is wrong. `toOutcome`
  covers that case by reading the status first. The KDoc on `httpOutcome` states the requirement.
- **`onException` and `errorBody` come before the trailing lambda**, matching `mapToOutcome`'s
  parameter order.
- Rejected: an `HttpClient` receiver (`client.outcome { get(url) }`). Ktorfit users don't hold the
  client at the call site.

### 2. Classification order

1. A `CancellationException` that is not a timeout: rethrow (spec: "Cancellation is never an error").
2. `ResponseException`: run `errorBody(e.response)`, falling back to `Http(e.response.status.value)`.
3. Timeouts (`HttpRequestTimeoutException`, `ConnectTimeoutException`, `SocketTimeoutException`)
   and `kotlinx.io.IOException`: `Network`.
4. Decoding failures (`NoTransformationFoundException`, `ContentConvertException`, which covers
   `JsonConvertException`, and `kotlinx.serialization.SerializationException`): `Serialization`.
5. A platform hook for network failures that aren't `IOException`: `Network`. The known case is
   `java.nio.channels.UnresolvedAddressException` from CIO on JVM and Android, which is an
   `IllegalArgumentException`. It is an `expect`/`actual` in `jvmMain` and `androidMain`, and a no-op
   elsewhere.
6. Otherwise: `Unknown(cause = e, message = e.message)`.

Timeouts are checked before cancellation as a guard. In Ktor 2, `HttpRequestTimeoutException`
extended `CancellationException`, and rethrowing it would cancel the collector. The floor is Ktor 3,
where it's an `IOException`, but the order costs nothing and survives a regression.

### 3. The error-body hook is guarded

`errorBody` runs inside its own try/catch. Anything it throws, except cancellation, falls back to
`Http(status)`. That keeps a decoding attempt (`runCatching { body<XrpcError>() }`) from turning a
clear 400 into `Serialization`, and spares every caller from writing the guard. It is not passed
to `onException`, because the outcome is still the HTTP error the caller would expect.

### 4. Dependencies

- `api(ktor-client-core)`: `HttpResponse` appears in the signatures.
- `implementation(ktor-serialization)`: only for the `ContentConvertException` type check. The
  JSON converters a consumer uses already depend on it.
- `implementation(kotlinx-serialization-core)`: only for the `SerializationException` type check.
  This adds a small runtime artifact for a consumer who decodes some other way. That is accepted,
  because KMP consumers almost always use kotlinx.serialization already.
- Tests: `ktor-client-mock`. Serialization failures are produced without the serialization
  compiler plugin, through `body<Unregistered>()` (`NoTransformationFoundException`) and thrown
  `JsonConvertException`s, so the module build stays plain.
- **Floors:** the lowest Ktor 3.x that publishes every target, including `wasmJs` and `macosArm64`,
  and that is compatible with the repo's Kotlin. Find it with the `kotlin-multiplatform-libraries-expert`
  skill or klibs.io, and record the reason in a catalog comment, as for the other floors.

## Risks / Trade-offs

- **[Engines surface failures differently]** Darwin wraps `NSError`, and JS fetch rejects with
  generic errors. Those might not be `IOException`s. → They fall to `Unknown`, with the cause kept
  and reported through `onException`, so nothing is lost. Task 3.3 records what each engine throws
  for an offline request. A new mapping is added only from observed evidence.
- **[`httpOutcome` with `expectSuccess = false` misreports error pages]** → The KDoc, the README and
  a test that pins the behavior. The README's REST and XRPC examples use `toOutcome`, or set
  `expectSuccess` per request.
- **[The kotlinx-serialization-core dependency is unwanted by some consumers]** → It's
  `implementation`, so it isn't on the consumer's compile classpath. If it becomes a problem, swap it
  for a `classify` hook.
