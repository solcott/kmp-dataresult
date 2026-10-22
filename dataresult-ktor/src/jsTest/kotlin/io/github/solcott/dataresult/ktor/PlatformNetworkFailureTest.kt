package io.github.solcott.dataresult.ktor

import io.github.solcott.dataresult.DataError
import io.github.solcott.dataresult.Origin
import io.github.solcott.dataresult.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PlatformNetworkFailureTest {

  // What the Js engine throws for a host that doesn't resolve or a refused connection.
  private fun failedFetch() = Error("Fail to fetch", Throwable("getaddrinfo ENOTFOUND"))

  @Test
  fun failedFetchIsNetwork() {
    assertEquals(DataError.Network, failedFetch().toDataError())
  }

  // It is a kotlin.Error, not an Exception, so this pins that httpOutcome still catches it.
  @Test
  fun failedFetchIsANetworkOutcome() = runTest {
    assertEquals(
      Outcome.Error(DataError.Network, Origin.Network),
      httpOutcome<String> { throw failedFetch() },
    )
  }

  @Test
  fun otherErrorsAreNotNetwork() {
    assertEquals(DataError.Unknown::class, Error("something else").toDataError()::class)
  }
}
