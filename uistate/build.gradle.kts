plugins {
  id("kmp-library")
  id("kmp-published")
}

kotlin {
  // Swift export builds and verifies on macosArm64, so anything reachable from an exported API
  // needs this slice. See the note in `kmp-library` for why it isn't in the shared set.
  macosArm64()

  sourceSets {
    // `api`, not `implementation`: ContentState.origin and LoadStatus.Failed.error are Origin and
    // DataError, so every consumer of this module sees them.
    commonMain.dependencies {
      api(project(":dataresult"))
      // Declared here even though dataresult re-exports it, because ContentState and LoadStatus
      // carry `@Immutable` themselves. `api` for the same reason as in dataresult.
      api(libs.composeRuntimeAnnotations)
      api(libs.kotlinx.immutable.collections)
    }
  }
}
