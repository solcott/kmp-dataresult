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
    commonMain.dependencies {
      // `api`: the classes here are `@Immutable`, and a consumer's Compose compiler has to resolve
      // that annotation to see it. A class it can't resolve is dropped silently, and the types go
      // back to unstable. Annotations only, no Compose runtime or Compose types.
      api(libs.composeRuntimeAnnotations)
      // `api`: combineOutcomes takes and returns Flow, so every consumer of this module sees it.
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.immutable.collections)
    }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.turbine)
    }
  }
}
