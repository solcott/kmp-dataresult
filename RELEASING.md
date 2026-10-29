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

5. Publish a GitHub release for the tag. That fires `.github/workflows/release.yml`, which uploads
   every module to Maven Central as a deployment, **but doesn't release it**. When the workflow is
   green, open the deployment in the
   [Central Portal](https://central.sonatype.com/publishing/deployments) and check it: 7 modules,
   each with its `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js` and `wasmJs`
   artifacts, except that `dataresult-store5` has no `macosArm64`. Then click **Publish**.

   If anything is wrong, click **Drop** instead, fix it, and re-run the workflow. Once a version is
   published on Central it can never be deleted or replaced, so this check is the last chance.

6. Set the next development version in `gradle.properties` (`X.Y.Z+1-SNAPSHOT`) and commit.

## Snapshots

Nothing to do by hand. After every green push to `main`, the `publish-snapshot` job in
`.github/workflows/build.yml` publishes the current version to the Central snapshot repository, but
only if it ends in `-SNAPSHOT`. That guard is why pushing the step 4 commit publishes nothing: on a
release version the upload would go to Central's release endpoint, leaving a stray X.Y.Z deployment
in the Portal (and, if automatic release were ever turned on, releasing X.Y.Z from `main`). Step 6
puts snapshots back on.

## One-time setup

Needed once, before the first snapshot or release reaches Central; until then `publish-snapshot`
and `release.yml` fail.

1. The `io.github.solcott` namespace is verified on the
   [Central Portal](https://central.sonatype.com/publishing/namespaces), and "Enable SNAPSHOTs" is
   chosen from its menu. Snapshot uploads are rejected until then.
2. Five repository secrets hold a Central Portal user token and the signing key:
   `ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME`, `…MAVENCENTRALPASSWORD`, `…SIGNINGINMEMORYKEY`,
   `…SIGNINGINMEMORYKEYID` and `…SIGNINGINMEMORYKEYPASSWORD`. They are the same names as in
   `kmp-parcelize`. `release.yml` fails before uploading if the signing key or username is missing.
   Snapshots are signed if the key is set, but don't require it.

Releases are published by hand in the Portal. To release automatically instead, pass
`automaticRelease = true` to `publishToMavenCentral()` in `kmp-published.gradle.kts`, and drop the
Portal check from step 5. Keep the `-SNAPSHOT` guard if you do: it becomes the only thing between a
push to `main` and a permanent release.
