package io.github.solcott.dataresult.ktor

// The Js engine fails every rejected fetch -- DNS failure and refused connection alike, as observed
// on Ktor 3.0.0 under Node -- with this exact kotlin.Error, the JS error as its cause. fetch()
// rejects only when no response arrived, so this is a network failure. It is not an Exception, so
// it has no type to match, and the message is Ktor's own constant.
internal actual fun Throwable.isPlatformNetworkFailure(): Boolean =
  this is Error && message == "Fail to fetch"
