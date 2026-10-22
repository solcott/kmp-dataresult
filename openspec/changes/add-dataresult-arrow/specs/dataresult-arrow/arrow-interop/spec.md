# Spec Delta

## Purpose

Converts between Arrow's `Either` and `Raise` and the library's `Outcome` and `DataError`, so code
written with Arrow can feed `ContentState` and read outcomes without hand-written conversions.

## ADDED Requirements

### Requirement: Either of DataError to Outcome
The library SHALL convert `Either<DataError, T>` to `Outcome<T>`: `Right(value)` to
`Data(value, origin)` and `Left(error)` to `Error(error, origin)`. `origin` is supplied by the
caller and defaults to `Origin.Network`.

#### Scenario: Right becomes Data
- **WHEN** `Right(value)` is converted with the default origin
- **THEN** the outcome is `Data(value, Network)`

#### Scenario: Left becomes Error with the given origin
- **WHEN** `Left(DataError.Network)` is converted with origin `Cache`
- **THEN** the outcome is `Error(Network, Cache)`

### Requirement: Either of any error to Outcome
The library SHALL convert `Either<E, T>` to `Outcome<T>`, given a mapping from `E` to `DataError`.
The mapping SHALL run only for `Left`, and its result SHALL be the outcome's cause.

#### Scenario: Domain error mapped
- **WHEN** `Left(NotFound)` is converted with a mapping of `NotFound` to `Http(404)`
- **THEN** the outcome is `Error(Http(404), Network)`

#### Scenario: Mapping not called for Right
- **WHEN** `Right(value)` is converted
- **THEN** the mapping is not invoked, and the outcome is `Data(value, Network)`

### Requirement: Outcome to Either
The library SHALL convert `Outcome<T>` to `Either<DataError, T>`: `Data` to `Right(data)`, `Error` to
`Left(cause)`, and `Loading` to no value (null), because no `Either` represents a pending result.

#### Scenario: Loading has no Either
- **WHEN** `Outcome.Loading` is converted
- **THEN** the result is null

#### Scenario: Round trip keeps the value
- **WHEN** `Right(value)` is converted to an outcome and back
- **THEN** the result is `Right(value)`

### Requirement: Outcome builder over Raise
The library SHALL provide a builder that runs a block able to raise a `DataError`. A block that
returns `value` SHALL give `Data(value, origin)`. A raised error SHALL give `Error(error, origin)`. A
thrown exception SHALL propagate, unchanged and uncaught.

#### Scenario: Raised error
- **WHEN** the block raises `DataError.Serialization`
- **THEN** the outcome is `Error(Serialization, Network)`, and no code after the raise runs

#### Scenario: Thrown exception propagates
- **WHEN** the block throws an `IllegalStateException`
- **THEN** the builder throws that exception, and produces no outcome

#### Scenario: Suspending block
- **WHEN** the block calls a suspending function before returning `value`
- **THEN** the outcome is `Data(value, Network)`
