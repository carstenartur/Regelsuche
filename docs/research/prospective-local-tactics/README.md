# When learning the next primitive rule pays for itself

Regelsuche learned an order for trying primitive rewrites from 26 earlier proofs. On the public mixed stream of Horner expressions and dot products, it solved **1,011 of 1,024** tasks within 524,288 logical units. The same greedy search with the original order solved **965**. Identical training and loading followed by disabling the learned order gave **939**. All three eventually solved all 1,024 tasks. On the prospective family of 256 squared residuals the learned order and free greedy search both solved **208**, while the previous branching primitive search solved **224**. The learned order, subtree counting and local theorem search are optional Java APIs.

## What the ordering learned

The solver rebuilt the training proofs from their source tasks and observed 93 primitive steps. It counts which step begins a proof and which rule follows each predecessor. For example, after taking a common left factor, cancelling an addend was its most frequent next choice. This ranking comes from the proofs. The implementation receives the existing primitive inventory and training tasks, and never receives a benchmark-family label. Every attempted provider incurs its charge. Every chosen step is checked in search and replayed in the final witness.

The complete cold bill is **13,080** units, including reading input (3,973), solving and checking lessons (7,145), fitting (1,450), and loading (512). Each row below starts with a new bill. The ledgers record both success under the individual case cap and completion before the stream deadline.

| Stream | Cases | Previous primitive (SHARED) | Subtree branching | Same greedy, free, untrained | Learned order | Learned minus free greedy, full work |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Horner/dot products, public | 1,024 | 861 | 931 | 965 | **1,011** | −25,832 |
| Stage products C, public | 512 | 384 | 416 | 416 | **416** | −7,176 |
| Determinants D, public | 512 | 480 | 512 | 512 | **512** | +13,848 |
| Squared residuals E, prospective | 256 | **224** | 208 | 208 | **208** | +11,432 |

On the mixed stream the learner stays cheaper than free greedy from task 345 onward. On C that point is task 333. Neither D nor E repays training. On E the learned order saves 1,648 running units over the untrained order, leaving 11,432 extra including training. Both solve precisely the same 208 cases. The previous branching solver with whole-root counts wins E even against the new branching solver with subtree counts. [Ordering bill, per-stream work and reproduction](development/primitive-ordering.md) · [Independent technical comparison on six streams](subtree-counts.md) · [Ordering ledgers](results/ordering/) · [Development ledgers](development/ordering/)

## Did symbolic local learning transfer?

The separate registered experiment asks whether a retained theorem can solve additional cases in a new outer expression. A source-only Java matcher indexes the operators at the root and two children of a learned pattern. It checks substitutions at candidate sites and independently replays steps on the full expression. The regression includes a successful escalation after a failed, paid primitive attempt. Removing the learned rule prevents that bounded proof. On development C/D, immediate local matching returned many learned steps but solved only 274/374 cases by the deadline. A paid primitive first attempt increased this to 384/480. The development selection rule preferred the larger combined count. [Development selection, controls and fingerprints](development/local-selection.json) · [Two-rotation verification](development/local-verification.json)

| Registered E arm | On time / 256 | Full work including setup | Setup |
| --- | ---: | ---: | ---: |
| SHARED, previous branching primitive | **224** | **170,048** | 0 |
| PRIOR, previous learned selector | 208 | 404,567 | 232,215 |
| LOCAL_OLD, local search with seven older schemata | 208 | 182,683 | 10,843 |
| LOCAL, local search with 26 retained schemata | 208 | 349,900 | 178,060 |
| DROP, same setup with all learned proposals disabled | 208 | 349,900 | 178,060 |

The registered 10% target required **247** timely solutions and at least one necessary learned rule on a case missed by all four controls. LOCAL admitted zero cases to its theorem matcher. Its full ledger was identical to DROP, leaving no eligible single-rule removal. E is short enough that all arms finished the whole stream before the cumulative deadline. The per-case budgets distinguish them. [Primary verification](results/verification.json) · [Complete E ledgers, including failures](results/E/) · [Cross-check of supplemental controls](results/reference-crosscheck.json)

All 16 extra SHARED successes come from the three-component ordinary factoring stratum with a cap of 256. On E/9 the primitive proof factors three component sums. SHARED pays 244 in search and 12 in replay. LOCAL produces the exact same proof, but pays 3 for selection, 247 for search and 12 for replay. Subtree counting pays 253 for search and 12 for replay. This same cost split occurs in all 16 cases. The separate polynomial audit counts toward the stream clock.

## What was held out and verified

The [registered protocol](https://github.com/carstenartur/Regelsuche/commit/f9c1c1ba35cd96e17e1835abaeac459fb5acbc9a) fixes the family, case and stream budgets, five official arms, and pass criteria. The [complete public freeze](https://github.com/carstenartur/Regelsuche/commit/892dcabf9fd24967009e225663940f62cc74010e) is the seed used to generate E. Its mathematical construction was disclosed up front. Its exact inputs, permutation and outcomes were unopened while choosing the implementation on the earlier public streams. [Secondary comparisons fixed at the freeze](supplements.md)

Two independently launched Java 25 JVMs ran each comparison in opposite arm orders. A separate Python implementation reconstructed the 256 inputs, checked disjoint whole polynomials up to variable renaming, verified targets and named rewrites, and reconciled the official 1,280 rows and 288 distinct proof paths. It also checked the four ordering arms, training receipts, and the separately generated subtree reference. All 256 subtree ledgers and proofs matched across the four supplemental JVMs. Each archived mathematical ledger is the common content of its two runs. These results measure solved tasks and logical work, including failed attempts. Elapsed-time gains and performance on external workloads remain open.

The local eight-project Maven reactor ran 4,683 tests successfully and skipped 16 conditional tests in its ordinary invocation. The opt-in study jobs ran separately. All 1,929 Java source files in the tested reactor matched the public freeze by SHA-256. The local Maven run used the cached Surefire 3.5.6. The project pins 3.5.4.

## Reproduction

Use a JDK 25 checkout, Maven and Python 3. Run from the repository root. Each JVM creates its own output. To reproduce known C/D panels, substitute `development` for `final` in the primary commands and verifier and use the C/D paths described in the supplementary studies.

```sh
set -eu
seed=892dcabf9fd24967009e225663940f62cc74010e
study="$PWD/docs/research/prospective-local-tactics"
work=$(mktemp -d)
for r in 0 1; do
  mvn -B -ntp -pl regelsuche-learning -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest=LocalTacticStudyTest -Dlocal.phase=final "-Dlocal.research=$PWD/docs/research" \
    "-Dlocal.freeze=$seed" "-Dlocal.rotation=$r" "-Dlocal.output=$work/local$r" test
  mvn -B -ntp -pl regelsuche-learning -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest=PrimitiveOrderingStudyTest "-Dordering.official=$work/local$r" "-Dlocal.freeze=$seed" \
    "-Dordering.research=$PWD/docs/research" "-Dordering.rotation=$r" "-Dordering.output=$work/order$r" test
  mvn -B -ntp -pl regelsuche-learning -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest=SubtreeNodeCountStudyTest "-Dsubtree.official=$work/local$r" "-Dlocal.freeze=$seed" \
    "-Dsubtree.research=$PWD/docs/research" "-Dsubtree.rotation=$r" \
    "-Dsubtree.rows=$work/sub$r" "-Dsubtree.output=$work/sub$r.json" test
done
python3 "$study/verify.py" "$work/local0" "$work/local1" final "$seed"
python3 "$study/verify_references.py" E "$seed" "$work/local0/E" "$work/order0/E" "$work/order1/E"
python3 "$study/verify_references.py" E "$seed" "$work/local0/E" "$work/sub0" "$work/sub1" \
  --subtree-reports "$work/sub0.json" "$work/sub1.json"
```
