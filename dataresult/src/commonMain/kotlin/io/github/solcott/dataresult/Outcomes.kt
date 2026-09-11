package io.github.solcott.dataresult

/**
 * Several [Outcome]s emitted together, each keeping its own type — what a data source returns when
 * it draws on more than one source at once. Build a flow of them with `combineOutcomes`.
 *
 * Each outcome stays reachable on its own, by position ([Outcomes2.first], [Outcomes2.second], …)
 * or by destructuring — `val (history, categories) = outcomes` — which is how a caller gives the
 * positions names. The members here answer for all of them at once.
 *
 * The concrete types are positional and fixed at two to five sources, and this class is sealed, so
 * that `uistate` can fold a group into one `ContentState` per source with no per-screen code. A
 * group with consumer-defined named fields could not be folded generically.
 */
sealed class OutcomeGroup {
  /** Every outcome in the group, in argument order. */
  abstract val outcomes: List<Outcome<*>>

  /** True while at least one source has a request in flight. */
  val isAnyLoading: Boolean
    get() = outcomes.any { it.isLoading }

  /** True while every source has a request in flight — nothing has answered yet. */
  val isAllLoading: Boolean
    get() = outcomes.all { it.isLoading }

  /** True if at least one source failed. */
  val hasAnyError: Boolean
    get() = outcomes.any { it is Outcome.Error }

  /** True if every source failed. */
  val hasAllErrors: Boolean
    get() = outcomes.all { it is Outcome.Error }

  /** The failure of every source that failed, in argument order. */
  val errors: List<DataError>
    get() = outcomes.mapNotNull { it.errorOrNull }

  /** The first failure in argument order, or null if none failed. [errors] holds the rest. */
  val errorOrNull: DataError?
    get() = outcomes.firstNotNullOfOrNull { it.errorOrNull }

  /**
   * True if every source carries a value in this emission. Says nothing about earlier emissions: a
   * source that is reloading reports [Outcome.Loading] here even if it answered before. Folding
   * into `ContentState` is what remembers the last value.
   */
  val hasAllData: Boolean
    get() = outcomes.all { it is Outcome.Data }
}

/** Two outcomes emitted together. See [OutcomeGroup]. */
data class Outcomes2<out A, out B>(val first: Outcome<A>, val second: Outcome<B>) : OutcomeGroup() {
  override val outcomes: List<Outcome<*>> = listOf(first, second)
}

/** Three outcomes emitted together. See [OutcomeGroup]. */
data class Outcomes3<out A, out B, out C>(
  val first: Outcome<A>,
  val second: Outcome<B>,
  val third: Outcome<C>,
) : OutcomeGroup() {
  override val outcomes: List<Outcome<*>> = listOf(first, second, third)
}

/** Four outcomes emitted together. See [OutcomeGroup]. */
data class Outcomes4<out A, out B, out C, out D>(
  val first: Outcome<A>,
  val second: Outcome<B>,
  val third: Outcome<C>,
  val fourth: Outcome<D>,
) : OutcomeGroup() {
  override val outcomes: List<Outcome<*>> = listOf(first, second, third, fourth)
}

/** Five outcomes emitted together. See [OutcomeGroup]. */
data class Outcomes5<out A, out B, out C, out D, out E>(
  val first: Outcome<A>,
  val second: Outcome<B>,
  val third: Outcome<C>,
  val fourth: Outcome<D>,
  val fifth: Outcome<E>,
) : OutcomeGroup() {
  override val outcomes: List<Outcome<*>> = listOf(first, second, third, fourth, fifth)
}
