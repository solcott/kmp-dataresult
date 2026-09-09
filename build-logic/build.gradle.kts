import dev.detekt.gradle.Detekt

plugins {
  `kotlin-dsl`
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
  alias(libs.plugins.dependency.sorter)
}

dependencies {
  implementation(libs.android.gradle.plugin)
  implementation(libs.kotlin.gradle.plugin)

  // `implementation`, not `compileOnly`: the convention plugins below don't just reference these
  // types, they `apply` them by id. A compileOnly marker compiles fine and then fails at apply
  // time with "Plugin with id '...' not found".
  implementation(libs.plugins.dependency.sorter.toDep())
  implementation(libs.plugins.detekt.toDep())
  implementation(libs.plugins.ktfmt.toDep())
  implementation(libs.plugins.publish.toDep())

  // Required in order to use the same detekt.yml as the main project, which references Compose
  // rule ids that would otherwise fail config validation.
  detektPlugins(libs.detekt.compose.rules)
}

// Should be synced with gradle/gradle-daemon-jvm.properties.
kotlin { jvmToolchain(libs.versions.jvm.toolchain.get().toInt()) }

tasks.validatePlugins { enableStricterValidation = true }

detekt {
  config.setFrom(files("$rootDir/../detekt/detekt.yml"))
  buildUponDefaultConfig = true
  allRules = false
}

tasks.withType<Detekt> {
  reports { html.required = true }
  exclude("**/build/**")
  exclude("**/generated/**")
}

ktfmt {
  googleStyle()
  removeUnusedImports = true
}

fun Provider<PluginDependency>.toDep() = map {
  "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}
