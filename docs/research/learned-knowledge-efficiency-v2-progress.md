# Implementation ledger: learned knowledge efficiency v2

Spec: `docs/research/learned-knowledge-efficiency-v2-design.md`.

The user explicitly commissioned implementation and necessary build repairs.
Routine implementation and reversible integration proceed under that authority.

- [x] Inspect main, #1025/#1042/#1043, comments, review threads and current CI.
- [x] Create isolated branch and integrate existing #1039/#1041 changes.
- [x] Run unmodified #1043 search/learning baseline using Java 25 and offline Gradle.
  Result: BUILD SUCCESSFUL; search 429 + learning 879 tests; no failures/errors/skips.
- [x] Repair CI workflow integration without changing limits or gate policy.
  PR #1044 retains the workflow-count contract and integrates the typed comparison.
  109 Maven build-contract tests passed. Full CI is tracked separately.
- [x] Profile real baseline worker, including output/model serialization.
  2,016 valid requests retained from a copied bytecode snapshot: 1,164 BASE and
  852 LEARNED_NAIVE. Codec accounts for roughly 36% of execution samples,
  response serialization 29–30%, canonicalization 18–20%, matching below 1%.
  These are sampled attribution diagnostics, not a relative speed estimate.
- [x] Learn checked schemas from actual training traces with independent application verification.
- [x] Integrate bounded relevant selection and measured source-only net utility.
- [x] Add joint typed plans, domain verification and reusable execution lifecycle.
- [x] Freeze v2 corpus before evaluation: commit 5bc9e21e,
  protocol SHA256 7e9b51c8f4c66655489350ef4be982411b6ab5ae0585ddb8b725b1ef15d7113c.
- [ ] Run fresh-process reload, fair profiles and real amortization sequences.
- [ ] Run affected suites, independent review, complete CI, publish coherent PRs.

Pre-evaluation verification: a clean, build-cache-disabled Java 25 build passed
all four affected module suites: search 450, learning 904, math-algorithms 316,
experiments 263 (1,933 tests, zero failures/errors/skips). The seven explicitly
requested SDK/math publications also completed. The external comparison package
passed 59 Python tests with pinned SymPy 1.14.0. Four independent review findings
were reproduced and corrected: recursive DAG hashing, capability-delta replay,
an endpoint/cost audit gap and missing runtime provenance. A targeted independent
follow-up found no outstanding items from that review. This is not performance
acceptance; the registered full-lifecycle comparison remains to be run.

Review focus: expression-valued repeated bindings; large unsupported inputs;
stale checker/inventory revisions; complete-mode suppression; memory/liveness
of multiple outputs; costs outside logical search counters.
