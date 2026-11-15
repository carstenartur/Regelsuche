# Public matched discovery component measurements

The preregistered polynomial and rational panel formed five candidates. Four matched rules in the project inventory. Removing their project-novelty signal increased each score by exactly 2.0. The new rational candidate remained first and the four known candidates kept their order. All 47 recorded prefix budgets, from zero through 46, selected identical sets. The three-example support requirement admitted no candidate when each observation was mined separately.

| Arm | Formed | Formation dispatches | Occupied / configured candidate slots | Mined hypotheses with a refuting witness | Revisions after original challenge |
| --- | ---: | ---: | ---: | ---: | ---: |
| Production | 5 | 1 | 5 / 46 | 0 | 0 |
| No fan-in | 0 | 23 | 0 / 46 | 0 | 0 |
| No counterexample-guided refinement | 5 | 1 | 5 / 46 | 0 | 0 |
| No project-novelty scheduling | 5 | 1 | 5 / 46 | 0 | 0 |

The separate historical log-product control used all 12 successful public training observations from the reference campaign. On the unguarded initial statement the deterministic real-valued challenger inferred positivity of each factor and of their product. Within its configured boundaries and samples it returned no refuting witness. No proposal strategy ran, and the independent repeat of the terminal check reproduced the same result in every arm. The checker status is a bounded search outcome. These records establish neither unrestricted validity of the log identity nor effective repair of a refuted candidate.

The full arm uses the existing miner, binary known-rule input, interestingness scoring/tie-break, and deterministic refinement loop. The no-novelty arm changes only that input before ordering. No downstream verdict or holdout selects candidates, budgets, or weights. The no-refinement arm retains both the original validator and the terminal recheck. The log example has a separately budgeted slot and is excluded from all discovery counts. Historical endpoint pairs are visible to the miner. This panel measures component behavior on those public examples. The #355 aggregate experiment, target-free #383 discovery, held-out transfer, generalized benefit from project novelty, and successful counterexample-guided rescue require their own evidence.

[Protocol frozen before execution](https://github.com/carstenartur/Regelsuche/commit/67b8eb0345324b024adad2bfa92265ed3f556ba4) · [Raw observations, schedules, challenges and outcomes](evidence/discovery-component-panel-v1.json.gz)

## Paired replay with exact validation

The original campaign used validation revision `03b58d1d`. The paired replay retains the same observations, candidate IDs, mathematical statements, budget ceilings, ranker, and refinement strategies. Its validation implementation is [revision 9ddd37ac](https://github.com/carstenartur/Regelsuche/commit/9ddd37acd174edcfdfe440e72f726bf7392ed34c). The pre-registered protocol and original archive remain available.

| Measured quantity | Historical validator | Exact validator |
| --- | --- | --- |
| Source-pair equivalence provenance, 12 polynomial observations | Deterministic numeric samples | Exact univariate polynomial normal forms |
| Terminal check on the leading rational candidate in each nonempty arm | No counterexample found within budget | Inconclusive, with no defined scalar pair within the exact bounds |
| Terminal checks on the other four candidates in each nonempty arm | Four bounded no-counterexample outcomes | Four bounded no-counterexample outcomes |
| Candidate counts, rankings and selections | 5 / 0 / 5 / 5 | Identical at every prefix |
| Separate historical log control | Three inferred positivity conditions, no refuting witness | Identical |

The new provenance strings change each arm's input hash, although all 23 endpoint pairs and budgets agree. Across the full raw reports, exactly 31 scalar fields changed. Twelve are source evidence descriptions and four are the per-arm hashes. The remaining fifteen refer to one distinct rational candidate across the three occupied queues. They comprise the first check's status and explanation, the terminal revision status, and the repeated check's status and explanation. An inconclusive check supplies neither a witness nor a validation success.

[Historical raw evidence](evidence/discovery-component-panel-v1.json.gz) · [Exact-validation raw evidence](evidence/discovery-component-panel-exact-v1.json.gz)

## Reproduction

From the repository root, run `./gradlew :app:discoveryComponentPanel` and compare `build/reports/discovery-component-panel.json` with `gzip -dc docs/evidence/discovery-component-panel-exact-v1.json.gz`. Two fresh exact-validation executions produced 106,779 bytes with SHA-256 `fbeb2b16f1a61ff4281c22a9d31996e693e3fcbce709b63f91cdcb4dd971176a`. The checkout-owned JUnit test replays against this archive, checks both retained digests, and compares their outcomes. For the historical implementation, run the original [immutable experiment revision](https://github.com/carstenartur/Regelsuche/commit/a0abfcbf8b136047c52e51d87a13b0933433f9d7). Its uncompressed report has 106,716 bytes and SHA-256 `33300f2dd7f34a53d36ab36d5d241c453a289ab12d1b325310964618a8d169b7`. Wall-clock timestamps of hypotheses and revisions are omitted. Variable-membership sets are serialized in lexical order, preserving witness, strategy and queue order.
