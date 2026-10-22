package io.github.solcott.dataresult.ktor

import io.github.solcott.dataresult.DataError
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformNetworkFailureTest {

  // An IllegalArgumentException, so it reaches DataError.Network only through the platform hook.
  @Test
  fun unresolvedAddressIsNetwork() {
    assertEquals(DataError.Network, UnresolvedAddressException().toDataError())
  }
}
