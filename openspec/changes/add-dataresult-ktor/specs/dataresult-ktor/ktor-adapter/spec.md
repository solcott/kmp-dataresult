# Spec Delta

## Purpose

Maps Ktor HTTP calls and responses onto `Outcome` and `DataError`, so a data layer built on Ktor
reports failures in the library's transport-agnostic terms, without exception handling at each
call site.

## ADDED Requirements

### Requirement: Successful call becomes Data
The adapter SHALL turn a call that returns a value, or a 2xx response whose success mapping returns
a value, into `Outcome.Data(value, Origin.Network)`.

#### Scenario: 2xx response mapped to data
- **WHEN** the response status is 200 and the success mapping returns `value`
- **THEN** the outcome is `Data(value, Network)`

#### Scenario: Call returns a value
- **WHEN** the wrapped call returns `value` without throwing
- **THEN** the outcome is `Data(value, Network)`

### Requirement: Non-success status becomes an HTTP error
The response form SHALL check the status itself. A status outside 2xx SHALL become
`Outcome.Error(DataError.Http(status), Network)`, whatever the client's `expectSuccess` setting, and
the success mapping SHALL NOT run.

#### Scenario: 404 without expectSuccess
- **WHEN** a client with `expectSuccess = false` receives a 404, and no error-body hook is given
- **THEN** the outcome is `Error(Http(404), Network)`, and the success mapping is not invoked

#### Scenario: Thrown response exception
- **WHEN** the wrapped call throws a Ktor response exception carrying a 503 response
- **THEN** the outcome is `Error(Http(503), Network)`

### Requirement: Error bodies decoded by a caller hook
For a non-success response, the adapter SHALL call the caller's error-body hook. A `DataError` it
returns SHALL become the outcome's cause. When the hook returns nothing, or throws anything other
than cancellation, the cause SHALL be `DataError.Http(status)`.

#### Scenario: XRPC envelope decoded
- **WHEN** a 400 response carries `{"error":"InvalidRequest","message":"bad cursor"}`, and the hook maps it to `Api(["bad cursor"], "InvalidRequest")`
- **THEN** the outcome is `Error(Api(["bad cursor"], "InvalidRequest"), Network)`

#### Scenario: Hook fails
- **WHEN** the hook throws while decoding a 500 response's body
- **THEN** the outcome is `Error(Http(500), Network)`

### Requirement: Exceptions classified into DataError
The adapter SHALL classify failures that aren't HTTP. Connection, DNS, timeout and other I/O
failures become `DataError.Network`. Body decoding or conversion failures become
`DataError.Serialization`. Anything else becomes `DataError.Unknown` with the throwable kept.

#### Scenario: Request timeout
- **WHEN** the call fails because a request, connect or socket timeout elapsed
- **THEN** the outcome is `Error(Network, Network)`

#### Scenario: Body cannot be decoded
- **WHEN** reading a 2xx body as the expected type fails because no converter applies or the content is malformed
- **THEN** the outcome is `Error(Serialization, Network)`

#### Scenario: Unrecognized exception
- **WHEN** the call throws an exception of a kind the adapter does not recognize
- **THEN** the outcome is `Error(Unknown(cause = thatException), Network)`

### Requirement: Classified exceptions are reported
The adapter SHALL pass every exception that becomes an `Outcome.Error` to an optional
caller-supplied callback, before returning the outcome. A non-success response that threw nothing
SHALL NOT invoke it.

#### Scenario: Network failure reported
- **WHEN** a call fails with an I/O exception, and a callback is given
- **THEN** the callback receives that exception, and the outcome is `Error(Network, Network)`

### Requirement: Cancellation is never an error
The adapter SHALL rethrow cancellation rather than turning it into an outcome, and SHALL NOT pass it
to the exception callback.

#### Scenario: Caller cancelled mid-request
- **WHEN** the coroutine running the call is cancelled while the request is in flight
- **THEN** cancellation propagates to the caller, and no outcome is produced

### Requirement: Flow form reports its own lifecycle
The flow form SHALL emit `Outcome.Loading` when collected, then exactly one outcome for the call,
and then complete. Each collection SHALL make a new request.

#### Scenario: One request per collection
- **WHEN** the flow is collected twice
- **THEN** each collection emits `Loading` and then one outcome, and the call runs twice
