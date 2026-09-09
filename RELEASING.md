# Releasing

1. Set the release version in `gradle.properties` (drop the `-SNAPSHOT`).

2. Update `CHANGELOG.md`: rename the `Unreleased` header to the release version and add a fresh
   `Unreleased` section above it.

3. Verify against the consuming projects first, if the change is behavioral:

   ```
   ./gradlew publishToMavenLocal
   ```

   then build `Countries` and `Recipes` against that version before going further.

4. Commit and tag:

   ```
   git commit -am "Prepare version X.Y.Z"
   git tag -am "Version X.Y.Z" X.Y.Z
   git push && git push --tags
   ```

5. Publish a GitHub release for the tag. That fires `.github/workflows/release.yml`, which publishes
   every artifact to GitHub Packages using the workflow's own token — there are no secrets to set up.

6. Set the next development version in `gradle.properties` (`X.Y.Z+1-SNAPSHOT`) and commit.

## Publishing to Maven Central

Central is wired but off. To turn it on, pass `-PpublishToCentral`, which enables both
`publishToMavenCentral()` and `signAllPublications()` in `kmp-published.gradle.kts`. That needs the
usual vanniktech credentials as `ORG_GRADLE_PROJECT_*` environment variables — see the
`kmp-parcelize` release workflow, which already does this — and a step in `release.yml` to run
`publishAllPublicationsToMavenCentralRepository`.

Worth doing at some point: Central requires no authentication to *read*, which would remove the
`read:packages` token every consumer of this library currently needs.
