package io.github.solcott.dataresult

/**
 * Transport-agnostic failure vocabulary.
 *
 * Errors are classified by what they mean to a consumer, never by which client produced them. Each
 * data source (Apollo, Ktor, Store, …) maps its own errors into these cases at its own boundary, so
 * nothing outside that boundary depends on a specific networking library.
 */
sealed class DataError {
  /** No usable response: offline, DNS failure, dropped connection, or timeout. */
  data object Network : DataError()

  /** An HTTP response arrived with a non-success status code. */
  data class Http(val code: Int) : DataError()

  /**
   * The transport succeeded but the backend reported logical errors in the payload — GraphQL
   * `errors`, a REST error envelope, and so on.
   */
  data class Api(val messages: List<String>, val code: String? = null) : DataError()

  /** A response body arrived but could not be decoded, or did not match the expected schema. */
  data object Serialization : DataError()

  /** Anything not otherwise classified. [cause] and [message] are retained for logging. */
  data class Unknown(val cause: Throwable? = null, val message: String? = null) : DataError()
}
