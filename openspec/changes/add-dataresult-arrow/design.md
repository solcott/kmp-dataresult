# Design

## Context

The module is thin. Its decisions are about names, `Origin`, `Loading`, and exceptions, and
they are recorded here because each one is visible to consumers. `dataresult`'s accessors use the
`…OrNull` convention (`dataOrNull()`, `errorOrNull`). Arrow's builders are `inline`, so they work
in `suspend` code without separate suspend overloads.

## Goals / Non-Goals

**Goals:**
- Conversions in both directions that read like Arrow at the call site.

**Non-Goals:**
- Flow operators (`Flow<Either<…>>.asOutcomes()`). `.map { it.toOutcome() }` is one line, and an
  operator would need its own decision about `Loading`. Add one only on demand.
- `Ior`, `Option`, `Validated`, or accumulating errors. `DataError` is a single cause.
- Catching exceptions in the builder. Classifying exceptions belongs to the source adapters, such as
  the Ktor one.

## Decisions

### 1. API surface

```kotlin
fun <T> Either<DataError, T>.toOutcome(origin: Origin = Origin.Network): Outcome<T>
inline fun <E, T> Either<E, T>.toOutcome(origin: Origin = Origin.Network, mapError: (E) -> DataError): Outcome<T>
fun <T> Outcome<T>.toEitherOrNull(): Either<DataError, T>?
inline fun <T> outcome(origin: Origin = Origin.Network, block: Raise<DataError>.() -> T): Outcome<T>
```

- **`outcome { }` mirrors `either { }`.** It is the name an Arrow user reaches for. It doesn't clash
  with the Ktor change's `httpOutcome`, which was prefixed for this reason.
- **The builder is `inline`**, implemented on Arrow's `recover`/`fold` over `Raise`, so it works in
  `suspend` code, like `either { }`.
- **`toEitherOrNull`, not `toEither`**, because `Loading` has no `Either`, and the name follows
  `dataOrNull()`. Rejected: a sealed or nullable `Either<DataError, T?>`, which would blur "pending"
  with "null data".
- **`Origin` defaults to `Network`.** An `Either` doesn't record where it came from, and most
  `Either` repositories wrap a network call. A caller wrapping a database read passes `Origin.Cache`,
  which matters for `ContentState.hasAnswer`.

### 2. Dependencies

- `api(arrow-core)`: `Either` and `Raise` appear in signatures.
- **Floor:** the lowest Arrow 2.x publishing all seven targets. Arrow 2 is where `Raise` lives in
  `arrow-core`, with the 1.x deprecations removed. Find it with the
  `kotlin-multiplatform-libraries-expert` skill or klibs.io. If `macosArm64` or `wasmJs` is missing,
  drop that target with a comment in the build file, as `dataresult-store5` does, and add the
  exception to the README's Targets line.

## Risks / Trade-offs

- **[The default `Origin` is wrong for a DB-backed `Either`]** → The KDoc and README call out
  `Origin.Cache`. The parameter is not hidden.
- **[Thrown exceptions escape `outcome { }`]** → This is deliberate and matches `either { }`. The KDoc
  says so, and points to the Ktor adapter for classification.
