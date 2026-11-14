# Open-target macro formation on the public #383 panel

On the twelve frozen downstream tasks, production scoring, ordered path enumeration and seeded random selection obtained the same utility outcomes in four campaigns. Each arm shortened the found path in one TRAIN task per campaign. None improved a VALIDATION or TEST task, gained reachability over the primitive baseline or caused a correctness regression. The four seeds repeat the same fixed tasks.

| Four campaigns per arm | Production score | Ordered paths | Seeded paths |
| --- | ---: | ---: | ---: |
| Accepted macro counts by campaign | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 1, 2, 2 |
| Rejected primitive replay groups | 0 | 0 | 1 |
| Improved TRAIN task slots | 4 / 16 | 4 / 16 | 4 / 16 |
| Improved VALIDATION task slots | 0 / 16 | 0 / 16 | 0 / 16 |
| Improved TEST task slots | 0 / 16 | 0 / 16 | 0 / 16 |
| Primitive baseline states expanded / candidate transformations generated | 200 / 356 | 200 / 356 | 200 / 356 |
| Macro-enabled states expanded / candidate transformations generated | 204 / 396 | 204 / 396 | 204 / 396 |

Across each arm's 48 task slots the existing evaluator reported 4 improvements, 28 unchanged successes and 16 tasks on which neither search reached its target. Its improvement definition accepts fewer path steps or fewer expanded states. The aggregate counts above show that the local path reduction coexists with increased search work over this panel.

## What entered formation

Formation used the four TRAIN start expressions and their declared assumptions. The reference primitive operation mapping was fixed in advance. The demonstrated endpoints, expected operations, intermediate paths, trace identifiers and evaluation tasks were excluded from the former's interface. The code executes the production AST rewrite engine to depth two, derives the endpoint and abstract operations from each generated path, then invokes the production replay and macro generalizer. A regression test removes all demonstration targets, expected operations, trace IDs and evaluation inputs and checks the identical input projection.

| Public start | Engine expansions | Generated moves | Eligible two-step paths |
| --- | ---: | ---: | ---: |
| `(x+1)^2` | 2 | 4 | 2 |
| `(y+2)^2` | 2 | 4 | 2 |
| `(x+x)-x` | 4 | 5 | 0 |
| `(y*1)+0` | 5 | 10 | 5 |

Each policy received the same bounded path inventory and could select one path per start. Production selected the lowest endpoint score, with the same lexical tie-break used for enumeration. Enumeration selected the first path, and random selection sampled uniformly using the frozen campaign seed. Replay can reach a commutatively equivalent representation of a selected endpoint. The raw record retains the selected concrete path, the independently replayed path, any rejected group, and each accepted macro with its witness. Every campaign's candidate set was frozen and content-addressed before task evaluation.

This experiment compares path selection inside a shared generalization and evaluation pipeline, with the same path limits and task budgets. The older demonstrated-path study used the supplied TRAIN endpoints and operation sequences. [Prospective plan commit](https://github.com/carstenartur/Regelsuche/commit/1fa021ac7f17bcf7886f761b0cd2c37bce9fd26f) · [Plan](../research/benchmarks/candidate-independent/track-c-open-macros/comparison-plan.json) · [All paths and paired outcomes](../research/benchmarks/candidate-independent/track-c-open-macros/comparison-result.json.gz) · [Demonstrated-path comparison](candidate-independent-reusable-macro-batch.md)

## Reproduction

```sh
mvn -B -pl app -am '-Dtest=TrackCOpenMacroComparisonTest,CandidateIndependentReusableMacroAdapterTest,CandidateIndependentMacroReplayAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false test
python3 scripts/verify-track-c-open-macros.py
```

The Java test reruns the real search, generalization and evaluation and compares all 286,381 bytes. The Python verifier independently checks the input and case bindings, nested content hashes, complete seed/selection schedules, replay endpoint bindings, accepted candidate union and paired outcome decisions from the raw paths and costs. Two Java 25 runs produced SHA-256 `ccbdc84f3958a491b01b1f8fc606b1c7e3989ba178e79f3207e392f3efd617bb`.
