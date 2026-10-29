import com.vanniktech.maven.publish.MavenPublishBaseExtension

// Publishing for every module in this repo. The vanniktech plugin does the work that is tedious to
// get right by hand for a KMP project: it wires up every target's publication, adds sources and
// javadoc jars, and validates the POM.
//
// Maven Central is the only remote destination: releases go to a Central Portal deployment that is
// published by hand, and -SNAPSHOT versions go to the Central snapshot repository. See
// RELEASING.md.
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
    "uistate" to "Stale-while-revalidate view state for content backed by a data source.",
    "uistate-compose" to
      "Collects a stream of Outcomes into ContentState retained with androidx retain.",
    "uistate-circuit" to
      "Collects a stream of Outcomes into retained ContentState, for Circuit presenters.",
    "dataresult-apollo" to
      "Maps Apollo GraphQL responses onto dataresult's Outcome and DataError types.",
    "dataresult-store5" to
      "Maps Store5 StoreReadResponse onto dataresult's Outcome and DataError types.",
    "dataresult-ktor" to
      "Maps Ktor HTTP calls and responses onto dataresult's Outcome and DataError types.",
  )

configure<MavenPublishBaseExtension> {
  coordinates(group.toString(), project.name, version.toString())

  // Only registers the repository and its tasks; nothing contacts Central until a
  // ...ToMavenCentralRepository task runs. Without automaticRelease, a release upload waits in the
  // Portal for someone to publish it. On a -SNAPSHOT version it goes to the snapshot repository
  // instead, so nothing here tells a snapshot from a release -- the -SNAPSHOT guard in build.yml's
  // publish-snapshot job is what keeps a push to main from uploading a release version.
  publishToMavenCentral()

  // Signing follows the key rather than a flag, so publishToMavenLocal works on a machine without
  // one. A release must still be signed: release.yml fails before uploading if the key is missing.
  if (providers.gradleProperty("signingInMemoryKey").isPresent) {
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
