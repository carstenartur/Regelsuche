# Historical representation calibration on the public atlas

| Production search | Public cases | Returned occurrences, including sources | Cases with a verified new target representation |
| --- | ---: | ---: | ---: |
| Scalar best-first | 14 | 2,353 | 6 |
| Structural diversity | 14 | 299 | 7 |

Both policies recovered the same six of the twelve historical positive cases. Structural diversity also reached the published distribution fitness-valley control. Neither policy reached the inconsistent near-miss, any of the five square-completion targets, or the Sophie Germain factorization. On Sophie Germain each policy returned only the source at depth zero. Its polynomial is valid and its representation differs from the published product.

All 2,652 returned occurrences agreed with their respective source under bounded exact rational-polynomial comparison. Independent polynomial expansion with SymPy 1.14.0 reproduced every one of these equivalence decisions. Occurrences include duplicate expressions and common starting points. The two policies have the same case-specific configured ceilings. Counts of returned occurrences measure neither primitive work nor speed.

### Reproduction

Run the module test from the checkout root with JDK 25. It executes the experiment in a new directory and checks both uncompressed evidence hashes. The experiment entry point `de.regelsuche.benchmark.HistoricalIntrinsicCalibration` takes a new output directory as its only argument.

```bash
mvn -pl regelsuche-experiments -am -Dtest=HistoricalIntrinsicCalibrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Two fresh JVM runs wrote identical UTF-8 bytes. The freeze contains the ordered expressions, depth, primitive rule names, equivalence-preserving flags and assumptions. After committing all rows to disk, the assessor records intrinsic validity for every occurrence and joins exact formatted and permitted-AC correspondence using the published target. An occurrence receives credit only at positive depth, subject to its case criterion, intrinsic agreement and the source baseline. Original atlas v1 artifacts and outcomes retain their original meanings.

| Artifact | Uncompressed bytes | SHA-256 over uncompressed UTF-8 |
| --- | ---: | --- |
| [Candidate freeze](freeze.json.gz) | 635,420 | `4aa696c27aee2832250b054dfcfbf033d01eece6bed32f18791490233e477a4b` |
| [Per-occurrence assessment](assessment.json.gz) | 543,166 | `3e725f3ce7b31cb7d2b31b3507550dd3b44ae62e2f1a9fbb9876559de091cf01` |

The corpus SHA-256 is `53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422`. The prospective criteria and information boundary are in the [protocol](protocol.md). All 14 cases were previously public. The observations calibrate historical representation matching and intrinsic validation within these two production policies.
