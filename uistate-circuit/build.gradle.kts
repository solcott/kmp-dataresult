plugins {
  id("kmp-library")
  id("kmp-published")
  // Applied by id with no version: the root buildscript classpath decides which compose compiler
  // plugin this gets, because AGP 9 would otherwise supply an older pinned one.
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // Countries' :presenter builds for macosArm64 and :apple depends on it, so this needs the slice
  // too. circuit-retained publishes it. See the note in `kmp-library`.
  macosArm64()

  sourceSets {
    commonMain.dependencies {
      api(project(":uistate"))
      // `api` throughout: ContentState is the return type, Flow<Outcome<T>> the parameter, and
      // produceRetainedState's ProduceStateScope leaks through the inline machinery.
      api(libs.circuit.retained)
      api(libs.kotlinx.coroutines.core)
    }

    // `nonWebTest`, not `commonTest`: `presenterTestOf` runs on Molecule, whose frame clock lives
    // in a browser-only source set. Under Node recomposition never advances, so a web test would
    // hang to the timeout rather than fail. Nothing here is platform-specific, so jvm + Android
    // host + the two Apple targets is honest coverage.
    named("nonWebTest").dependencies {
      implementation(kotlin("test"))
      implementation(libs.circuit.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.turbine)
    }
  }
}
