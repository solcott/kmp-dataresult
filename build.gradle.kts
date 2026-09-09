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
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.publish) apply false
    alias(libs.plugins.dependency.sorter)
}

// Pins the Gradle daemon's JVM. `./gradlew updateDaemonJvm` writes the criteria to
// gradle/gradle-daemon-jvm.properties; the foojay resolver auto-downloads a matching JDK.
tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(libs.versions.jvm.toolchain.get())
    vendor.set(JvmVendorSpec.AMAZON)
}
