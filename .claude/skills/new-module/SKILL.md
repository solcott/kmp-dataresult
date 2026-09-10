---
name: new-module
description: Add a new published KMP module to kmp-dataresult, such as an adapter (dataresult-ktor, say) or a new uistate integration. It covers every file that has to change, so the pattern isn't re-derived from the six existing modules.
argument-hint: "<module-name>"
---

Add the module `$ARGUMENTS`. Every step below is required, and a missed one fails the build or ships
an incomplete artifact. Copy the nearest existing module rather than writing anything from scratch.
Adapters should follow `dataresult-apollo`, Compose integrations `uistate-compose`.

1. **`settings.gradle.kts`**: add `include(":<name>")` after the existing includes.

2. **`<name>/build.gradle.kts`**:
   - `plugins { id("kmp-library"); id("kmp-published") }`. For a module with `@Composable` code, add
     `id("org.jetbrains.kotlin.plugin.compose")` **with no version**, and give it the same comment as in
     `uistate-compose/build.gradle.kts`.
   - Declare `macosArm64()` in `kotlin { }` with the standard comment, *unless* a dependency doesn't
     publish a macOS artifact (as with `dataresult-store5`). If so, leave it out and say why in a comment.
   - Dependencies go in `commonMain.dependencies`. Use `api` for anything that appears in a public
     signature and `implementation` otherwise, and put a comment on each saying which it is and why.
     Add new libraries to `gradle/libs.versions.toml` as *floors* (the minimum version needed), with a
     comment.
   - Put tests that need a Compose frame clock (Molecule, `presenterTestOf`) in
     `named("nonWebTest").dependencies`, not `commonTest`.

3. **`build-logic/src/main/kotlin/kmp-published.gradle.kts`**: add a `descriptions` entry keyed by
   the module name. The build fails without one.

4. **Sources** go under `<name>/src/commonMain/kotlin/io/github/solcott/<name with '-' → '/'>/`. The
   Android namespace is derived as `io.github.solcott.<name with '-' → '.'>`, so the package must
   match. Tests go in `commonTest` (or `nonWebTest`).

5. **Swift-export rules**, for anything under `dataresult`/`uistate`: `sealed class`, not
   `sealed interface`, and no Compose types.

6. **Docs**: add a row to the README "Artifacts" table (the artifact, its contents, what it depends on), any
   target exception to the README "Targets" line, and an entry under `## Unreleased` in
   `CHANGELOG.md`.

7. Run `/precommit <name>` and confirm the new module's tests actually ran (a non-zero count on
   `jvmTest`).
