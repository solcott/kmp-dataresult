import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

// AGP 9.x has built-in Kotlin support pinned to an older Kotlin Gradle Plugin (KGP). The
// Kotlin-family plugins are applied in each module via `id(...)` with no version, so the KGP the
// build actually uses is the one on this classpath. Without this override the modules compile
// against AGP's older bundled KGP rather than the version in the catalog.
buildscript {
    dependencies {
        classpath(libs.kotlin.compose.compiler.plugin)
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    // For the root `check` and `build` that build-logic's checks hang off (see the end of this file).
    base
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.publish) apply false
    alias(libs.plugins.dependency.sorter)
    // Applied here rather than through the `com.autonomousapps.build-health` settings plugin. DAGP
    // must share a classloader with AGP and KGP, and this project's classloader already holds both:
    // AGP from this block, KGP from the buildscript override above. The settings plugin would need
    // them on the settings classpath instead, which undoes that override. Each module gets the
    // per-project half of DAGP from `kmp-library`.
    alias(libs.plugins.dependency.analysis)
}

// `buildHealth` fails on any finding instead of just reporting it, so the api/implementation
// choices in the module build files stay true. A finding kept on purpose gets an exclusion here
// that says why, the same way every api/implementation line carries a comment.
dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
            onUsedTransitiveDependencies {
                // KGP picks the JUnit flavor of kotlin-test for the jvm and Android host tests from
                // commonTest's `kotlin("test")`. Declaring it again would duplicate that choice.
                exclude("org.jetbrains.kotlin:kotlin-test-junit")
                // Compose's JVM artifacts. The `runtime` / `runtime-retain` coordinates declared in
                // commonMain redirect to these through Gradle module metadata, and DAGP doesn't follow
                // the redirect on the jvm target, so it asks for them to be declared a second time.
                exclude(
                    "androidx.compose.runtime:runtime-desktop",
                    "androidx.compose.runtime:runtime-retain-desktop",
                )
            }
        }

        // Two used-transitive false positives on project dependencies, excluded per module below:
        // - DAGP doesn't count a commonMain project dependency as declared for the jvm target, so it
        //   asks for it to be declared again (in jvmMain before 3.20.0, in commonMain since). Android
        //   reads the same declaration correctly. Each module declares the other projects excluded
        //   here directly in commonMain, so for them the finding can never be a real one.
        // - Since 3.20.0, every module's commonTest is asked to declare the module itself. A module
        //   can't depend on itself, so its own path is excluded too.
        project(":dataresult") { onUsedTransitiveDependencies { exclude(":dataresult") } }
        project(":dataresult-apollo") {
            onUsedTransitiveDependencies { exclude(":dataresult", ":dataresult-apollo") }
        }
        project(":dataresult-store5") {
            onUsedTransitiveDependencies { exclude(":dataresult", ":dataresult-store5") }
        }
        project(":dataresult-ktor") {
            onUsedTransitiveDependencies { exclude(":dataresult", ":dataresult-ktor") }
        }
        project(":uistate") { onUsedTransitiveDependencies { exclude(":dataresult", ":uistate") } }
        project(":uistate-compose") {
            onUsedTransitiveDependencies { exclude(":uistate", ":dataresult", ":uistate-compose") }
            // DAGP 3.20.0 wants :dataresult as `implementation`, but Outcome is in this module's
            // signatures (the Flow<Outcome<T>> parameters). The JVM signatures erase that generic,
            // which is presumably what DAGP reads; the klib ABI dump shows it. `api` is right.
            onIncorrectConfiguration { exclude(":dataresult") }
        }
        project(":uistate-circuit") {
            // :dataresult since Paging, a compiled class rather than inline Compose code, names
            // Outcome in its own members.
            onUsedTransitiveDependencies {
                exclude(":uistate", ":dataresult", ":uistate-compose", ":uistate-circuit")
            }
            // DAGP 3.20.0 wants :uistate-compose as `implementation`. It is right that nothing from
            // uistate-compose is in this module's ABI, but the artifact is documented (README) as
            // bringing uistate-compose with it, so a Circuit app gets the plain composables too.
            onIncorrectConfiguration { exclude(":uistate-compose") }
        }
    }
}

// Pins the Gradle daemon's JVM. `./gradlew updateDaemonJvm` writes the criteria to
// gradle/gradle-daemon-jvm.properties; the foojay resolver auto-downloads a matching JDK.
tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(libs.versions.jvm.toolchain.get())
    vendor.set(JvmVendorSpec.AMAZON)
}

// build-logic is an included build with its own ktfmt, sort-dependencies and detekt setup. A task
// selector such as `./gradlew ktfmtCheck` only reaches this build's projects, so until this was
// wired up nothing ran those checks and build-logic drifted. Hooking the root tasks, rather than
// adding `./gradlew -p build-logic ...` steps to CI, makes the commands everyone already runs
// cover it: `ktfmtFormat` / `sortDependencies` fix it, `ktfmtCheck` / `checkSortDependencies`
// check it, and `build` runs its `check` (detekt, ktfmt, sorting, validatePlugins). A CI-only step
// would have left local runs and /precommit blind to it until CI failed.
val buildLogic = gradle.includedBuild("build-logic")

tasks.register("ktfmtFormat") {
    group = "formatting"
    description = "Formats build-logic. Each module's own ktfmtFormat covers the rest."
    dependsOn(buildLogic.task(":ktfmtFormat"))
}
tasks.register("ktfmtCheck") {
    group = "verification"
    description = "Checks build-logic's formatting. Each module's own ktfmtCheck covers the rest."
    dependsOn(buildLogic.task(":ktfmtCheck"))
}
tasks.named("sortDependencies") { dependsOn(buildLogic.task(":sortDependencies")) }
tasks.named("checkSortDependencies") { dependsOn(buildLogic.task(":checkSortDependencies")) }
tasks.named("check") { dependsOn(buildLogic.task(":check")) }
