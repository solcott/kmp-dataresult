# Proposal

## Why

Every way to turn `Flow<Outcome<T>>` into `ContentState<T>` today goes through a Compose
`MutableState`: `uistate-compose`'s `collectFrom` and `collectLatestFrom`, and the Circuit producers
built on them. Screens that hold state in a ViewModel `StateFlow` or a Decompose component, and
SwiftUI code that observes exported Kotlin state, have no entry point. They have to call
`applyEmission` by hand, and so re-implement the completion and cancellation rules the Compose
fold already gets right. Consumers use ViewModel + StateFlow on some screens and Decompose on
others, so this gap is real today.

## What Changes

- Add a Flow-level fold to `uistate`. It takes a stream of `Outcome`s and returns a stream of
  `ContentState`s, applying the same rules as the Compose fold: data replaced only by `Data`, status
  settled on every emission, settled on normal completion and never on cancellation.
- Add a variant driven by parameters. For each distinct parameter value it cancels the in-flight
  request and marks the state reloading while keeping the current data.
- Add collectors that fold into a `MutableStateFlow` the caller holds, starting from its current
  value, for ViewModel and Decompose hosts. Restarting the collection never resets the screen to
  its placeholder.
- Add the same three forms for groups of sources (`ContentStates2`…`5`).
- Document ViewModel + StateFlow, Decompose and SwiftUI usage in the README. No host-specific modules
  are added.
- No breaking changes. `uistate-compose` and `uistate-circuit` keep their current API and behavior.

## Capabilities

### New Capabilities
- `content-state-streams`: folding streams of `Outcome`s into `ContentState` outside Compose, either
  as a stream of states or into a caller-held state flow, for one source or a group of sources.

### Modified Capabilities

None. There are no existing specs.

## Impact

- **Code:** new public functions in `uistate` (`commonMain`), plus their tests in `uistate/src/commonTest`.
- **API:** additions to `uistate/api/` (ABI dumps). This API is exported to Swift wholesale, so it
  must stay Swift-export safe: it adds only coroutines types, which `dataresult` already exposes.
- **Dependencies:** no new libraries. `uistate` reaches `kotlinx-coroutines-core` today only
  transitively, through `dataresult`. Once `Flow` appears in `uistate`'s own signatures, it must
  declare coroutines as a direct `api` dependency, or `buildHealth` fails. Its tests add
  `kotlinx-coroutines-test` and Turbine.
- **Docs:** README ("Without Compose" section), CHANGELOG `## Unreleased`, and the
  `compose-integrations` rule. After this change the completion rule is implemented in two places.
