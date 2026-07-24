# Frozen downstream task stream

## Purpose

`regelsuche.downstream-task-stream/v1` materializes the twelve reusable-macro evaluation tasks from the candidate-independent benchmark as a separate ordered stream **without reading candidate or evaluation results**.

The stream is derived only from three files frozen before evaluated execution:

- `research/benchmarks/candidate-independent/case-corpus.json`;
- `research/benchmarks/candidate-independent/corpus-freeze-receipt.json`;
- `research/benchmarks/candidate-independent/macro-primitives.json`.

The generator's input interface is an explicit allowlist containing exactly those three files and the repository revision. It accepts no batch result, candidate ledger, paired evaluation or report directory as input.

The freeze receipt states that execution had not started and that no evaluated results existed. It also binds both the case corpus and the `macro-primitives/v1` baseline inventory by content hash.

## Ordering and comparison contract

Tasks are ordered by the immutable case-corpus array and then by each case's task array. The resulting stream contains:

- four TRAIN tasks;
- four VALIDATION tasks;
- four TEST tasks;
- the exact source, target, assumptions and search budget for every task;
- the case and inventory content roots;
- the policy `IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET`.

No `outcome`, baseline result, candidate-enabled result, candidate ID or macro ID is permitted in the stream artifact. Those belong to later paired execution.

## Verification

```bash
./gradlew verifyDownstreamTaskStream
```

The task generates the stream twice and requires byte-identical output. The independent verifier recomputes all rows from the frozen corpus, checks the receipt and baseline-inventory bindings and rejects:

- reordered tasks, even with a recomputed top-level hash;
- a substituted baseline-inventory hash;
- any leaked evaluation outcome.

## Claim boundary

This contract establishes that a fixed, candidate-independent ordered stream exists and can be reproduced from pre-execution inputs. It does not establish candidate utility, correctness, break-even, formal proof, external novelty or publication readiness. Paired execution and full lifecycle accounting remain separate phases of #384.
