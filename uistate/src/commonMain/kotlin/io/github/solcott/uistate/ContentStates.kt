package io.github.solcott.uistate

import androidx.compose.runtime.Immutable
import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.OutcomeGroup
import io.github.solcott.dataresult.Outcomes2
import io.github.solcott.dataresult.Outcomes3
import io.github.solcott.dataresult.Outcomes4
import io.github.solcott.dataresult.Outcomes5
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * One [ContentState] per source of an [OutcomeGroup]: what a screen holds when it renders several
 * sources that should each keep their own last value.
 *
 * [applyEmission] folds each outcome of a group into its own slot with
 * `ContentState.applyEmission`, so a source that is loading or has failed leaves every other
 * source's data and status alone. Local results can show while a network source is still on its
 * way, and one failed source does not blank the rest. For a block that should render as a unit,
 * with nothing shown until every part is ready, collapse the group with `toContentState` instead.
 *
 * Build the initial group with `contentStatesOf`, and collect a flow of groups with
 * `produceContentStates` (`uistate-compose`) or `produceRetainedContentStates` (`uistate-circuit`).
 * Each slot stays reachable on its own, by position (`first`, `second`, …) or by destructuring.
 *
 * [O] pairs each arity with the [OutcomeGroup] of the same arity and types, so a group can only be
 * fed the outcomes it was built for. Every subclass overrides [applyEmission], [reloading] and
 * [settled] to return its own type. The producers rely on that, and the hierarchy is sealed so that
 * nothing outside this library can break it.
 */
@Immutable
sealed class ContentStateGroup<O : OutcomeGroup> {
  /** Every state in the group, in argument order. */
  abstract val states: ImmutableList<ContentState<*>>

  /** Folds one emission of every source into that source's own state. */
  abstract fun applyEmission(outcomes: O): ContentStateGroup<O>

  /**
   * Marks every source reloading, keeping the data each one holds. See `ContentState.reloading`.
   */
  abstract fun reloading(): ContentStateGroup<O>

  /** Settles every source that is still loading. See `ContentState.settled`. */
  abstract fun settled(): ContentStateGroup<O>

  /** True while at least one source has a request in flight. */
  val isAnyLoading: Boolean
    get() = states.isLoading

  /** True while every source has a request in flight. */
  val isAllLoading: Boolean
    get() = states.all { it.isLoading }

  /** True if the most recent request of at least one source failed. */
  val hasAnyError: Boolean
    get() = states.any { it.errorOrNull != null }

  /** True if the most recent request of every source failed. */
  val hasAllErrors: Boolean
    get() = states.all { it.errorOrNull != null }

  /** The failure of every source whose most recent request failed, in argument order. */
  val errors: ImmutableList<DataError>
    get() = states.mapNotNull { it.errorOrNull }.toImmutableList()

  /** The first failure in argument order, or null if none failed. [errors] holds the rest. */
  val errorOrNull: DataError?
    get() = states.errorOrNull

  /** True once every source has produced a value. See `ContentState.hasLoaded`. */
  val hasLoaded: Boolean
    get() = states.hasLoaded

  /**
   * The single [LoadStatus] a screen rendering every source as one should show. See
   * `List<ContentState<*>>.combinedStatus`.
   */
  fun combinedStatus(precedence: StatusPrecedence = StatusPrecedence.FailedFirst): LoadStatus =
    states.combinedStatus(precedence)
}

/** Two sources, one [ContentState] each. See [ContentStateGroup]. */
data class ContentStates2<A, B>(val first: ContentState<A>, val second: ContentState<B>) :
  ContentStateGroup<Outcomes2<A, B>>() {
  override val states: ImmutableList<ContentState<*>> = persistentListOf(first, second)

  override fun applyEmission(outcomes: Outcomes2<A, B>): ContentStates2<A, B> =
    ContentStates2(first.applyEmission(outcomes.first), second.applyEmission(outcomes.second))

  override fun reloading(): ContentStates2<A, B> =
    ContentStates2(first.reloading(), second.reloading())

  override fun settled(): ContentStates2<A, B> = ContentStates2(first.settled(), second.settled())

  /**
   * Collapses the group into one [ContentState] with the top-level [combine]: not loaded until
   * every source has, and one status under [precedence]. [transform] runs on every call, so keep it
   * a cheap, pure mapping.
   */
  fun <R> toContentState(
    precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
    transform: (A, B) -> R,
  ): ContentState<R> = combine(first, second, precedence, transform)
}

/** Three sources, one [ContentState] each. See [ContentStateGroup]. */
data class ContentStates3<A, B, C>(
  val first: ContentState<A>,
  val second: ContentState<B>,
  val third: ContentState<C>,
) : ContentStateGroup<Outcomes3<A, B, C>>() {
  override val states: ImmutableList<ContentState<*>> = persistentListOf(first, second, third)

  override fun applyEmission(outcomes: Outcomes3<A, B, C>): ContentStates3<A, B, C> =
    ContentStates3(
      first.applyEmission(outcomes.first),
      second.applyEmission(outcomes.second),
      third.applyEmission(outcomes.third),
    )

  override fun reloading(): ContentStates3<A, B, C> =
    ContentStates3(first.reloading(), second.reloading(), third.reloading())

  override fun settled(): ContentStates3<A, B, C> =
    ContentStates3(first.settled(), second.settled(), third.settled())

  /** Collapses the group into one [ContentState]. See [ContentStates2.toContentState]. */
  fun <R> toContentState(
    precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
    transform: (A, B, C) -> R,
  ): ContentState<R> = combine(first, second, third, precedence, transform)
}

/** Four sources, one [ContentState] each. See [ContentStateGroup]. */
data class ContentStates4<A, B, C, D>(
  val first: ContentState<A>,
  val second: ContentState<B>,
  val third: ContentState<C>,
  val fourth: ContentState<D>,
) : ContentStateGroup<Outcomes4<A, B, C, D>>() {
  override val states: ImmutableList<ContentState<*>> =
    persistentListOf(first, second, third, fourth)

  override fun applyEmission(outcomes: Outcomes4<A, B, C, D>): ContentStates4<A, B, C, D> =
    ContentStates4(
      first.applyEmission(outcomes.first),
      second.applyEmission(outcomes.second),
      third.applyEmission(outcomes.third),
      fourth.applyEmission(outcomes.fourth),
    )

  override fun reloading(): ContentStates4<A, B, C, D> =
    ContentStates4(first.reloading(), second.reloading(), third.reloading(), fourth.reloading())

  override fun settled(): ContentStates4<A, B, C, D> =
    ContentStates4(first.settled(), second.settled(), third.settled(), fourth.settled())

  /** Collapses the group into one [ContentState]. See [ContentStates2.toContentState]. */
  fun <R> toContentState(
    precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
    transform: (A, B, C, D) -> R,
  ): ContentState<R> = combine(first, second, third, fourth, precedence, transform)
}

/** Five sources, one [ContentState] each. See [ContentStateGroup]. */
data class ContentStates5<A, B, C, D, E>(
  val first: ContentState<A>,
  val second: ContentState<B>,
  val third: ContentState<C>,
  val fourth: ContentState<D>,
  val fifth: ContentState<E>,
) : ContentStateGroup<Outcomes5<A, B, C, D, E>>() {
  override val states: ImmutableList<ContentState<*>> =
    persistentListOf(first, second, third, fourth, fifth)

  override fun applyEmission(outcomes: Outcomes5<A, B, C, D, E>): ContentStates5<A, B, C, D, E> =
    ContentStates5(
      first.applyEmission(outcomes.first),
      second.applyEmission(outcomes.second),
      third.applyEmission(outcomes.third),
      fourth.applyEmission(outcomes.fourth),
      fifth.applyEmission(outcomes.fifth),
    )

  override fun reloading(): ContentStates5<A, B, C, D, E> =
    ContentStates5(
      first.reloading(),
      second.reloading(),
      third.reloading(),
      fourth.reloading(),
      fifth.reloading(),
    )

  override fun settled(): ContentStates5<A, B, C, D, E> =
    ContentStates5(
      first.settled(),
      second.settled(),
      third.settled(),
      fourth.settled(),
      fifth.settled(),
    )

  /** Collapses the group into one [ContentState]. See [ContentStates2.toContentState]. */
  fun <R> toContentState(
    precedence: StatusPrecedence = StatusPrecedence.FailedFirst,
    transform: (A, B, C, D, E) -> R,
  ): ContentState<R> = combine(first, second, third, fourth, fifth, precedence, transform)
}

/**
 * The group to show before anything has loaded: each source not yet loaded, holding its
 * placeholder. The group counterpart of passing `initial` to a single-source producer.
 */
fun <A, B> contentStatesOf(first: A, second: B): ContentStates2<A, B> =
  ContentStates2(ContentState(first), ContentState(second))

/** The initial [ContentStates3]. See the two-source [contentStatesOf]. */
fun <A, B, C> contentStatesOf(first: A, second: B, third: C): ContentStates3<A, B, C> =
  ContentStates3(ContentState(first), ContentState(second), ContentState(third))

/** The initial [ContentStates4]. See the two-source [contentStatesOf]. */
fun <A, B, C, D> contentStatesOf(
  first: A,
  second: B,
  third: C,
  fourth: D,
): ContentStates4<A, B, C, D> =
  ContentStates4(
    ContentState(first),
    ContentState(second),
    ContentState(third),
    ContentState(fourth),
  )

/** The initial [ContentStates5]. See the two-source [contentStatesOf]. */
fun <A, B, C, D, E> contentStatesOf(
  first: A,
  second: B,
  third: C,
  fourth: D,
  fifth: E,
): ContentStates5<A, B, C, D, E> =
  ContentStates5(
    ContentState(first),
    ContentState(second),
    ContentState(third),
    ContentState(fourth),
    ContentState(fifth),
  )
