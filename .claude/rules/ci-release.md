---
paths:
  - ".github/**"
  - "gradle.properties"
  - "RELEASING.md"
  - "CHANGELOG.md"
---

# CI, publishing and dependency updates

- `.github/workflows/build.yml` runs `ktfmtCheck`, `checkSortDependencies`, `buildHealth`, then
  `build` on macOS. Apple targets only compile and link on a Mac.
- Maven Central is the only publishing channel. On a push to `main`, the `publish-snapshot` job
  publishes to the Central snapshot repository, but only while `version` in `gradle.properties`
  ends in `-SNAPSHOT`. On a release version the upload would go to Central's release endpoint
  instead, and Central release versions can never be deleted or replaced, so keep that guard.
- The release process is in `RELEASING.md`. `release.yml` uploads a deployment but doesn't release
  it: a maintainer checks it and clicks Publish in the Central Portal. A green workflow doesn't
  mean the version is public.
- Signing in `kmp-published.gradle.kts` follows the presence of `signingInMemoryKey`, so local
  publishing needs no key. `release.yml` checks the secrets first so a release can't go up unsigned.
- Dependency updates come from the hosted Renovate app, configured in `.github/renovate.json5`. It
  opens a PR as soon as a version is published, with no rate limits, and scans about every 4 hours.
  It doesn't track `jvm-toolchain` or setup-java's `java-version`, so bump those by hand.
