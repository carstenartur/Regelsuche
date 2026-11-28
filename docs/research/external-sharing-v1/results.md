# Exact sharing recovered one existing mean on FPBench

The [protocol](protocol.md) and [runner](https://github.com/carstenartur/Regelsuche/commit/e12406d4cbdd4ae151c1dc0c8668ff228c9359c7) were published before the ten input files were opened. Both JVM runs used the frozen source tree `f7a3e102919cec8bf5472845986d51be1a936c5d` and produced byte-identical [per-program results](results.jsonl), SHA-256 `ba1f94eaf196b58ebd2a55973bf9650710cb99e067db4f088c76e51c3a6e49d1`.

All 70 programs were accounted for. Seven entered the existing exact-polynomial projection. All three evaluation arms agreed on all 28 substitutions. One program met the prespecified material threshold of at least two arithmetic operations and 10% saved. Counts below are for one evaluation per admitted program. The counts were the same for all four substitutions.

| Pinned file | Programs | Admitted | Material | Direct tree | Selected sharing | Full structural CSE |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| apron | 6 | 0 | 0 | 0 | 0 | 0 |
| daisy | 7 | 0 | 0 | 0 | 0 | 0 |
| fptaylor-real2float | 11 | 3 | 0 | 32 | 32 | 32 |
| fptaylor-tests | 10 | 3 | 0 | 14 | 14 | 14 |
| graphics | 1 | 0 | 0 | 0 | 0 | 0 |
| herbie | 3 | 0 | 0 | 0 | 0 | 0 |
| ml-frameworks | 17 | 1 | 1 | 18 | 14 | 14 |
| precimonious | 2 | 0 | 0 | 0 | 0 | 0 |
| rump | 3 | 0 | 0 | 0 | 0 | 0 |
| salsa | 10 | 0 | 0 | 0 | 0 | 0 |
| **Total** | **70** | **7** | **1** | **64** | **60** | **60** |

The selected definition was the mean of four inputs in FPBench's TensorFlow Lite variance example. On the expanded expression, the plan computes `(a + b + c + d) / 4` once and reads it twice. This saves three additions and one division, or 22.2% of the tree's 18 arithmetic instructions. Full CSE also evaluates 14 arithmetic instructions. The original FPCore program already binds this mean with `let`. The result demonstrates recovery of that sharing after expansion and validates the new definition/reference executor. Timing, preparation cost, floating-point rounding, learned selection and a speed advantage on the original program remain outside the measurement. [Original program and scope](https://github.com/FPBench/FPBench/blob/090d81982edc0d2a597f4c15644253d4f266a6ef/benchmarks/ml-frameworks.fpcore#L194-L206)

The six other admitted programs had identical counts in all arms. Of the 63 unsupported programs, 38 used unsupported operators, 13 exceeded the variable limit, eight had a variable denominator, two used unsupported declarations, one yielded the reader's “expected atom” rejection and one exceeded the cross-worker exact-literal restriction. All ten files parsed at file level. The ledger includes the original reason for every rejected program and the four output values for every admission.

## Reproduction

Use Java 25 and Maven from the repository root. The runner verifies each file's Git blob ID before parsing. It records overlay reference reads (including the first occurrence) and full-CSE cache hits (subsequent occurrences of an already evaluated binary operation) separately from arithmetic instructions.

```sh
git clone https://github.com/FPBench/FPBench.git /tmp/fpbench-external-sharing
git -C /tmp/fpbench-external-sharing checkout --detach 090d81982edc0d2a597f4c15644253d4f266a6ef
for run in 1 2; do
  mvn -B -pl regelsuche-experiments -am test \
    -Dtest=ExternalSharingStudyTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dsharing.inputs=/tmp/fpbench-external-sharing/benchmarks \
    -Dsharing.evidence="/tmp/external-sharing-${run}.jsonl" || exit 1
done
cmp /tmp/external-sharing-1.jsonl /tmp/external-sharing-2.jsonl
cmp /tmp/external-sharing-1.jsonl docs/research/external-sharing-v1/results.jsonl
```
