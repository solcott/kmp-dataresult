package io.github.solcott.dataresult.ktor

// No network failure here is known to escape kotlinx.io.IOException.
internal actual fun Throwable.isPlatformNetworkFailure(): Boolean = false
