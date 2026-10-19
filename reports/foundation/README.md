# Foundation — measurements

| Metric | Target | Result | Notes |
|--------|--------|--------|-------|
| App launch (`XCTApplicationLaunchMetric`) | < 1 s | **0.66 s** average (5 runs: 0.63–0.68 s) | iPhone 16 Pro simulator on a Mac, 2026-10-19. Measures launch to first frame on an empty database, not a real device — re-measure on the target iPhone. |
| Core line coverage | ≥ 80% | 100% (88/88) | `scripts/coverage-gate.sh` |
| App line coverage | ≥ 80% | 92.4% (691/748) | `scripts/coverage-gate.sh` |

Reproduce: see "Tests" in [`docs/development.md`](../../docs/development.md).
