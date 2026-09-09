# kmp-dataresult

A small vocabulary for getting data from a source onto a screen, for Kotlin Multiplatform.

Two ideas, and adapters that connect them to real data sources:

- **`Outcome`** — what a data source emits: `Loading`, `Data`, or `Error`, where errors are a fixed,
  transport-agnostic set (`DataError`) rather than whatever exception type a client happened to
  throw. Nothing above the data layer has to know whether you fetch with GraphQL, REST, or a cache.
- **`ContentState`** — what a screen renders: the last known value, always present, plus a status
  that tracks request activity independently. That separation is what gives you
  stale-while-revalidate for free — a refresh shows an indicator over the current content instead of
  replacing it with a spinner, and a failed refresh keeps what was already there.

## Artifacts

| Artifact | Contents | Depends on |
| --- | --- | --- |
| `io.github.solcott:dataresult` | `Outcome`, `DataError`, `Origin` | nothing |
| `io.github.solcott:uistate` | `ContentState`, `LoadStatus`, `applyEmission` | `dataresult` |
| `io.github.solcott:dataresult-apollo` | Apollo GraphQL → `Outcome` | `dataresult`, `apollo-api` |
| `io.github.solcott:dataresult-store5` | Store5 → `Outcome` | `dataresult`, `store5` |
| `io.github.solcott:uistate-circuit` | Collects `Outcome`s into retained `ContentState` | `uistate`, `circuit-retained` |

Take only what you need: a repository module usually wants `dataresult` plus one adapter, and only
the presentation layer needs `uistate`.

**Targets:** `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js`, `wasmJs`.
`dataresult-store5` has no `macosArm64` — Store5 publishes no artifact for it.

## Using it

Both halves fit together in one line. A source produces `Outcome`s; the consumer folds them:

```kotlin
// Data layer -- Store5 here, but the signature says nothing about Store5.
fun articles(): Flow<Outcome<List<Article>>> = store.stream(request).asOutcomes()

// Presentation layer.
var state by mutableStateOf(ContentState(emptyList<Article>()))
repository.articles().collect { state = state.applyEmission(it) }
```

In a Circuit presenter, `uistate-circuit` does that collection for you, into state that survives a
configuration change:

```kotlin
val state = produceContentState(initial = emptyList(), retryTrigger) { repository.articles() }
```

For a source whose parameters change *while* it is on screen — a search term, a filter — use
`produceContentStateFor`, which cancels the in-flight request on each new value and marks the state
reloading first, so the current content stays put under a refresh indicator:

```kotlin
val state =
  produceContentStateFor(initial = emptyList(), params = filters, retryTrigger) { filter ->
    repository.search(filter.query, filter.tags)
  }
```

Two names rather than overloads: a lambda written `{ repository.foo() }` satisfies both shapes, by
ignoring the implicit `it`, so as overloads every call passing a `Flow` would be ambiguous.

> **Circuit stops collecting for a paused record.** In a multi-pane layout, Circuit pauses the
> record that is not current and `pausableState` drops the producer from composition. The pane that
> is not on top silently stops re-querying, with no error anywhere. Wrap each composed pane in
> `ProvideRecordLifecycle(isActive = true)` if it should keep running.

`state` now carries everything a screen needs:

```kotlin
when {
  !state.hasLoaded && state.isLoading -> Spinner()
  !state.hasLoaded -> ErrorScreen(state.errorOrNull)
  else -> Content(state.data, isRefreshing = state.isLoading)
}
```

Check `hasLoaded` rather than testing `data` for emptiness — an empty list is a real answer, and
treating it as "nothing yet" leaves a spinner over a legitimately empty screen.

For an intentional re-fetch (a retry, a filter change, pull-to-refresh) call `state.reloading()` to
put up the indicator immediately; the next emission settles it.

### Apollo

```kotlin
apolloClient.query(ArticlesQuery()).toFlow()
  .mapToOutcome(onException = { logger.e(it) { "Data request failed" } }) { articles.map { it.toModel() } }
```

Cache-miss responses are dropped rather than surfaced as errors — under a cache-then-network policy
a network response follows. GraphQL `errors` are checked first, so a response carrying both an error
and a cache miss surfaces the error.

## Installing

GitHub Packages authenticates **every** read, including public ones, so consumers need a token.
Create a classic PAT with the `read:packages` scope and put it in `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.key=ghp_yourtoken
```

Then, in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven {
      url = uri("https://maven.pkg.github.com/solcott/kmp-dataresult")
      credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
      }
      // Keeps every other dependency off this repository, so a token problem can't cascade.
      content { includeGroup("io.github.solcott") }
    }
  }
}
```

## Developing

```bash
./gradlew ktfmtFormat        # required before committing; CI runs ktfmtCheck
./gradlew build              # every target, plus tests
./gradlew publishToMavenLocal
```

`publishToMavenLocal` is how to try a change against a consuming project before releasing. Point the
consumer at a `-SNAPSHOT` version and make sure `mavenLocal()` is in its repositories.

Releasing is described in [RELEASING.md](RELEASING.md).

## Design notes

**Why `Loading` is an `Outcome` and not just a `ContentState` concern.** Some sources report their
own request lifecycle and some don't. Store5 emits `Loading` when it goes back to the fetcher, and
losing that signal means losing the background-refresh indicator. Sources that can't observe their
own lifecycle — an Apollo flow, say — simply never emit it, and a consumer that starts in a loading
state is unaffected either way.

**Why `sealed class` and not `sealed interface`.** These types are exported to Swift wholesale by at
least one consumer. Swift export rejects generic subtypes conforming to an erased parent protocol,
and emits nested member typealiases without an access modifier (so they default to `internal`).
`sealed class` avoids both. Keep Compose types out of `dataresult` and `uistate` for the same
reason — a Compose type anywhere in the reachable API breaks the iOS build with no warning.

**Why the adapters take a callback instead of a logger.** `mapToOutcome`'s `onException` parameter
keeps this library independent of any logging framework, and lets a caller inject its own tagged
instance so tests can assert on what was logged.
