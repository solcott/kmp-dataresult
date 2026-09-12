package io.github.solcott.dataresult

/** Where the data or failure in an [Outcome] came from. `Outcome.Loading` carries none. */
enum class Origin {
  Cache,
  Network,
}
