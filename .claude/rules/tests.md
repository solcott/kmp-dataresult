---
paths:
  - "*/src/*Test/**"
---

# Tests

- Use `kotlin.test`, `runTest`, Turbine, Molecule (`moleculeFlow(RecompositionMode.Immediate)`) and
  Circuit's `presenterTestOf`.
- **Compose-clock tests go in `nonWebTest`, not `commonTest`.** Molecule's frame clock is
  browser-only, so under Node recomposition never advances and the test hangs until it times out.
  `kmp-library` defines the `nonWeb` source set group.
- jvm, android and the Apple targets run `commonTest` + `nonWebTest`. js and wasmJs run only
  `commonTest`, so `uistate-circuit`'s js and wasm runners run 0 tests by design.
- `:<module>:jvmTest --tests '<fqcn>'` is the fastest runner. Browser test tasks
  (`jsBrowserTest`, `wasmJsBrowserTest`) need Chrome; the Node ones don't.
