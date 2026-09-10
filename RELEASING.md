# Releasing

1. Set the release version in `gradle.properties` (drop the `-SNAPSHOT`).

2. Update `CHANGELOG.md`: rename the `Unreleased` header to the release version and add a fresh
   `Unreleased` section above it. `git diff <last-tag> -- '*/api/*'` lists every public API change
   since the last release, so nothing reaches consumers without a changelog line.

3. Verify against the consuming projects first, if the change is behavioral:

   ```
   ./gradlew publishToMavenLocal
   ```

   then build the consuming projects against that version before going further.

4. Commit and tag:

   ```
   git commit -am "Prepare version X.Y.Z"
   git tag -am "Version X.Y.Z" X.Y.Z
   git push && git push --tags
   ```

5. Publish a GitHub release for the tag. That fires `.github/workflows/release.yml`, which publishes
   every artifact to GitHub Packages using the workflow's own token — there are no secrets to set up.

6. Set the next development version in `gradle.properties` (`X.Y.Z+1-SNAPSHOT`) and commit.

## Snapshots

Nothing to do by hand. After every green push to `main`, the `publish-snapshot` job in
`.github/workflows/build.yml` publishes the current version to GitHub Packages, but only if it ends
in `-SNAPSHOT`. That guard is why pushing the step 4 commit publishes nothing: a release version
published from `main` would be burned (GitHub Packages versions are immutable), and `release.yml`
would then fail with 409. Step 6 puts snapshots back on.

## Publishing to Maven Central

Central is wired but off, for releases and snapshots alike. To turn it on, pass
`-PpublishToCentral`, which enables both `publishToMavenCentral()` and `signAllPublications()` in
`kmp-published.gradle.kts`. That needs the
usual vanniktech credentials as `ORG_GRADLE_PROJECT_*` environment variables — see the
`kmp-parcelize` release workflow, which already does this — and a step in `release.yml` to run
`publishAllPublicationsToMavenCentralRepository`.

Worth doing at some point: Central requires no authentication to *read*, which would remove the
`read:packages` token every consumer of this library currently needs.

### Turning on Central snapshots

`publish-snapshot` already has a Maven Central step. On a `-SNAPSHOT` version the vanniktech plugin
sends it to the Central Portal snapshot repository, and signing is optional. The step stays skipped
until all of this is in place:

1. The `io.github.solcott` namespace is verified on the
   [Central Portal](https://central.sonatype.com/publishing/namespaces), and "Enable SNAPSHOTs" is
   chosen from its menu. Snapshot uploads are rejected until then.
2. Repository secrets `ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME` and
   `ORG_GRADLE_PROJECT_MAVENCENTRALPASSWORD` hold a Central Portal user token. The signing secrets
   (`ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEY`, `…KEYID`, `…KEYPASSWORD`) are optional for snapshots;
   a snapshot is signed if they are set. These are the same names as in `kmp-parcelize`.
3. The repository variable `PUBLISH_TO_CENTRAL` is `true` (Settings → Secrets and variables →
   Actions → Variables). Unsetting it switches Central snapshots off again.
4. README's "Snapshots" section tells consumers about the repository, which needs no token:

   ```kotlin
   maven("https://central.sonatype.com/repository/maven-snapshots/") {
     mavenContent {
       includeGroup("io.github.solcott")
       snapshotsOnly()
     }
   }
   ```

Central does not validate snapshots and deletes them after 90 days.
