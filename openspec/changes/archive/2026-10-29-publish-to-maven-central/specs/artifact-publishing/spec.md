# Spec Delta

## Purpose

Defines where the library's artifacts are published (releases, snapshots and local builds), what a
consumer needs in order to resolve them, and the guards that keep a version from being published by
mistake.

## ADDED Requirements

### Requirement: Releases resolve from Maven Central without credentials
Every published module SHALL be available on Maven Central at its released version, as
`io.github.solcott:<module>`. A consumer SHALL need only `mavenCentral()` and no credentials to
resolve it.

#### Scenario: Consumer resolves a release
- **WHEN** a consumer whose only repository is `mavenCentral()` depends on `io.github.solcott:dataresult:X.Y.Z` after X.Y.Z is released
- **THEN** the dependency resolves with no token or credentials configured

#### Scenario: Every target is in the release
- **WHEN** X.Y.Z is released
- **THEN** each module's artifacts for all of its targets (`android`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js`, `wasmJs`, except `macosArm64` for `dataresult-store5`) are on Maven Central at X.Y.Z

### Requirement: Released artifacts are signed and fully described
Every artifact in a release SHALL be signed, and every module's POM SHALL carry a name, description,
project URL, license, developer and SCM information.

#### Scenario: Release without a signing key
- **WHEN** the release workflow runs without the signing key available
- **THEN** it fails before uploading anything to Maven Central

#### Scenario: Module without a description
- **WHEN** a published module has no POM description configured
- **THEN** the build fails instead of publishing an empty description

### Requirement: A release becomes public only when the maintainer publishes it
The release workflow SHALL upload a release to Maven Central as a deployment that is not yet
public. The release SHALL become public only when a maintainer publishes that deployment in the
Central Portal.

#### Scenario: Release workflow finishes
- **WHEN** the release workflow for X.Y.Z completes successfully
- **THEN** X.Y.Z exists as a deployment in the Central Portal awaiting publication, and does not yet resolve from Maven Central

#### Scenario: Faulty deployment is dropped
- **WHEN** the maintainer drops an unpublished deployment of X.Y.Z
- **THEN** X.Y.Z is not public, and the release workflow can upload X.Y.Z again

### Requirement: Snapshots are published to the Central snapshot repository
After every successful build of a push to `main` whose version ends in `-SNAPSHOT`, that version
SHALL be published to the Central Portal snapshot repository. A consumer SHALL be able to resolve it
from that repository without credentials.

#### Scenario: Green push to main
- **WHEN** a push to `main` with version `X.Y.Z-SNAPSHOT` builds successfully
- **THEN** `X.Y.Z-SNAPSHOT` of every module is published to the Central snapshot repository

#### Scenario: Consumer resolves a snapshot
- **WHEN** a consumer adds `https://central.sonatype.com/repository/maven-snapshots/` and depends on `io.github.solcott:dataresult:X.Y.Z-SNAPSHOT`
- **THEN** the dependency resolves with no token or credentials configured

#### Scenario: Failed build
- **WHEN** the build of a push to `main` fails
- **THEN** nothing is published

### Requirement: Only snapshot versions are published from main
A push to `main` SHALL NOT publish anything when its version does not end in `-SNAPSHOT`.

#### Scenario: Release version on main
- **WHEN** the "Prepare version X.Y.Z" commit, whose version is `X.Y.Z`, is pushed to `main`
- **THEN** the snapshot job publishes nothing, and reports that X.Y.Z is not a snapshot version

### Requirement: Maven Central is the only remote channel
The build SHALL NOT publish to GitHub Packages or to any remote repository other than Maven Central
and its snapshot repository.

#### Scenario: Release or snapshot publish
- **WHEN** a release or a snapshot is published
- **THEN** nothing is uploaded to GitHub Packages

### Requirement: Local publishing needs no credentials
Publishing to the local Maven repository SHALL work with no Central credentials and no signing key,
so a change can be tried in a consuming project before it is pushed.

#### Scenario: publishToMavenLocal on a fresh machine
- **WHEN** a contributor with no Central credentials or signing key configured publishes to the local Maven repository
- **THEN** every module is published to the local Maven repository, unsigned, and the build succeeds
