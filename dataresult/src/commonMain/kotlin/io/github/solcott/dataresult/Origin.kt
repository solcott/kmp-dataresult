package io.github.solcott.dataresult

/** Where a piece of data came from, or where an in-flight load is being served from. */
enum class Origin {
  Cache,
  Network,
}
