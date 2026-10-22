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
| `io.github.solcott:uistate` | `ContentState`, `LoadStatus`, `applyEmission`, `ContentStates2`–`5`, `scanContentState` | `dataresult`, `kotlinx-coroutines-core` |
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

A cache miss is not an answer. A database-backed source of truth returns an empty list for a key it
has never fetched, and Store emits that before the fetch starts — so, forwarded as it is, a first
visit shows an empty screen while the network is still being asked. Tell the adapter a fetch is
coming and what empty means, and it holds that first read back until the fetch answers:

```kotlin
store.stream(request).asOutcomes(fetching = request.refresh) { it.isEmpty() }
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
treating it as "nothing yet" leaves a spinner over a legitimately empty screen. The exception is an
empty value from cache while its request is still in flight or has failed: that is a cache miss, not
a result. The adapters hold misses back where they can (see above); for a source that can't,
`state.hasAnswer { it.isEmpty() }` is `hasLoaded` that says no to one. It is only ever false while a
request is outstanding or has failed, so it can't hang a spinner either.

For an intentional re-fetch (a retry, a filter change, pull-to-refresh) call `state.reloading()` to
put up the indicator immediately; the next emission settles it.

### Without Compose

`uistate` has the same fold for hosts that aren't Compose, with the same rules: data is replaced
only by `Data`, the status settles on every emission, and a still-loading state settles when the
source completes but never when the collection is cancelled. It comes in two shapes.

In a ViewModel, hold a `MutableStateFlow` and fold into it with `collectFrom`:

```kotlin
class ArticlesViewModel(private val repository: ArticlesRepository) : ViewModel() {
  private val _state = MutableStateFlow(ContentState(emptyList<Article>()))
  val state: StateFlow<ContentState<List<Article>>> = _state.asStateFlow()

  init {
    viewModelScope.launch { _state.collectFrom(repository.articles()) }
  }
}
```

A retry is a parameter that changes. `collectLatestFrom` cancels the in-flight request on each new
value and marks the state reloading, so the articles stay on screen under the indicator:

```kotlin
  private val attempt = MutableStateFlow(0)

  init {
    viewModelScope.launch { _state.collectLatestFrom(attempt) { repository.articles() } }
  }

  fun retry() = attempt.update { it + 1 }
```

In a Decompose component, the same holder goes in a `retainedInstance` with its own scope, so it
survives configuration changes and stops when the component is destroyed:

```kotlin
class ArticlesComponent(context: ComponentContext, repository: ArticlesRepository) :
  ComponentContext by context {
  private val holder = retainedInstance { Holder(repository) }
  val state: StateFlow<ContentState<List<Article>>> = holder.state

  private class Holder(repository: ArticlesRepository) : InstanceKeeper.Instance {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ContentState(emptyList<Article>()))
    val state = _state.asStateFlow()

    init {
      scope.launch { _state.collectFrom(repository.articles()) }
    }

    override fun onDestroy() = scope.cancel()
  }
}
```

Both use the collector rather than `scanContentState(initial).stateIn(scope, WhileSubscribed(…),
initial)`, because `stateIn` restarts a cold upstream on every resubscription, and a restarted
fold starts over from `initial`: the screen drops back to its placeholder. The collector folds into
the holder's *current* value, so a restart carries on from the last state. It is the same trap as
keying `retain` in Compose.

When a cold stream of states is what you want, `Flow<Outcome<T>>.scanContentState(initial)` emits
`initial` and then one state per outcome, and `params.scanContentStateLatest(initial) { p -> … }`
restarts it on each distinct parameter. From SwiftUI, expose one from the exported Kotlin module:

```kotlin
class ArticlesModel(repository: ArticlesRepository) {
  val state: Flow<ContentState<List<Article>>> =
    repository.articles().scanContentState(ContentState(emptyList()))
}
```

Swift export bridges it to a typed flow with `asAsyncSequence()`:

```swift
@Observable @MainActor
final class ArticlesObserver {
  private let model: ArticlesModel
  private(set) var state: ContentState?

  init(_ model: ArticlesModel) { self.model = model }

  // Attach with `.task { await observer.observe() }`.
  func observe() async {
    do {
      for try await next in model.state.asAsyncSequence() { state = next }
    } catch {}
  }
}
```

The fold restarts with the `.task`, so this suits state that lives as long as the view. For state
that should outlive it, hold a `MutableStateFlow` on the Kotlin side and fold into it with
`collectFrom`, as above. Every form also takes a group of sources (`ContentStates2`…`5`).

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

### Paging

A list loaded a page at a time from a cursor is one flow per page. Each emits
`Outcome<Page<T, M>>`, where a `Page` holds the items, an optional header (`meta`) and the next
cursor:

```kotlin
// Data layer: one page, after `cursor` (null for the first).
fun followers(account: String, cursor: String?): Flow<Outcome<Page<Profile, Profile>>> =
  apolloClient.query(FollowersQuery(account, cursor)).toFlow().mapToOutcome {
    val next = followers.pageInfo.endCursor.takeIf { followers.pageInfo.hasNextPage }
    Page(followers.nodes.map { it.toModel() }, subject.toModel(), next)
  }
```

A Circuit presenter holds the list with `rememberRetainedPaging`, and renders its `PagedState`:

```kotlin
val followers =
  rememberRetainedPaging(screen.account, idOf = Profile::did) { cursor ->
    repository.followers(screen.account, cursor)
  }
val list = followers.state
when {
  !list.hasLoaded -> if (list.refresh is LoadStatus.Failed) FullScreenError(onRetry = followers::retry) else Spinner()
  else -> FollowList(
    subject = list.meta,
    items = list.items,
    isRefreshing = list.refresh is LoadStatus.Loading,
    footer = when (list.append) {
      LoadStatus.Loading -> Footer.Spinner
      is LoadStatus.Failed -> Footer.Retry(followers::retry)
      LoadStatus.Idle -> if (list.endReached) Footer.None else Footer.LoadMore(followers::loadMore)
    },
  )
}
```

A failed refresh keeps the items, and a failed page keeps the pages before it. `retry()` repeats
only what failed. Coming back to the screen shows the pages at once, without requesting them. A new
key keeps the old items under the refresh indicator until its first page arrives, which is right
for a search. Pass `onKeyChange = OnKeyChange.ClearItems` for a filter, where the old items would be
wrong. `update { items -> items.filterNot { it.did == unblocked } }` edits the list without a
request, and `enabled = false` loads nothing, as for a blank query.

A page source may emit more than once, such as a cached read and then the network. Each emission
replaces its own page, and the pages after it are kept as long as its next cursor is unchanged.
For nested lists, such as a comment thread's replies, use one holder per parent inside
`key(thread.uri)`. Start each from the replies that came with the thread with
`initial = Outcome.Data(Page(thread.replies, Unit, thread.repliesCursor), Origin.Network)`, so
nothing is requested until `loadMore()`.

`PagedState`'s fold (`applyFirstPage`, `applyNextPage`, `refreshing`, `restarted`, `updated`) is
public and pure, for a host other than Circuit to drive.

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

**Why `Outcome` and `ContentState` are two types.** An `Outcome` is one emission, and a
`ContentState` is every emission so far folded together by `applyEmission`. Showing cached data
under a refresh indicator, or keeping it after a failure, exists only after that fold. A single type
would need either a `Loading` that carries the previous value, which makes every source remember its
last emission, or a nullable `data`, which can't tell "nothing loaded yet" from "loaded, and null".
`ContentState` also starts from a placeholder value, and choosing one is a UI decision. Keeping it
in `uistate` lets adapters and repositories depend on `dataresult` alone.

**Why `Loading` is an `Outcome` and not just a `ContentState` concern.** Some sources report their
own request lifecycle and some don't. Store5 emits `Loading` when it goes back to the fetcher, and
losing that signal means losing the background-refresh indicator. Sources that can't observe their
own lifecycle — an Apollo flow, say — simply never emit it, and a consumer that starts in a loading
state is unaffected either way.

**Why a cache miss is not `Data`.** A database-backed source of truth can't return null for a key it
has never fetched. Its query returns an empty list, which looks exactly like "nothing matched".
Emitted as `Data`, that list settles the consumer's status and counts as loaded. A first visit then
shows an empty screen until the network answers, and keeps showing it if the network fails. Only the
source knows a fetch is coming, so the source holds the miss back: `asOutcomes(fetching, isEmpty)`
does, as does the Apollo adapter's cache-miss drop. `hasAnswer` exists for sources that can't know.
It reads `status` as well as `origin`, so it can never be false once a state has settled.

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

**Why a paging cursor is a `String`.** Cursor-paged APIs (atproto, GraphQL Relay) hand out opaque
string tokens, and an offset encodes as one losslessly. A cursor type parameter would make every
paging type take three generics, and Swift export would show it as an untyped `Any?`.

**Why `PagedState` keeps pages separately.** A page's source can emit more than once: a cached read
and then the network, or a database watcher after a local write. Replacing only that page keeps
the user's scroll position. A changed next cursor drops the pages after it, because they were read
from a cursor that no longer exists. The same bookkeeping lets the fold drop a page requested
before a refresh or a key change, so the check that keeps a stale result out of the list is in pure,
unit-tested code rather than in the holder.

**Why nested lists are one holder per parent.** A thread's replies have their own cursor, their own
failure and their own retry. Nesting `PagedState`s would put child type parameters on every caller
and a second level of rules in the fold, for one screen. `initial` covers what nesting would have
given that screen: starting from the replies that came with the thread.

**Why keep-or-clear on a new key is a parameter.** The two differ in one step of the fold.
Separate entry points would duplicate every parameter and doc paragraph. Keep is the default
because it is what `produceRetainedContentState` already does on a key change.

**Why the adapters take a callback instead of a logger.** `mapToOutcome`'s `onException` parameter
keeps this library independent of any logging framework, and lets a caller inject its own tagged
instance so tests can assert on what was logged.
