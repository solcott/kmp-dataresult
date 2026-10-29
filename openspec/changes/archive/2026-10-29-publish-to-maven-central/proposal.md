# Proposal

## Why

GitHub Packages requires authentication for every read, even of a public package. So every consumer
of this library, and every CI job that builds one, needs a `read:packages` personal access token
just to resolve a dependency. Maven Central needs no token. The `io.github.solcott` namespace is
already verified there, because `kmp-parcelize` publishes under it. Nothing has been released yet
(the version is `0.1.0-SNAPSHOT` and there are no tags), so switching now means 0.1.0 is the first
release, and it goes to the channel it will stay on.

## What Changes

- **BREAKING (for snapshot consumers):** Maven Central replaces GitHub Packages as the only
  publishing channel. Releases go to Central. Snapshots go to the Central Portal snapshot
  repository. Nothing is published to GitHub Packages any more. Consumers switch all at once to
  `mavenCentral()` and, for snapshots, the Central snapshot repository. Neither needs a token.
- The `-PpublishToCentral` switch and the `PUBLISH_TO_CENTRAL` repository variable go away: Central
  is always the destination. Signing depends on whether a key is present, so `publishToMavenLocal`
  still needs no credentials or key.
- `release.yml` uploads every module to Central, and a maintainer publishes the deployment by hand
  in the Central Portal. Automatic release is deliberately left out for now.
- The `-SNAPSHOT` guard on the snapshot job stays. With Central as the only channel, it is the thing
  that stops a release version from being uploaded from `main`.
- README "Installing" and "Snapshots", `RELEASING.md`, the CHANGELOG, and the CI/release rule for
  agents are rewritten for one channel.
- Steps the maintainer does by hand, outside the code: add the five `ORG_GRADLE_PROJECT_*` repository
  secrets (the same names as in `kmp-parcelize`), turn on "Enable SNAPSHOTs" for the namespace in
  the Central Portal, and optionally delete the old GitHub Packages snapshots.

## Capabilities

### New Capabilities
- `artifact-publishing`: where and how the library's artifacts are published (releases, snapshots
  and local builds), what consumers need in order to resolve them, and the guards that keep a
  version from being published by mistake.

### Modified Capabilities

None.

## Impact

- **Build:** `build-logic/src/main/kotlin/kmp-published.gradle.kts` (remove the GitHubPackages
  repository and the `publishToCentral` gate, and make signing depend on a key being present).
- **CI:** `.github/workflows/build.yml` (`publish-snapshot`) and `.github/workflows/release.yml`.
  Both drop `packages: write`.
- **Docs:** `README.md`, `RELEASING.md`, `CHANGELOG.md` (the "Unreleased" snapshot line), and
  `.claude/rules/ci-release.md`.
- **Repository settings (manual):** five secrets. The `PUBLISH_TO_CENTRAL` variable was never
  created, so there is nothing to remove.
- **Consumers:** existing users of the GitHub Packages snapshots replace that repository block with
  the Central snapshot repository. They need no API or coordinate changes, because the
  `io.github.solcott:<module>` coordinates stay the same.
- **No library code or public API changes.** The ABI dumps are untouched.
