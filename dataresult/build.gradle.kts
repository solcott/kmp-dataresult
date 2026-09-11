plugins {
  id("kmp-library")
  id("kmp-published")
}

kotlin {
  // Swift export builds and verifies on macosArm64, so anything reachable from an exported API
  // needs this slice. See the note in `kmp-library` for why it isn't in the shared set.
  macosArm64()

  sourceSets {
    // `api`: combineOutcomes takes and returns Flow, so every consumer of this module sees it.
    commonMain.dependencies { api(libs.kotlinx.coroutines.core) }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.turbine)
    }
  }
}
