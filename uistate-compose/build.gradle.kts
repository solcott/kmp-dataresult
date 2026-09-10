plugins {
  id("kmp-library")
  id("kmp-published")
  // Applied by id with no version: the root buildscript classpath decides which compose compiler
  // plugin this gets, because AGP 9 would otherwise supply an older pinned one.
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // Matches the other modules' target set -- the androidx Compose runtime publishes macosArm64 too.
  // See the note in `kmp-library` for why it isn't in the shared set.
  macosArm64()

  sourceSets {
    // `api` throughout: ContentState and MutableState are in the signatures, Flow<Outcome<T>> is
    // the parameter, and `retain` is what callers are relying on for retention.
    commonMain.dependencies {
      api(project(":uistate"))
      api(libs.composeRuntime)
      api(libs.composeRuntimeRetain)
      api(libs.kotlinx.coroutines.core)
    }

    // The fold itself needs no composition, so its tests run everywhere, web included.
    commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }

    // The composables need a Compose frame clock, which Molecule only has off the web: under Node,
    // recomposition never advances and a test awaiting a second emission hangs to the timeout.
    named("nonWebTest").dependencies {
      implementation(libs.molecule.runtime)
      implementation(libs.turbine)
    }
  }
}
