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
| `io.github.solcott:dataresult` | `Outcome`, `DataError`, `Origin`, `combineOutcomes` | `kotlinx-coroutines-core` |
| `io.github.solcott:uistate` | `ContentState`, `LoadStatus`, `applyEmission`, `ContentStates2`–`5` | `dataresult` |
| `io.github.solcott:dataresult-apollo` | Apollo GraphQL → `Outcome` | `dataresult`, `apollo-api` |
| `io.github.solcott:dataresult-store5` | Store5 → `Outcome` | `dataresult`, `store5` |
| `io.github.solcott:uistate-compose` | Collects `Outcome`s into `ContentState`, on androidx `retain` | `uistate`, Compose runtime |
| `io.github.solcott:uistate-circuit` | The same, retained in Circuit's registry | `uistate-compose`, `circuit-retained` |

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

In Compose, `uistate-compose` does that collection for you, into state retained wherever the host
retains values — across configuration changes on Android, by default:

```kotlin
val state = produceContentState(initial = emptyList(), retryTrigger) { repository.articles() }
```

For a source whose parameters change *while* it is on screen — a search term, a filter — call it
on the parameters instead. Each new value cancels the in-flight request and marks the state
reloading first, so the current content stays put under a refresh indicator:

```kotlin
val state =
  filters.produceContentState(initial = emptyList(), retryTrigger) { filter ->
    repository.search(filter.query, filter.tags)
  }
```

The receiver is the whole difference: no receiver, a fixed source; a `Flow` receiver, a source
that changes with it. It is the shape of `flatMapLatest` — the receiver drives, the lambda returns
the flow to collect for each value. Debounce the receiver yourself if it needs it.

The receiverless one is deliberately *not* an extension on the source. `Flow<Outcome<T>>` is a
valid `Flow<P>` with `P = Outcome<T>`, so two extensions would be ambiguous at every call site.

In a Circuit presenter, use `uistate-circuit`'s `produceRetainedContentState` — the same two
functions and the same contract, with the state held in Circuit's registry rather than by androidx
`retain`. Each name mirrors the primitive underneath it: `produceContentState` ↔ Compose's
`produceState`, `produceRetainedContentState` ↔ Circuit's `produceRetainedState`. Both retain; the
name says which mechanism does it.

Holding your own `MutableState`? `state.collectFrom(source)` and
`state.collectLatestFrom(params) { … }` are the fold on its own — plain `suspend` functions, no
composition required.

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

### Combining states

A screen fed by several sources holds several `ContentState`s, usually of different types. There are
two ways to bring them together.

**Keep them separate, check them together.** `isLoading`, `errorOrNull` and `hasLoaded` also work on
a `List` of states, with the same names as on one:

```kotlin
data class State(val articles: ContentState<List<Article>>, val tags: ContentState<List<Tag>>) {
  private val sources = listOf(articles, tags)
  val isLoading get() = sources.isLoading   // any in flight
  val error get() = sources.errorOrNull     // first failure, in list order
}
```

Loading and failure stay independent here — both can be true at once.

**Merge them into one.** For a screen that can't show anything until every source is ready,
`combine` returns a single `ContentState`, so everything that works on one state works on it:

```kotlin
val profile: ContentState<Profile> = combine(user, settings) { u, s -> Profile(u, s) }
```

Its `hasLoaded` means *every* source has loaded, and its origin is `Cache` if any part came from
cache. Its status has to be a single `LoadStatus`, so it takes a `StatusPrecedence` for when one
source has failed while another is still loading: `FailedFirst`, the default, surfaces the error at
once; `LoadingFirst` holds it until everything has settled. The same rule is available on its own as
`sources.combinedStatus(precedence)`, and `combine` is exactly that plus a transform.

### Combining sources

When one call has to answer from several sources at once — recent searches from disk, suggestions
from the network — combine the flows in the data layer. `combineOutcomes` takes two to five
`Flow<Outcome<…>>`s and emits an `Outcomes2`…`Outcomes5` whenever any of them does:

```kotlin
// Data layer: one method, three sources, each keeping its own type.
fun suggestions(query: String): Flow<Outcomes3<List<Recent>, List<Category>, List<Ingredient>>> =
  combineOutcomes(recents(query), categories(query), ingredients(query))
```

Every source is started with `Loading`, so the group emits at once and the fastest source is not
held back by the slowest. Each outcome stays reachable on its own — `outcomes.first`, or
`val (recents, categories, ingredients) = outcomes` — and the group answers for all of them:
`isAnyLoading`, `isAllLoading`, `hasAnyError`, `hasAllErrors`, `errors`, `errorOrNull`,
`hasAllData`. A source that throws cancels the group, so map failures to `Outcome.Error` at each
source.

On the presentation side, fold the group into one `ContentState` per source, so each keeps its own
last value — recents show while categories are still loading, and one failed source does not blank
the rest:

```kotlin
val states =
  query.produceContentStates(
    contentStatesOf(emptyList<Recent>(), emptyList<Category>(), emptyList<Ingredient>())
  ) { q ->
    repository.suggestions(q)
  }
val (recents, categories, ingredients) = states // a ContentState<List<…>> each
if (states.isAnyLoading) ProgressBar()
```

`produceRetainedContentStates` is the Circuit version, and `collectFrom` / `collectLatestFrom`
accept a group too. The group of states has the same aggregates, plus `hasLoaded` and
`combinedStatus(precedence)`. When the screen should show nothing until every source is ready,
`states.toContentState { a, b, c -> … }` collapses the group into one `ContentState` with
`combine`'s rules.

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

### Snapshots

Every green push to `main` publishes the current `-SNAPSHOT` version (the `version` in
[`gradle.properties`](gradle.properties)) to the same repository, so the setup above covers it:
depend on `X.Y.Z-SNAPSHOT`. Gradle caches a snapshot for 24 hours. To pick up the newest one on every
build, add this to the consuming module's `build.gradle.kts`, or pass `--refresh-dependencies` once:

```kotlin
configurations.all { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
```

## Developing

```bash
./gradlew ktfmtFormat        # required before committing; CI runs ktfmtCheck
./gradlew build              # every target, plus tests
./gradlew publishToMavenLocal
```

`publishToMavenLocal` is how to try a change against a consuming project before it is pushed. Point
the consumer at a `-SNAPSHOT` version and make sure `mavenLocal()` is in its repositories. Once the
change is on `main`, a [snapshot](#snapshots) does the same from any machine or CI.

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

**Why the combined types are positional.** `Outcomes2`…`Outcomes5` and
`ContentStates2`…`ContentStates5` number their slots rather than letting each consumer name them.
The library has to know every slot's type to fold a group into per-source `ContentState`s, and a
group with consumer-defined fields could only be folded by code written for it, on every screen.
Destructuring gives the slots names where they are used. Both hierarchies are sealed for the same
reason: the producers rely on each subclass returning its own type from `applyEmission`, and a
subclass from outside the library could not promise that.

**Why `combineOutcomes` seeds every source with `Loading`.** `kotlinx.coroutines.flow.combine` emits
nothing until every source has emitted once. A source that never reports its own lifecycle — an
Apollo flow, a local store mapped straight to data — would hold the whole group back behind
whichever source is slowest. The cost is a possibly doubled `Loading` from sources that already emit
one, and folding the same emission twice changes nothing.

**Why the adapters take a callback instead of a logger.** `mapToOutcome`'s `onException` parameter
keeps this library independent of any logging framework, and lets a caller inject its own tagged
instance so tests can assert on what was logged.
