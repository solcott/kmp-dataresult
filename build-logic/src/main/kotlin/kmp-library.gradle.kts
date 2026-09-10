import com.android.build.api.withAndroid
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// The shared KMP setup for every published module. AGP 9 dropped KMP support from
// `com.android.library`, so the Android target comes from
// `com.android.kotlin.multiplatform.library` and is configured through an `android { }` block
// nested inside `kotlin { }`.
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("com.autonomousapps.dependency-analysis")
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
  //
  // `nonWeb` is the one addition to the default set. A module whose tests need a Compose frame
  // clock cannot test on js/wasmJs -- Molecule's clock lives in its `browserMain` source set, so
  // under Node recomposition never advances and a test awaiting a second emission hangs to the
  // timeout rather than failing. Such a module puts its tests in `nonWebTest`.
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("nonWeb") {
        withJvm()
        @Suppress("UnstableApiUsage") withAndroid()
        withNative()
      }
    }
  }

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
    // `isReturnDefaultValues` because Compose and Circuit touch android.util.Log, which is an
    // unmocked stub on the host runner -- without it every such call throws.
    withHostTestBuilder {}.configure { isReturnDefaultValues = true }
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

  // No iosX64: Intel Macs cannot run the iOS simulator build, so nothing here targets it.
  //
  // macosArm64 is deliberately NOT here. Every module but :dataresult-store5 declares it itself,
  // because Store5 publishes no macosArm64 artifact and that module therefore cannot have one.
  // Declaring it per module keeps the constraint visible at the module it constrains, rather than
  // buried in an opt-out here.
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

  // Every module here is published, so its public API is a contract. The reference dumps under
  // each module's api/ make an API change show up as a diff in review, and `check` fails until
  // the dump is updated with `./gradlew updateKotlinAbi`. Since Kotlin 2.4 the call itself is what
  // enables validation: `enabled = true` is a deprecation error, and klib targets (Apple, JS, Wasm)
  // are covered without the removed `klib { enabled }`.
  @OptIn(ExperimentalAbiValidation::class) abiValidation()

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
