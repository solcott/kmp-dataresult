import com.vanniktech.maven.publish.MavenPublishBaseExtension

// Publishing for every module in this repo. The vanniktech plugin does the work that is tedious to
// get right by hand for a KMP project: it wires up every target's publication, adds sources and
// javadoc jars, and validates the POM.
//
// GitHub Packages is the live destination. Maven Central is wired but gated behind a property so
// the switch is a one-liner later -- see README. Signing is only meaningful for Central, so it is
// gated with it; GitHub Packages does not require signed artifacts.
plugins {
  id("com.vanniktech.maven.publish")
  id("project-config")
}

// A description per module, keyed by project name, so the POM says something useful. A missing
// entry is a build failure rather than an empty <description>, which Central would reject later.
val descriptions =
  mapOf(
    "dataresult" to
      "Transport-agnostic result and error types for Kotlin Multiplatform data sources.",
    "uistate" to
      "Stale-while-revalidate view state for content backed by a data source.",
    "dataresult-apollo" to
      "Maps Apollo GraphQL responses onto dataresult's Outcome and DataError types.",
    "dataresult-store5" to
      "Maps Store5 StoreReadResponse onto dataresult's Outcome and DataError types.",
  )

configure<MavenPublishBaseExtension> {
  coordinates(group.toString(), project.name, version.toString())

  // Enabled by `-PpublishToCentral`. Left off, the Central endpoints are never contacted and the
  // signing plugin never demands a key, so a plain GitHub Packages publish needs no secrets.
  if (providers.gradleProperty("publishToCentral").isPresent) {
    publishToMavenCentral()
    signAllPublications()
  }

  pom {
    name.set(project.name)
    description.set(
      requireNotNull(descriptions[project.name]) {
        "No POM description for ':${project.name}' -- add one in kmp-published.gradle.kts."
      }
    )
    inceptionYear.set("2026")
    url.set("https://github.com/solcott/kmp-dataresult")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }
    developers {
      developer {
        id.set("solcott")
        name.set("Scott Olcott")
      }
    }
    scm {
      connection.set("scm:git:git://github.com/solcott/kmp-dataresult.git")
      developerConnection.set("scm:git:ssh://github.com/solcott/kmp-dataresult.git")
      url.set("https://github.com/solcott/kmp-dataresult")
    }
  }
}

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/solcott/kmp-dataresult")
      // Gradle properties for local publishing, env vars for CI. GitHub Packages authenticates
      // even public reads, so consumers need a token too -- see README.
      credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
      }
    }
  }
}
