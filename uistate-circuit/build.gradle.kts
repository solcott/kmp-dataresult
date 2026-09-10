plugins {
  id("kmp-library")
  id("kmp-published")
  // Applied by id with no version: the root buildscript classpath decides which compose compiler
  // plugin this gets, because AGP 9 would otherwise supply an older pinned one.
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // Matches the other modules' target set -- circuit-retained publishes macosArm64 too. See the
  // note in `kmp-library` for why it isn't in the shared set.
  macosArm64()

  sourceSets {
    commonMain.dependencies {
      // `api` throughout: ContentState (uistate) is the return type, Flow<Outcome<T>> (dataresult)
      // the parameter, @Composable adds the Composer (composeRuntime), and produceRetainedState's
      // ProduceStateScope leaks through the inline machinery. dataresult, uistate and
      // composeRuntime also come through uistate-compose, but dependency analysis requires a
      // direct use to be declared directly.
      api(project(":dataresult"))
      api(project(":uistate"))
      // `uistate-compose` as well as `uistate`: it holds the fold both variants share.
      api(project(":uistate-compose"))
      api(libs.circuit.retained)
      api(libs.composeRuntime)
      api(libs.kotlinx.coroutines.core)
    }

    // `nonWebTest`, not `commonTest`: `presenterTestOf` runs on Molecule, whose frame clock lives
    // in a browser-only source set. Under Node recomposition never advances, so a web test would
    // hang to the timeout rather than fail. Nothing here is platform-specific, so jvm + Android
    // host + the two Apple targets is honest coverage.
    named("nonWebTest").dependencies {
      implementation(kotlin("test"))
      // The presenter under test returns a CircuitUiState, so the tests use circuit-runtime
      // directly. circuit-test brings it in only transitively.
      implementation(libs.circuit.runtime)
      implementation(libs.circuit.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.turbine)
    }
  }
}
