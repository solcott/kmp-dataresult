package io.github.solcott.uistate

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin

/**
 * How to collapse several [LoadStatus]es into one when they disagree — specifically, when one
 * source has failed while another is still loading.
 *
 * Only [combinedStatus] and [combine] consult this, because they have to produce a single
 * [LoadStatus]. The list aggregates `isLoading` and `errorOrNull` keep loading and failure
 * independent and never need it.
 */
enum class StatusPrecedence {
  /**
   * A failure wins over anything still in flight. The default: an error is actionable — a retry —
   * and should not sit hidden behind another source's spinner for however long that source takes.
   */
  FailedFirst,

  /**
   * Anything still in flight wins over a failure, which surfaces once every source has settled.
   * Fewer flashes of error UI during a multi-source load, at the cost of a failure waiting behind a
   * slow or long-lived source.
   */
  LoadingFirst,
}

/**
 * True while any of these states has a request in flight.
 *
 * Together with `errorOrNull` and `hasLoaded` on a list, this gives a UI state holding several
 * separately-typed [ContentState]s one place to ask about all of them, while each keeps its own
 * typed data:
 * ```
 * data class State(
 *   val articles: ContentState<List<Article>>,
 *   val tags: ContentState<List<Tag>>,
 * ) {
 *   private val sources = listOf(articles, tags)
 *   val isLoading get() = sources.isLoading
 *   val error get() = sources.errorOrNull
 * }
 * ```
 *
 * Loading and failure stay independent here — both can be true at once — so no [StatusPrecedence]
 * is involved. Use [combinedStatus] when a single [LoadStatus] is needed.
 */
val List<ContentState<*>>.isLoading: Boolean
  get() = any { it.isLoading }

/**
 * The first failure among these states, in list order, or null if none failed.
 *
 * Only the first is reported; the individual states still hold the rest.
 */
val List<ContentState<*>>.errorOrNull: DataError?
  get() = firstNotNullOfOrNull { it.errorOrNull }

/**
 * True once every one of these states has produced a value — vacuously true for an empty list.
 *
 * Like the single-state version, this reads `origin` rather than testing data for emptiness, so a
 * source that legitimately loaded nothing still counts.
 */
val List<ContentState<*>>.hasLoaded: Boolean
  get() = all { it.hasLoaded }

/**
 * Collapses these states into the single [LoadStatus] a screen rendering all of them should show.
 * - [StatusPrecedence.FailedFirst]: any failure, else loading if any is in flight, else idle.
 * - [StatusPrecedence.LoadingFirst]: loading if any is in flight, else any failure, else idle.
 *
 * With several failures the first in list order is reported. [combine] uses exactly this for the
 * status of what it returns, so the two cannot disagree.
 */
fun List<ContentState<*>>.combinedStatus(
  precedence: StatusPrecedence = StatusPrecedence.FailedFirst
): LoadStatus {
  val failed = firstNotNullOfOrNull { it.status as? LoadStatus.Failed }
  val loading = any { it.isLoading }
  return when (precedence) {
    StatusPrecedence.FailedFirst -> failed ?: if (loading) LoadStatus.Loading else LoadStatus.Idle
    StatusPrecedence.LoadingFirst -> if (loading) LoadStatus.Loading else failed ?: LoadStatus.Idle
  }
}

/**
 * Combines two states into one, for a screen that renders them as a single block — nothing until
 * both are ready.
 *
 * The result is a real [ContentState], so everything that works on one state works on it:
 * - **data** is [transform] of both values. It runs on every call — every recomposition, in a
 *   presenter — so keep it a cheap, pure mapping. Until a source has loaded, its value is the
 *   placeholder it was built with; check the result's `hasLoaded` before trusting the data.
 * - **origin** is null until *every* source has loaded, so the result's `hasLoaded` means all of
 *   them have. After that it is [Origin.Cache] if any part came from cache — the whole is only as
 *   fresh as its stalest part — and [Origin.Network] otherwise.
 * - **status** is [combinedStatus] of the sources under [precedence].
 *
 * For a UI state that keeps its sources separate and only wants one place to check loading and
 * errors, use `isLoading`, `errorOrNull` and `hasLoaded` on `listOf(…)` instead.
 *
 * Named like `kotlinx.coroutines.flow.combine`, and an overload of it rather than a clash — the
 * parameter types differ — so both can be imported into the same file.
 */
fun <A, B, R> combine(
  a: ContentState<A>,
  b: ContentState<B>,
  precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
  transform: (A, B) -> R,
): ContentState<R> = listOf(a, b).combinedInto(precedence, transform(a.data, b.data))

/** Combines three states into one. See the two-state [combine] for how each field is derived. */
fun <A, B, C, R> combine(
  a: ContentState<A>,
  b: ContentState<B>,
  c: ContentState<C>,
  precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
  transform: (A, B, C) -> R,
): ContentState<R> = listOf(a, b, c).combinedInto(precedence, transform(a.data, b.data, c.data))

/** Combines four states into one. See the two-state [combine] for how each field is derived. */
fun <A, B, C, D, R> combine(
  a: ContentState<A>,
  b: ContentState<B>,
  c: ContentState<C>,
  d: ContentState<D>,
  precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
  transform: (A, B, C, D) -> R,
): ContentState<R> =
  listOf(a, b, c, d).combinedInto(precedence, transform(a.data, b.data, c.data, d.data))

/** Combines five states into one. See the two-state [combine] for how each field is derived. */
fun <A, B, C, D, E, R> combine(
  a: ContentState<A>,
  b: ContentState<B>,
  c: ContentState<C>,
  d: ContentState<D>,
  e: ContentState<E>,
  precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
  transform: (A, B, C, D, E) -> R,
): ContentState<R> =
  listOf(a, b, c, d, e).combinedInto(precedence, transform(a.data, b.data, c.data, d.data, e.data))

private fun <R> List<ContentState<*>>.combinedInto(
  precedence: StatusPrecedence,
  data: R,
): ContentState<R> = ContentState(data, combinedOrigin(), combinedStatus(precedence))

private fun List<ContentState<*>>.combinedOrigin(): Origin? =
  when {
    any { it.origin == null } -> null
    any { it.origin == Origin.Cache } -> Origin.Cache
    else -> Origin.Network
  }
