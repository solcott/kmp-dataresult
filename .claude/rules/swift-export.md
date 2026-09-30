---
paths:
  - "dataresult/src/commonMain/**"
  - "uistate/src/commonMain/**"
---

# Swift export

Consumers export `dataresult` and `uistate` to Swift wholesale. Anything in the reachable API that
Swift export can't handle breaks the consumer's iOS build *with no warning here*: this repo's own
build, tests and ABI check all stay green.

- Use `sealed class`, never `sealed interface`.
- Keep every Compose type out of these two modules. The only exception is the
  `androidx.compose.runtime:runtime-annotation` dependency (`@Immutable`), which contains no Compose
  types.
- Public collections stay `List`. Don't use `kotlinx.collections.immutable` types (`ImmutableList`,
  `persistentListOf`) in the public API. `@Immutable` on the class already makes Compose treat it as
  stable, whatever its field types. (`f44cf21` tried this and was reverted.)
