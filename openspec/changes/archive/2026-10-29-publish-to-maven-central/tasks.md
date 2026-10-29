# Tasks

## 1. Publishing convention

- [x] 1.1 In `build-logic/src/main/kotlin/kmp-published.gradle.kts`, remove the `GitHubPackages` repository block and the `publishToCentral` gate. Call `publishToMavenCentral()` unconditionally, and `signAllPublications()` only when `signingInMemoryKey` is present (design D2). Rewrite the file's header comment for one channel. Verify that `./gradlew build` passes (through `dataresult-gradle-runner`), and that `./gradlew :dataresult:tasks --all` lists `publishAllPublicationsToMavenCentralRepository` and no `…GitHubPackagesRepository` task.
- [x] 1.2 Check how signing behaves (design D2's trap). With no key configured, `./gradlew publishToMavenLocal` should succeed with no `sign…` tasks in the graph. With `ORG_GRADLE_PROJECT_signingInMemoryKey` set to a throwaway key in the environment, `./gradlew publishToMavenLocal --dry-run` should list the `sign…` tasks. If the environment variable isn't seen, change the check so it is, and record why in a comment.

## 2. Snapshots

- [x] 2.1 In `.github/workflows/build.yml` `publish-snapshot`, remove the GitHub Packages step, `packages: write`, and the `vars.PUBLISH_TO_CENTRAL` condition. Run the Central step on every snapshot without `-PpublishToCentral`. Rewrite the `-SNAPSHOT` guard's comment for Central (design D4). Verify that the file has no `GitHubPackages`, `PUBLISH_TO_CENTRAL` or `publishToCentral` left, and that `actionlint` (if installed) reports nothing.
- [x] 2.2 Rewrite README "Snapshots" to use the Central snapshot repository (`https://central.sonatype.com/repository/maven-snapshots/`, with `includeGroup("io.github.solcott")` and `snapshotsOnly()`, and no credentials). Mention that Central deletes snapshots after 90 days. In `CHANGELOG.md` under `## Unreleased`, change the "Snapshot builds of `main` are published to GitHub Packages" line to Central. Verify that the README snippet is valid `settings.gradle.kts` syntax by checking it against the existing `dependencyResolutionManagement` block in this repo's `settings.gradle.kts`.

## 3. Releases

- [x] 3.1 In `.github/workflows/release.yml`, remove `packages: write` and the GitHub Packages step. Add a step that fails, naming the missing secret, when `ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEY` or `ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME` is empty (design D5). Add a Central step that runs `publishAllPublicationsToMavenCentralRepository` with the five `ORG_GRADLE_PROJECT_*` secrets mapped as in `kmp-parcelize`, followed by a `::notice::` saying the deployment must be published in the Central Portal. Verify that `actionlint` (if installed) reports nothing and that the step order is check → publish → notice.
- [x] 3.2 Rewrite `RELEASING.md` for Central only. Keep steps 1–4 and 6. Replace step 5 with: publish the GitHub release, wait for `release.yml`, then open the deployment in the Central Portal, check the file list (7 modules, with `dataresult-store5` missing `macosArm64`), and click Publish, or drop it and fix. Replace the "Snapshots" and "Publishing to Maven Central" sections with a short note on the one-time setup (secrets, "Enable SNAPSHOTs") and on why the `-SNAPSHOT` guard stays. Verify that no mention of GitHub Packages, `gpr.`, `PUBLISH_TO_CENTRAL` or `publishToCentral` remains.
- [x] 3.3 Update `.claude/rules/ci-release.md` so that it says the snapshot job and the release workflow publish to Maven Central only, and that a release needs a manual Publish in the Portal. Verify that the rule has no mention of GitHub Packages left.

## 4. Consumer docs

- [x] 4.1 Rewrite README "Installing" to say `mavenCentral()` is all that's needed, with no token, and point to "Snapshots" for unreleased builds. Add a short migration note for anyone who used the GitHub Packages snapshots (delete that repository block and its `gpr.*` properties). Add a CHANGELOG `## Unreleased` line saying that artifacts are published to Maven Central. Verify that README has no `maven.pkg.github.com` or `gpr.` left.

## 5. Integration

- [x] 5.1 Run `/precommit` and verify that it's green. Then verify that `grep -rn -i -E "github packages|maven.pkg.github|gpr\.|PUBLISH_TO_CENTRAL|publishToCentral"` over the repository (excluding `openspec/`, `.claude/worktrees/`, `build/` and `.gradle/`) finds nothing.

## Workflow follow-up

These are manual steps outside the code (design, "Migration Plan"):

- Before merging, add the repository secrets `ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME`, `…MAVENCENTRALPASSWORD`, `…SIGNINGINMEMORYKEY`, `…SIGNINGINMEMORYKEYID` and `…SIGNINGINMEMORYKEYPASSWORD`, and turn on "Enable SNAPSHOTs" for `io.github.solcott` in the Central Portal.
- After merging, confirm that `0.1.0-SNAPSHOT` resolves from the Central snapshot repository with no token, then move the consuming projects over.
- Release 0.1.0 by following `RELEASING.md`, and inspect the deployment before publishing it.
- Optionally, delete the old GitHub Packages snapshots.
- Archive the change.
