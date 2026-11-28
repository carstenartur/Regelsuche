# Where learned tactic selection helps, and what failed to transfer

On two new 512-case families the learned selection policy finished 256 interpolation tasks and 192 squared-radius tasks. Primitive search and both automatic controls finished 352 and 320. The fresh model produced no learned steps in any returned proof or partial proof on these families. Neither family met the preregistered improvement or the single-tactic removal criterion.

The [public protocol](https://github.com/carstenartur/Regelsuche/blob/b950e96be225c8254544bc12eaf654c7a128f64b/docs/research/prospective-tactic-transfer/protocol.md) fixes the two mathematical generators, eight arms, budgets and decision rule. The [complete freeze](https://github.com/carstenartur/Regelsuche/commit/c7141702e091ff94b5ed2e3c5af5b5d2d66136dd) fixes all code, the learned models and the independent checker before generating the final cases. Each family got 1,048,576 units. Success required 10% more verified solutions than the strongest control in **each** family and one previously unsolved task in each family that demonstrably required an individual acquired tactic.

| Solver | Interpolation solved | Interpolation full work | Squared radii solved | Squared radii full work |
|---|---:|---:|---:|---:|
| Primitive search | 352 | 404,800 | 320 | 379,744 |
| Historical automatic selection | 352 | 422,770 | 320 | 393,618 |
| Historical forced matcher | 320 | 545,874 | 256 | 612,114 |
| Fresh automatic selection | 352 | 464,681 | 320 | 439,881 |
| Fresh forced matcher | 288 | 683,273 | 224 | 676,041 |
| Fresh forced matcher with new checker | 288 | 683,291 | 224 | 676,059 |
| Fresh learned selection with new checker | 256 | 799,801 | 192 | 830,777 |
| Same selection with all proposals disabled | 256 | 795,385 | 192 | 825,689 |

All arms finished the entire stream within the family deadline. Individual case limits of 256, 768, 2,048 or 8,192 units determined which tasks counted. Preliminary training, failed queries, selection, search, in-search verification, independent final replay and an exact polynomial audit contribute to full work. The polynomial audit is charged to the family clock. Individual caps cover routing, search and replay. The two margins required 388 and 352 solutions. Relative to the strongest controls the policy finished 27.3% and 40.0% fewer tasks. Removing all learned proposals gave the same successful cases. No task qualified for single-tactic removal. Raw rows include every partial and unsuccessful proof.

## What did the solver learn?

Primitive search solved all 20 training tasks. The batch promoted a library of six proof-backed schemata after testing on 18 separate gate tasks. Five have IDs absent from the historical seven-schema library. The training recipe combines neighbouring general primitive rewrites and records the actual primitive solution paths. Measured comparisons on 46 separate calibration inputs, including intermediate states, yielded 469 trials. Acquisition cost 55,067 units, calibration cost 126,081, model loading cost 1,953, and preparation cost 6,908. The learned solver and its disabled control each paid the full 190,009 units per family. See the [training inputs and primitive proofs](training/training.json), [validated schemata](training/snapshot.json) and [calibration comparisons](training/fit.json).

A depth-two decision tree uses anonymous operator shapes at intermediate states to select one schema, the complete matcher or primitive proposals. Previously unobserved shapes of a known goal permit the complete matcher. A [separate development regression](../../../regelsuche-learning/src/test/java/de/regelsuche/evolution/LearnedTacticPolicyTest.java) uses a primitive step, a learned step and another primitive step. Disabling precisely the schema used prevents completion within three search levels. This shows that the instrument can detect such a case. Its appearance in development gives no prospective credit.

## Why did cases get lost?

* **Interpolation.** The primitive solver finished 96 cases the policy lost, and the policy added zero. Each group of 32 lost cases had either two levels with shifted coordinates at cap 768, three levels with plain coordinates at cap 768, or three levels with shifted coordinates at cap 2,048. One historical schema, `u*(v-w)+u*w → u*v`, matched a local factor-and-cancel identity in this new family. The historical forced matcher used it 992 times across 448 returned paths (including partial paths). That schema was absent from the fresh library. On input A/15 the historical matcher reduced six steps to four yet cost 573 units for search plus replay. Primitive search cost 378 and the fresh policy cost 649 for the same output `a + 50016`.
* **Squared radii.** The primitive solver finished 128 cases the policy lost, and the policy added zero. Both orientations lost all 32 two-term instances at cap 768 and all 32 three-term instances at cap 2,048. On B/5 the primitive solver reached nine nodes using six steps and 478 units of search plus replay. The policy reached 25 nodes after one step and charged 780 units against the cap of 768. The primitive solution uses the difference-of-squares, square-product and cancellation rules. The composition of those steps was absent from both learned inventories on this family.

A [retrospective structural check](inspect_coverage.py) found zero matches for the six fresh source patterns at any occurrence in the 1,024 inputs or in the intermediate expressions of the returned primitive paths, including partial paths. The check spans 5,856 recorded step targets, including 960 from unfinished cases. Other explored states can differ.

Both families are synthetic exact polynomial problems, internally related by design. The retained polynomial signatures separate each final source from all training, calibration, previously published sources accounted for by the protocol, and from every other final instance under variable renaming. Local rule overlap occurs by construction and is especially visible in the interpolation example. The comparison of two fresh JVMs in opposite arm orders gave byte-identical results on all 8,192 case records and 1,890 distinct within-family proof paths, alongside the timing observations. The [independent reconciliation](results/verification.json) includes paired outcomes, losses and costs by stratum.

## Where learning paid on the development stream

On the earlier public Horner/dot-product stream the new independent checker increased timely completions from **708 to 720** while using the same seven learned schemata and the original selection. Both runs ultimately completed all 1,024. In-search checking and terminal replay each saved 6,650 units. Checker setup rose by 18. Full work fell from 752,130 to **738,848**. The more expensive learned selection plus checker finished 510. Median elapsed time on this shared machine rose from 1.78 to 1.89 seconds for the checker change alone, so the gain concerns logical work. The [four-arm comparison](development/) isolates selection and checking on the same 1,536 learned applications.

In a supplementary retrospective removal on this development stream, the new checker and old selection paid identical setup with proposals enabled and disabled. Learned proposals finished **720 versus 698** cases and saved **21,948** units over all 1,024 (738,848 versus 760,796). With the previous checker the same comparison saved 8,648 units. Reusing proof context increased the measured net contribution of the learned proposals by **2.54×** here.

The productive change in this experiment is the independently checked reuse of unchanged proof context. A future research question is whether alternative tactic libraries can be retained or dropped on a paid validation gate, and whether a cheap applicability decision can avoid matching and replay when their expected cost exceeds the primitive path. The archived final families are now development evidence. A new transfer claim needs an untouched family.

## Reproducing the experiment

Use JDK 25, Maven, Python 3 and the repository root. The frozen solver uses ordinary typed arithmetic expressions and a maximum output node count. Neither family names nor generator metadata are input features. The published training receipts include every primitive training witness, promotion charges and the paid calibration trials. Each family starts with a fresh solver and pays its own setup once.

```bash
seed=c7141702e091ff94b5ed2e3c5af5b5d2d66136dd
research="$PWD/docs/research"
study="$research/prospective-tactic-transfer"
runs=$(mktemp -d)
for pass in 0 1; do
  mvn -B -pl regelsuche-learning -am -Dtest=PairedTacticStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dpaired.phase=train \
    -Dpaired.research="$research" -Dpaired.output="$runs/train-$pass" test || exit 1
  diff -r "$study/training" "$runs/train-$pass" || exit 1
  mvn -B -pl regelsuche-learning -am -Dtest=PairedTacticStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dpaired.phase=final \
    -Dpaired.research="$research" -Dpaired.freeze="$seed" \
    -Dpaired.rotation="$pass" -Dpaired.output="$runs/final-$pass" test || exit 1
done
python3 "$study/verify.py" "$runs/final-0" "$runs/final-1" "$seed" > "$runs/verified.json"
```

The independent Python implementation reconstructs all inputs from the freeze, verifies disjointness of the coefficient signatures under variable renaming, and checks the exact polynomial, tree size and each contiguous, rule-labelled proof step. It reconciles setup, search, replay, audit and deadlines. Individual logical operations inside the Java search and fitting procedures use their recorded counters. The rotation comparison covers every row and proof, including unsuccessful searches. The elapsed-time observations are separate. The raw [ledgers and complete proof paths](results/) are losslessly compressed. The [Horner/dot-product comparison](development/) has its own four-rotation reproduction command.
