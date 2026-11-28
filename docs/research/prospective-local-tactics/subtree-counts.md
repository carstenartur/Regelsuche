# Supplementary primitive control with shared subtree counts

A primitive rewrite often builds a new root while retaining an entire child expression. The optional `solveWithSubtreeNodeCounts` method remembers exact occurrence counts for the root and its immediate compound children in an identity table local to the query. Two occurrences of the same object each contribute its full size. Counting below a child uses an iterative walk bounded by the native 10,000-occurrence limit. The counter charges initialization, inspection, table reads, insertion and clearing the table when it reaches 128 entries. Resource-observed sessions retain the native objective. The comparison uses the same inventory, search policy, checker, independent proof replay, exact polynomial audit and fixed case and stream budgets as the earlier whole-root SHARED control.

## Public development streams

| Stream | Cases | SHARED on time | SUBTREE on time | SHARED total work | SUBTREE total work |
| --- | ---: | ---: | ---: | ---: | ---: |
| MIXED | 1,024 | 861 | 931 | 622,234 | 575,430 |
| A | 512 | 384 | 384 | 339,904 | 332,384 |
| B | 512 | 384 | 384 | 308,384 | 288,416 |
| C | 512 | 384 | 416 | 291,488 | 272,800 |
| D | 512 | 480 | 512 | 183,362 | 178,542 |

The implementation and cache depth were selected using these public cases. All 1,024 MIXED cases succeeded under their individual budgets in both configurations. Its gain therefore comes from reaching more tasks before the stream deadline. The C and D increases also appear under the individual budgets. The observations measure logical work. Wall-clock performance and transfer to external inputs remain open. [Work breakdowns and hashes of all row ledgers](development/subtree-counts.json)

## Reproduction

Run the comparison from the repository root with Java 25 and Maven. Each JVM receives a fresh output path. The runner checks every public SHARED aggregate and the SHA-256 digest of its full row ledger against the earlier published study.

```bash
runs=$(mktemp -d)
for r in 0 1; do
  mvn -B -pl regelsuche-learning -am -Dtest=SubtreeNodeCountStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false "-Dsubtree.research=$PWD/docs/research" \
    "-Dsubtree.output=$runs/counts-$r.json" "-Dsubtree.rotation=$r" test || exit 1
done
cmp "$runs/counts-0.json" "$runs/counts-1.json"
```

To include the separately frozen E panel, add `"-Dlocal.freeze=$seed" "-Dsubtree.official=$official" "-Dsubtree.rows=$runs/e-$r"` to the Maven command. Set `seed` to the public solver freeze commit and `official` to a completed official run containing `E/SHARED.jsonl` and `E/summary.json`. The runner reads E from this ledger, checks every reconstructed SHARED row for equality, and writes all SUBTREE rows and their primitive paths to the new directory. Compare these two directories with `diff -r "$runs/e-0" "$runs/e-1"`. This arm is a separate technical comparison to the registered five-arm study.
