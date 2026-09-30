---
paths:
  - "*/src/commonMain/**"
  - "*/api/**"
---

# Public API

- The README's "Design notes" explain the non-obvious API decisions. Read them before changing
  public API.
- Every module's public API is pinned by the dumps in `<module>/api/`, and `checkKotlinAbi` fails
  `check` on any difference. After an *intended* change, run `./gradlew updateKotlinAbi` and commit
  the `api/` diff with the change. Never run it just to make the check pass: that would rubber-stamp
  an accidental API change.
- Names mirror the primitive underneath: `produceContentState` ↔ `produceState`,
  `produceRetainedContentState` ↔ `produceRetainedState`. It's `mapData`, not `map`, because these
  values live in `Flow`s.
- User-visible changes get an entry under `## Unreleased` in `CHANGELOG.md`.
