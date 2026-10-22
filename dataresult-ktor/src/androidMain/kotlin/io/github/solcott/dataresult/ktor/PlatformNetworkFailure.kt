package io.github.solcott.dataresult.ktor

import java.nio.channels.UnresolvedAddressException

// CIO throws this for a host that doesn't resolve. It is an IllegalArgumentException, not an
// IOException, so the common classification would otherwise call a DNS failure Unknown.
internal actual fun Throwable.isPlatformNetworkFailure(): Boolean =
  this is UnresolvedAddressException
