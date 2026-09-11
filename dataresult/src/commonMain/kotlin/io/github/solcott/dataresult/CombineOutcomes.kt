package io.github.solcott.dataresult

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/**
 * Combines two sources into one flow of [Outcomes2], emitting whenever either source does.
 *
 * Each source is started with [Outcome.Loading]. That is what this adds over
 * `kotlinx.coroutines.flow.combine`, and it is the reason to use it: `combine` emits nothing until
 * every source has emitted at least once, so a source that never reports its own lifecycle — an
 * Apollo flow, a local store mapped straight to data — would otherwise hold the whole group back
 * behind whichever source is slowest. Seeded, the group emits at once with every source loading,
 * and each source's answer shows up as it arrives. A source that already emits `Loading` first
 * reports it twice, which is harmless: folding the same emission twice gives the same state.
 *
 * Failures are not caught here. A source that throws cancels the whole group, so map each source's
 * failures to [Outcome.Error] at that source — which is also where its [Origin] is known.
 *
 * Named `combineOutcomes` rather than overloading `combine`: without a transform, `combine(a, b)`
 * would read as the kotlinx function with its lambda missing.
 */
fun <A, B> combineOutcomes(a: Flow<Outcome<A>>, b: Flow<Outcome<B>>): Flow<Outcomes2<A, B>> =
  combine(a.seeded(), b.seeded()) { first, second -> Outcomes2(first, second) }

/** Combines three sources. See the two-source [combineOutcomes] for how they are seeded. */
fun <A, B, C> combineOutcomes(
  a: Flow<Outcome<A>>,
  b: Flow<Outcome<B>>,
  c: Flow<Outcome<C>>,
): Flow<Outcomes3<A, B, C>> =
  combine(a.seeded(), b.seeded(), c.seeded()) { first, second, third ->
    Outcomes3(first, second, third)
  }

/** Combines four sources. See the two-source [combineOutcomes] for how they are seeded. */
fun <A, B, C, D> combineOutcomes(
  a: Flow<Outcome<A>>,
  b: Flow<Outcome<B>>,
  c: Flow<Outcome<C>>,
  d: Flow<Outcome<D>>,
): Flow<Outcomes4<A, B, C, D>> =
  combine(a.seeded(), b.seeded(), c.seeded(), d.seeded()) { first, second, third, fourth ->
    Outcomes4(first, second, third, fourth)
  }

/** Combines five sources. See the two-source [combineOutcomes] for how they are seeded. */
fun <A, B, C, D, E> combineOutcomes(
  a: Flow<Outcome<A>>,
  b: Flow<Outcome<B>>,
  c: Flow<Outcome<C>>,
  d: Flow<Outcome<D>>,
  e: Flow<Outcome<E>>,
): Flow<Outcomes5<A, B, C, D, E>> =
  combine(a.seeded(), b.seeded(), c.seeded(), d.seeded(), e.seeded()) {
    first,
    second,
    third,
    fourth,
    fifth ->
    Outcomes5(first, second, third, fourth, fifth)
  }

private fun <T> Flow<Outcome<T>>.seeded(): Flow<Outcome<T>> = onStart { emit(Outcome.Loading) }
