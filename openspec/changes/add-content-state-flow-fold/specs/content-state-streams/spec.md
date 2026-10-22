# Spec Delta

## Purpose

Lets a host that isn't Compose, such as a ViewModel, a Decompose component or SwiftUI, turn a
stream of `Outcome`s into `ContentState`s with the same stale-while-revalidate rules the Compose
hosts apply, without depending on Compose.

## ADDED Requirements

### Requirement: Stream of states from a stream of outcomes
The library SHALL turn a stream of `Outcome<T>` and an initial `ContentState<T>` into a stream of
`ContentState<T>`. It emits the initial state first, then one state for each outcome, folded with
the same rules as `applyEmission`.

#### Scenario: Initial state comes first
- **WHEN** the outcome stream has not emitted yet
- **THEN** the first emitted state is the initial state, unchanged

#### Scenario: Data replaces the value
- **WHEN** the outcome stream emits `Data(value, origin)`
- **THEN** the next emitted state holds `value` and `origin`, and its status is `Idle`

#### Scenario: Loading and errors keep the last value
- **WHEN** a state holding loaded data receives `Loading`, then `Error(cause)`
- **THEN** the emitted states keep that data, and their statuses are `Loading` and then `Failed(cause)`

### Requirement: Settle on normal completion only
When the outcome stream completes normally while the state is still `Loading`, the library SHALL
emit one more state with status `Idle`. When the collection is cancelled, it SHALL NOT emit a
settled state, because cancellation means the request was abandoned, not answered.

#### Scenario: Source completes without emitting
- **WHEN** the outcome stream completes without emitting, and the initial status is `Loading`
- **THEN** the last emitted state has status `Idle` and the initial data

#### Scenario: Source completes after settling
- **WHEN** the outcome stream emits `Data` and then completes
- **THEN** no extra state is emitted after the one holding the data

#### Scenario: Collection cancelled while loading
- **WHEN** the collector is cancelled while the state is `Loading`
- **THEN** no state with status `Idle` is emitted for that request

### Requirement: Restart on a new parameter
Given a stream of parameters and a function from a parameter to an outcome stream, the library SHALL
emit the initial state, then do the following for each distinct parameter value: cancel the stream
for the previous value, emit the current state marked `Loading` with its data kept, then fold the
new value's outcome stream.

#### Scenario: A new parameter keeps the content under a reload
- **WHEN** a state holding loaded data receives a new parameter value
- **THEN** the next emitted state keeps the data and has status `Loading`, and the previous
  parameter's stream is cancelled

#### Scenario: A repeated parameter starts no request
- **WHEN** the parameter stream emits the same value twice in a row
- **THEN** only one outcome stream is started for it, and no extra reloading state is emitted

#### Scenario: A cancelled request does not settle
- **WHEN** a parameter's outcome stream is cancelled by a newer parameter
- **THEN** no settled state is emitted for the cancelled stream

### Requirement: Fold into a caller-held state flow
The library SHALL fold an outcome stream, or a parameter-driven one, into a mutable state flow the
caller holds. It starts from that flow's current value and writes every folded state back to it,
following the same rules as the stream forms.

#### Scenario: Restarting keeps the last value
- **WHEN** a state flow already holds loaded data, and a new collection into it starts and receives `Loading`
- **THEN** the state flow keeps that data, with status `Loading`

#### Scenario: Writes made outside the fold are not discarded
- **WHEN** the caller writes a new value to the state flow while a collection into it is running
- **THEN** the next outcome is folded into the caller's value, not into a stale copy

### Requirement: Groups of sources
For a group of sources (two to five), the library SHALL provide all three forms: a stream of states,
a parameter-driven stream, and a fold into a caller-held state flow. Each source's outcome is folded
into that source's own state, with the rules above applied to every source.

#### Scenario: One source fails, the others keep their values
- **WHEN** a group's second source emits `Error(cause)` while the first holds loaded data
- **THEN** the emitted group keeps the first source's data and status, and the second source's
  status is `Failed(cause)`

#### Scenario: A new parameter reloads every source
- **WHEN** a parameter-driven group receives a new parameter value
- **THEN** every source in the next emitted group has status `Loading` and keeps its data

### Requirement: Available without Compose
This capability SHALL be provided by `uistate`, without a dependency on the Compose runtime or any
Compose type. It SHALL only use types that `uistate` is already allowed to expose to Swift export.

#### Scenario: Consumer depends only on uistate
- **WHEN** a module depends only on `uistate`
- **THEN** it can use every form of this capability, and no Compose runtime artifact is on its classpath

### Requirement: Same rules as the Compose hosts
For the same outcome stream and initial state, the stream forms here SHALL produce the same sequence
of distinct states as `uistate-compose`'s collectors.

#### Scenario: Identical inputs, identical states
- **WHEN** the same outcome stream is folded by this capability and by the Compose collector, from the same initial state
- **THEN** both reach the same sequence of distinct states
