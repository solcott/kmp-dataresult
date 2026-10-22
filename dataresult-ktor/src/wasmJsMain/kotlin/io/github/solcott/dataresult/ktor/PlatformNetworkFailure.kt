package io.github.solcott.dataresult.ktor

// Nothing observed to map. Ktor 3.0.0's Js engine failed to load under wasmJs on Node, so what it
// throws for an offline request is unknown here; it falls to Unknown with the cause kept.
internal actual fun Throwable.isPlatformNetworkFailure(): Boolean = false
