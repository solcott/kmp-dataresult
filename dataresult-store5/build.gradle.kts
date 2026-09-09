plugins {
  id("kmp-library")
  id("kmp-published")
}

// No macosArm64: Store5 publishes no macosArm64 artifact (checked against 5.1.0-beta01), so this
// module cannot have the slice the others do. Revisit if Store5 starts publishing one.
kotlin {
  sourceSets {
    // All three appear in this module's public signatures, so all three are `api`.
    commonMain.dependencies {
      api(project(":dataresult"))
      api(libs.kotlinx.coroutines.core)
      api(libs.store)
    }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.turbine)
    }
  }
}
