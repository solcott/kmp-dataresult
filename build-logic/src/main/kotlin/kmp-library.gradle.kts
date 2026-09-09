import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The shared KMP setup for every published module. AGP 9 dropped KMP support from
// `com.android.library`, so the Android target comes from `com.android.kotlin.multiplatform.library`
// and is configured through an `android { }` block nested inside `kotlin { }`.
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("dev.detekt")
  id("com.squareup.sort-dependencies")
  id("project-config")
}

val libs: VersionCatalog = the<VersionCatalogsExtension>().named("libs")

// Captured here rather than inline: inside `kotlin { android { } }`, `name` resolves to the
// Android target, not the project.
val moduleNamespace = "io.github.solcott.${project.name.replace('-', '.')}"
val jvmBytecodeTarget = libs.findVersion("jvm-compat").get().requiredVersion

kotlin {
  // Applied implicitly by KGP, but stated here because it is what creates the intermediate
  // source sets (`appleMain`, `webMain`, `nativeMain`) modules may want to use.
  applyDefaultHierarchyTemplate()

  jvmToolchain(libs.findVersion("jvm-toolchain").get().requiredVersion.toInt())

  android {
    namespace = moduleNamespace
    compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
    minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
    compilerOptions {
      jvmTarget = JvmTarget.fromTarget(jvmBytecodeTarget)
      freeCompilerArgs.add("-Xjdk-release=$jvmBytecodeTarget")
    }
    // The KMP Android plugin disables tests by default; opt back in so `androidHostTest` exists.
    withHostTestBuilder {}.configure {}
  }

  // The toolchain compiles on 25 but the published bytecode targets 17, so every JVM-flavored
  // target pins jvmTarget explicitly -- a toolchain silently raises it otherwise. `-Xjdk-release`
  // additionally hides post-17 JDK APIs. It is a JVM-only flag, so it stays out of the
  // project-wide compilerOptions, which also feed Native, JS and WasmJs.
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.fromTarget(jvmBytecodeTarget)
      freeCompilerArgs.add("-Xjdk-release=$jvmBytecodeTarget")
    }
  }

  // No iosX64: Intel Macs cannot run the iOS simulator build, and neither consuming app declares
  // it.
  //
  // macosArm64 is deliberately NOT here. Countries' :apple module needs it, so the modules it
  // consumes declare it themselves -- but Store5 publishes no macosArm64 artifact, so
  // :dataresult-store5 cannot have one. Declaring it per module keeps that constraint visible at
  // the module that has it rather than buried in an opt-out here.
  iosArm64()
  iosSimulatorArm64()

  // Both browser() and nodejs(): the browser targets are what consumers ship, and nodejs lets this
  // library's own tests run without launching a browser. A Kotlin/JS klib is not split by
  // execution environment, so a browser-only consumer is unaffected by the extra test runner.
  js {
    browser()
    nodejs()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    nodejs()
  }

  sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}

// No module has .java sources, but the Kotlin/Java target consistency check (an error by default
// on Gradle 8+) compares these task attributes against jvmTarget regardless of whether anything
// compiles through them. Left unset they would inherit the toolchain's 25 and fail the build.
tasks.withType<JavaCompile>().configureEach {
  sourceCompatibility = jvmBytecodeTarget
  targetCompatibility = jvmBytecodeTarget
}

detekt {
  config.setFrom(files("$rootDir/detekt/detekt.yml"))
  buildUponDefaultConfig = true
  allRules = false
}

tasks.withType<dev.detekt.gradle.Detekt> {
  reports { html.required = true }
  exclude("**/build/**")
  exclude("**/generated/**")
}
