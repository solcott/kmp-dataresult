plugins {
  id("kmp-library")
  id("kmp-published")
}

kotlin {
  // Swift export builds and verifies on macosArm64, so anything reachable from an exported API
  // needs this slice. See the note in `kmp-library` for why it isn't in the shared set.
  macosArm64()

  sourceSets {
    commonMain.dependencies {
      api(project(":dataresult"))
      // Apollo, coroutines and dataresult all appear in this module's public signatures:
      // ApolloResponse and Operation.Data as receiver/bound, Outcome and DataError as results.
      api(libs.apollo.api)
      api(libs.kotlinx.coroutines.core)

      // Read-only access to Apollo's per-response cache metadata (isFromCache) for Origin mapping.
      // Nothing from it reaches the public API.
      implementation(libs.apollo.normalized.cache)
    }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.test)
      // The tests compile against apollo-api types whose signatures use okio, which otherwise
      // arrives only transitively.
      implementation(libs.okio)
    }
  }
}
