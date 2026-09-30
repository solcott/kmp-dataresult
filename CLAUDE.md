# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

A Kotlin Multiplatform library: `Outcome` (what a data source emits) and `ContentState` (what a
screen renders), plus adapters for Apollo, Store5, Compose and Circuit.

Rules that apply only to some files live in `.claude/rules/`. Each one loads when you read a
matching file: Swift export and public API (`*/src/commonMain`), the Compose/Circuit invariants,
adapters, tests, Gradle build files, and CI/release. Put a new rule there when it concerns specific
paths, and in this file only if every session needs it.

## Commands

Run Gradle through the **`dataresult-gradle-runner`** agent, or `/precommit` before committing,
rather than inline Bash. Every task fans out across 7 targets, and the full log is noise that should
never reach this context.

```bash
./gradlew ktfmtFormat sortDependencies   # fix formatting + dependency order; do this before committing
./gradlew build                          # every target, tests, and `check` = ktfmtCheck + checkSortDependencies + detekt + checkKotlinAbi (+ checkSwiftExport)
./gradlew updateKotlinAbi                # after an *intended* public API change; commit the <module>/api/ diff with it
./gradlew buildHealth                    # dependency analysis: fails on any finding
./gradlew :uistate:jvmTest --tests 'io.github.solcott.uistate.ContentStateTest'   # one test class, fastest runner
./gradlew :uistate:allTests              # one module, every target
./gradlew :uistate:testAndroidHostTest   # Android host tests
./gradlew publishToMavenLocal            # try a change in a consumer (-SNAPSHOT version + mavenLocal())
```

These root tasks cover the included `build-logic/` build too. CI runs the same checks on macOS.

## Architecture

```
source ─► adapter ─► Flow<Outcome<T>> ─► ContentState.applyEmission ─► ContentState<T> ─► UI
          (asOutcomes / mapToOutcome)     (fold, one emission at a time)
```

Modules: `dataresult` ← `uistate` ← `uistate-compose` ← `uistate-circuit`. The adapters
`dataresult-apollo` and `dataresult-store5` depend only on `dataresult`. Each module is one or two files
under `src/commonMain`.

- **`ContentState` semantics:** `data` is replaced only by `Outcome.Data`. `Loading` and `Error` move
  `status` and keep the last value (stale-while-revalidate). `hasLoaded` is `origin != null`, which is
  deliberate: an empty list is a real answer. `status` settles on every emission, so a source never has to
  complete.
- **A cache miss is not `Data`.** Adapters hold back an empty cached read while a fetch is pending.
  `ContentState.hasAnswer(isEmpty)` is the consumer-side backstop. It is only ever false while
  `Loading` or `Failed`, so it can't hang a spinner.

## Conventions

- Comments and KDoc explain *why* and record traps.
- Commits: an imperative subject line, then a body wrapped at ~72 characters giving the reasoning and any trap
  found along the way.
- User-visible changes go under `## Unreleased` in `CHANGELOG.md`.
- Adding a module: use `/new-module`.

## Keeping context small

Hooks in `.claude/settings.json` enforce the two rules below that matter most. In the main session,
they refuse inline `gradle`/`gradlew` and reads of generated output. Subagents are exempt.

- The codebase is ~2k lines, so read the specific file directly instead of spawning an Explore agent.
- Don't read `detekt/detekt.yml` (929 lines, almost all defaults), `build/`, `.gradle/`, `.kotlin/` or
  `kotlin-js-store/`.
