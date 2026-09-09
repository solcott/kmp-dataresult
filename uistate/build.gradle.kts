plugins {
  id("kmp-library")
  id("kmp-published")
}

kotlin {
  // Countries' :apple module runs its Swift-export verification on macosArm64, so everything it
  // consumes needs this slice. See the note in `kmp-library` for why it isn't in the shared set.
  macosArm64()

  sourceSets {
    // `api`, not `implementation`: ContentState.origin and LoadStatus.Failed.error are Origin and
    // DataError, so every consumer of this module sees them.
    commonMain.dependencies { api(project(":dataresult")) }
  }
}
