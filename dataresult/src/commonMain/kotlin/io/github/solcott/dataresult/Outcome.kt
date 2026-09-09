package io.github.solcott.dataresult

/**
 * A single emission from a data source: [Loading] while a request is in flight, [Data] carrying a
 * value, or [Error] carrying a typed [DataError].
 *
 * An `Outcome` describes *one* emission in what may be an unbounded stream. A source can keep
 * emitting over time — cache then network, a cache watcher re-emitting after a local write, or a
 * subscription/SSE stream pushing server-driven changes — and each change is just another
 * `Outcome`. Consumers fold successive emissions into their own state rather than treating any one
 * as terminal.
 *
 * [Loading] carries no value, because it says nothing about the data — only that a request is
 * outstanding. A consumer folding emissions keeps whatever it already holds and shows it as stale
 * (see `ContentState.applyEmission` in the `uistate` module), which is what lets a source that
 * re-fetches in the background put up a refresh indicator without clearing the screen. Sources that
 * cannot observe their own request lifecycle simply never emit it; a consumer that starts in a
 * loading state is unaffected.
 *
 * [Data] and [Error] each record the [Origin] they came from, letting callers distinguish cached
 * data from fresh network data. [Loading] does not: which source will serve an in-flight request is
 * a fetch-policy detail consumers must not depend on.
 */
sealed class Outcome<out T> {
  data object Loading : Outcome<Nothing>()

  data class Data<out T>(val data: T, val origin: Origin) : Outcome<T>()

  data class Error(val cause: DataError, val origin: Origin) : Outcome<Nothing>()
}
