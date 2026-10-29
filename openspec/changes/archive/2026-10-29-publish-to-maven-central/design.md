# Design

## Context

Publishing is set up in one convention plugin, `build-logic/src/main/kotlin/kmp-published.gradle.kts`,
which every module applies. It uses the vanniktech plugin (0.37.0, the same as `kmp-parcelize`) and
already builds a POM that meets Central's requirements. It currently has two destinations:

- a `GitHubPackages` repository, which is always on and authenticated with `GITHUB_TOKEN`;
- Maven Central (`publishToMavenCentral()` + `signAllPublications()`), which only switches on with
  `-PpublishToCentral`.

Two workflows publish. In `build.yml`, the `publish-snapshot` job runs after `build` on pushes to
`main`, behind a `-SNAPSHOT` guard. It has a GitHub Packages step and a Central step that only runs
when `vars.PUBLISH_TO_CENTRAL` is set, and that variable was never created. `release.yml` runs when
a GitHub release is published and only publishes to GitHub Packages. Both run on macOS, because the
Apple klibs have to be part of the publication.

`kmp-parcelize` publishes to Central under the same namespace with the same five
`ORG_GRADLE_PROJECT_*` secret names. Its secrets live in a `release` environment and can't be shared
with this repository. This repository has no secrets, variables or environments yet.

## Goals / Non-Goals

**Goals:**
- Publish only to Central, with no switch, so it can't be left half-on.
- Keep `publishToMavenLocal` working on a machine with no credentials and no signing key.
- Make an unsigned release fail in CI before anything is uploaded, rather than in the Portal's
  validation.

**Non-Goals:**
- Releasing to Central automatically. A maintainer publishes each deployment in the Portal (see D3).
- Moving releases to an ubuntu runner. Parcelize can, but this repository's build and its
  Swift-export check assume macOS, and that's a separate question.
- Cleaning up the old GitHub Packages snapshots. The maintainer can delete them by hand afterwards.
- Changing coordinates, module names or the version scheme.

## Decisions

### D1. Remove GitHub Packages outright

Remove the `publishing { repositories { maven("GitHubPackages") } }` block, the `GITHUB_ACTOR` and
`GITHUB_TOKEN` env wiring, and `packages: write` in both workflows.

*Alternative:* keep publishing to GitHub Packages as well for a release or two. That was rejected
because every consumer moves at once. A second channel would also bring back the ordering trap:
whichever channel publishes first uses up the version if the second one then fails.

### D2. Central is always configured, and signing depends on whether a key is present

```kotlin
publishToMavenCentral()
if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
```

`publishToMavenCentral()` only registers the repository and its tasks. It contacts nothing until a
`…ToMavenCentralRepository` task runs, so having it always on costs nothing locally. Signing is
what would break `publishToMavenLocal` without a key, so signing is the only thing that stays
conditional. It now depends on the key that vanniktech already reads, not on a separate flag.

*Alternatives:*
- *Keep `-PpublishToCentral`.* Rejected because with only one channel, the flag no longer protects
  anything. It just has to be repeated on every CI command.
- *Sign unconditionally, as parcelize does.* Rejected for now because it hasn't been checked whether
  vanniktech 0.37 then requires a key for `publishToMavenLocal`, and the spec requires that it does
  not. If task 1.2 shows that unconditional signing is harmless, it is fine to switch to it.

*Trap:* the key reaches CI as the environment variable `ORG_GRADLE_PROJECT_signingInMemoryKey`.
Task 1.2 confirms that `providers.gradleProperty` sees it. If it doesn't, the check has to use
`findProperty`, or check the environment variable as well. Otherwise CI would quietly skip signing.

### D3. Manual release, through `publishAllPublicationsToMavenCentralRepository`

`publishToMavenCentral()` defaults to not releasing automatically. That task uploads and validates a
deployment, and a maintainer then clicks "Publish" in the Portal. Parcelize already works this way.
For the first release, this lets the deployment's file list be checked (7 modules, each with its
targets) and the deployment dropped if it's wrong. Once a version is released on Central, it can
never be deleted or replaced.

*Alternative:* `automaticRelease = true`, or `publishAndReleaseToMavenCentral`. That was postponed
rather than rejected. Switching is a one-line change, and it's safe as long as the D4 guard stays.

### D4. The snapshot job publishes to Central unconditionally, and the `-SNAPSHOT` guard stays

Remove the `vars.PUBLISH_TO_CENTRAL` condition and the GitHub Packages step. On a `-SNAPSHOT`
version, vanniktech sends the upload to the Central snapshot repository, where signing is optional.
The guard used to stop GitHub Packages from using up a version. Now it stops a release version on
`main` from becoming a pending Central deployment. With D3 that's harmless but confusing. If
automatic release is ever turned on, it becomes an accidental release. The comment on the guard
should say this.

### D5. The release workflow fails fast when the signing secrets are missing

Before publishing, `release.yml` checks that `ORG_GRADLE_PROJECT_signingInMemoryKey` and the Central
username are not empty, and stops with an error naming the missing secret. Without this check, a
missing key would mean D2 quietly skips signing, and the failure would only show up in the Portal's
validation. A Gradle-side check (fail when a non-snapshot version is uploaded to Central unsigned)
was considered. It's more precise but more code. The workflow check covers the one path that
publishes releases.

### D6. Repository-level secrets

The five secrets are repository secrets, not a `release` environment. `publish-snapshot` already
reads them that way, and an environment would need a branch policy that allows both `main` (for
snapshots) and tags (for releases). There's only one maintainer, and secrets aren't exposed to pull
requests from forks, so an environment adds little protection.

## Risks / Trade-offs

- [The first push to `main` after the merge runs `publish-snapshot` with no secrets, or before
  "Enable SNAPSHOTs" is on] → the job fails. Mitigation: the migration plan puts both
  prerequisites before the merge.
- [D2's check doesn't see the environment variable in CI] → snapshots publish unsigned, which is
  allowed. Releases are protected by D5. Task 1.2 checks it directly.
- [The workflow goes green before the release is public] → someone assumes X.Y.Z is out when it
  isn't. Mitigation: `RELEASING.md` makes "Publish in the Portal" an explicit numbered step, and the
  workflow's final log line says so.
- [Consumers still pointed at GitHub Packages stop getting snapshots, with no error] → they silently
  stay on the last snapshot that was published there. Mitigation: a CHANGELOG entry and a README
  migration note. The maintainer is moving every consumer at once.
- [Central deletes snapshots after 90 days] → a consumer pinned to an old snapshot breaks. This is
  documented in the README, and snapshots aren't meant for pinning.

## Migration Plan

1. **Before merging (maintainer, by hand):** add the five `ORG_GRADLE_PROJECT_*` repository secrets
   (repeat the values from `kmp-parcelize`; GitHub can't copy them), and turn on "Enable SNAPSHOTs"
   for `io.github.solcott` in the Central Portal.
2. **Merge.** The resulting push to `main` publishes `0.1.0-SNAPSHOT` to the Central snapshot
   repository. Check that it resolves from a consumer with no token.
3. **Move consumers** to `mavenCentral()` plus the Central snapshot repository.
4. **First release (0.1.0)**, following the rewritten `RELEASING.md`. Inspect the deployment in the
   Portal, then click Publish.
5. **Optionally,** delete the old snapshots from GitHub Packages.

**Rollback:** nothing has been released yet. Reverting the change brings GitHub Packages snapshots
back. If 0.1.0 has been uploaded but not published, drop the deployment in the Portal. A published
release can't be rolled back, so the next version supersedes it.

## Open Questions

- After 0.1.0, should releases switch to automatic? That's a one-line change to D3 and doesn't affect
  anything else here.
