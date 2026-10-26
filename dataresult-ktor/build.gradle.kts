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
      // HttpResponse is a receiver and a hook parameter, and Flow is httpOutcomeFlow's result, so
      // both are in this module's public signatures.
      api(libs.kotlinx.coroutines.core)
      api(libs.ktor.client.core)

      // Only for the SerializationException type check; nothing from it reaches the public API.
      implementation(libs.kotlinx.serialization.core)
      // HttpStatusCode.isSuccess(), on the status of a response. ktor-client-core brings it anyway;
      // declared because it is used directly, and nothing from it reaches the public API.
      implementation(libs.ktor.http)
      // Only for the ContentConvertException type check. A consumer's JSON converter already
      // depends on it, and nothing from it reaches the public API.
      implementation(libs.ktor.serialization)
    }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
      // HttpStatusCode for MockEngine responses, and ktor-utils for the types MockEngine's handler
      // signature names. Both arrive transitively but are used directly.
      implementation(libs.ktor.http)
      implementation(libs.ktor.utils)
      implementation(libs.turbine)
    }
  }
}
