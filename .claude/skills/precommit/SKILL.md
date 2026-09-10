---
name: precommit
description: Run this repo's CI checks locally before committing. It formats, sorts dependencies, builds every target with tests and detekt, and flags a missing CHANGELOG entry. It runs in the Haiku Gradle runner and returns a short verdict. Use it before any commit that touches Kotlin or Gradle files.
argument-hint: "[module]"
context: fork
agent: dataresult-gradle-runner
---

Mirror CI (`.github/workflows/build.yml`) for the current working tree, and report in the runner's
usual ≤20-line format.

Module argument: `$ARGUMENTS`. If it is empty, build everything. If it names a module (for example `uistate`),
scope step 2 to `:<module>:build`.

1. Run `./gradlew -q ktfmtFormat sortDependencies`. Then run `git status --short` and list any files
   these tasks rewrote. Those rewrites are expected; they just need to be part of the commit.
2. Run `./gradlew build` (or `:<module>:build`) with output filtered to failures. Report the test
   counts per module for the `jvmTest` results, and the `@Test` comparison the runner normally does.
   If `checkKotlinAbi` fails, quote the dump diff it prints and say that `./gradlew updateKotlinAbi`
   is the fix *only if the API change is intended*. Never run `updateKotlinAbi` yourself: that would
   rubber-stamp an accidental API change.
3. Run `git diff --stat HEAD` and `git status --short`. If any file under `*/src/*Main/` changed but
   `CHANGELOG.md` did not, say "CHANGELOG.md has no entry for a change to main sources". Do not edit
   it.

Finish with one line: **ready to commit**, or what's blocking it.
