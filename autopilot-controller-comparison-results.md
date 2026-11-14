# Controller ablation on the public #355 production campaign

The three controllers produced the same conjecture and consumed the same work in every measured resource unit. Equal candidate-slot allocation changed which batch held unused capacity. The production feedback controller read both execution receipts and the lifecycle decision, found zero eligible branches and ended the campaign. Both static controllers chose a one-round horizon before generating observations.

| Controller | Candidate slots, full batch / pair | Candidates, full batch / pair | Rejected clusters | Next decisions |
| --- | ---: | ---: | ---: | ---: |
| Production feedback | 11 / 1 | 1 / 0 | 1 | 0 |
| Fixed uniform | 6 / 6 | 1 / 0 | 1 | 0 |
| No feedback | 11 / 1 | 1 / 0 | 1 | 0 |

Each lane started with empty evidence, the same target-free brief, rule inventory, search policy and twelve seeds from two generator families. Both batches received one mining dispatch. The full batch drew on all twelve observations. The pair used the two predeclared, alpha-equivalent observations, one from each family. The miner rejected their cluster with `alpha-distinct-support<2`. The other branch retained `open-target-conjecture-91ef32d35224049e4cd2` in all three lanes.

## Mechanical work per controller

| Resource | Configured | Executed | Skipped | Remaining |
| --- | ---: | ---: | ---: | ---: |
| Generated states | 100000 | 240 | 0 | 99760 |
| Explored states | 10000 | 228 | 0 | 9772 |
| Observations | 12 | 12 | 0 | 0 |
| Mining batches | 2 | 2 | 0 | 0 |
| Candidate slots | 12 | 1 | 11 | 0 |
| Validation checks | 120 | 6 | 0 | 114 |
| Counterexample attempts | 1200 | 77 | 0 | 1123 |
| Project novelty comparisons | 1200 | 7 | 0 | 1193 |
| Proof attempts | 12 | 1 | 0 | 11 |
| Lifecycle handoffs | 12 | 1 | 0 | 11 |

Three positive and three negative holdouts passed. Counterexample search returned `NO_COUNTEREXAMPLE_FOUND`. The project-internal novelty check returned `NOVEL_WITHIN_PROJECT`. The polynomial-normal-form backend returned `CONFIRMED` with a `LOSSLESS` translation and the proof report recorded `SYMBOLICALLY_VERIFIED`. The lifecycle handoff completed. Formal proof, external novelty, promotion and public evidence each have status `NOT_EVALUATED`.

The campaign is a public, single-round retrospective control. Its branch outcomes were known when the control policies were registered. The production controller has no choice between eligible continuations on these inputs. Uniformity applies to the stage's two competing batches. Measuring uniform allocation across the seven stages would require a common cost model for resources such as search states, validation checks and proof attempts.

## Artifacts and reproduction

The [protocol](autopilot-controller-comparison.md) was committed before the first execution of either control. Its SHA-256 digest is `7ddc243adfd1297f1c1c331453872cbea501fe2beddcfd7145a7c1e8be250f8e`. The [compressed raw matrix](evidence/autopilot-controller-comparison-v1.json.gz) retains every lane's seed catalog, branch inputs, mining decisions, receipts, ledger, solver obligation, solver result and qualification gates. Two fresh Java 25 processes produced byte-identical matrices of 138180 bytes with SHA-256 `04eaa8a4bb800791b62fed4d1cab4fcd6c3e2e4de46a9c561a24a03272972d19`.

From the repository root, produce a fresh matrix and compare every byte with the retained run.

```sh
./gradlew :regelsuche-autopilot:installDist
mkdir -p build/reports/controllers
regelsuche-autopilot/build/install/regelsuche-autopilot/bin/regelsuche-autopilot \
  --compare-controllers build/reports/controllers/matrix.json
gzip -dc docs/evidence/autopilot-controller-comparison-v1.json.gz \
  | cmp - build/reports/controllers/matrix.json
```

`./gradlew :regelsuche-autopilot:dockerIntegrationTest` includes a host/image byte comparison using the production Dockerfile. The recorded pair above ran on the host. Testcontainers skipped the image execution in this workspace because a Docker daemon was unavailable. The full default production run reproduced all 37 files from base revision `34eb0900f26f9791545e0201d2f5db2d4a2ee96f` byte for byte and passed the production campaign verifier.
