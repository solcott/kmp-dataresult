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
- On a push to `main`, the `publish-snapshot` job publishes to GitHub Packages, but only while
  `version` in `gradle.properties` ends in `-SNAPSHOT`. A release version published there is
  immutable and would break `release.yml`. The job's Maven Central step is skipped unless the
  repository variable `PUBLISH_TO_CENTRAL` is `true`.
- The release process is in `RELEASING.md`. It publishes to GitHub Packages; Maven Central is wired
  but gated behind `-PpublishToCentral`.
- Dependency updates come from the hosted Renovate app, configured in `.github/renovate.json5`. It
  opens a PR as soon as a version is published, with no rate limits, and scans about every 4 hours.
  It doesn't track `jvm-toolchain` or setup-java's `java-version`, so bump those by hand.
