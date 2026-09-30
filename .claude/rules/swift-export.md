---
paths:
  - "dataresult/src/commonMain/**"
  - "uistate/src/commonMain/**"
---

# Swift export

Consumers export `dataresult` and `uistate` to Swift wholesale. Anything in the reachable API that
Swift export can't handle breaks the consumer's iOS build. Compilation, tests and the ABI check here
don't notice.

`checkSwiftExport` catches it instead. It runs as part of `check`, applied by the `swift-export-guard`
plugin in build-logic, and fails the build on all three constructs below. Keep this list and its patterns in
`build-logic/src/main/kotlin/CheckSwiftExport.kt` in sync.

- Use `sealed class`, never `sealed interface`.
- Keep every Compose type out of these two modules. The only exception is the
  `androidx.compose.runtime:runtime-annotation` dependency (`@Immutable`), which contains no Compose
  types.
- Public collections stay `List`. Don't use `kotlinx.collections.immutable` types (`ImmutableList`,
  `persistentListOf`) in the public API. `@Immutable` on the class already makes Compose treat it as
  stable, whatever its field types. (`f44cf21` tried this and was reverted.)
