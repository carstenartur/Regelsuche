# A paid grandchild filter increased dispatch work

This is a post-hoc development comparison on the six known families in the [frozen distributive study](../README.md). We tested one specific way to exclude an impossible schema earlier. The ordinary selector checks the expression's root operator and the two child shapes. The experimental selector also checks a mandatory binary operator at one grandchild position. Each root bucket chooses the position constrained by the most included schema rows, breaking ties in left-to-right order. Rows without a constraint at that position survive. The filter uses only the learned patterns, keeps the original candidate order and passes survivors to the same exact matcher.

The patch charges table preparation including pattern visits and table initialization. Each binary child-mask lookup charges three additional units for the guard test. A live guarded candidate incurs eight further units to access the common position and intersect masks. No family labels, new learned rules, budget changes or modified witness verifier enter the filter. The two patches here are experiment inputs. The primary solver and its checked-in evaluator retain their frozen code.

| Family | BASE | DROP | Original LEARNED | With filter | Difference |
| --- | ---: | ---: | ---: | ---: | ---: |
| Parallel | 636 | 790 | 543 | 568 | +25 |
| Serial | 584 | 740 | 552 | 574 | +22 |
| Normalize | 311 | 410 | 548 | 584 | +36 |
| Mirrored | 266 | 367 | 587 | 637 | +50 |
| Neutral | 88 | 109 | 109 | 109 | 0 |
| Irreducible | 32 | 41 | 41 | 41 | 0 |

Each table entry is the metered query work per input at the 200,000-unit reference budget, including independent final witness replay. All four coefficients within each family yielded these costs. All 24 reference witnesses and outputs remained identical. The 16-budget sweep retained every individual success/failure decision: BASE solved 220/384, DROP 208/384, and both learned variants 200/384. On 12 budget-limited learned attempts the returned prefix and witness changed. These failures and their work appear in the [408 paired learned rows](learned-pairs.csv). The passive diagnostic exactly reproduced all 1,224 primary rows. Both filtered rotations reproduced all 1,224 outcomes and metered accounts. The 816 BASE and DROP query rows are identical to the [original controls](../results/rows.csv). The paired table uses the original output and witness IDs.

| One pass over 24 reference inputs | Learning | Load and preparation | Query incl. replay | Total |
| --- | ---: | ---: | ---: | ---: |
| BASE | 0 | 0 | 7,668 | 7,668 |
| Original DROP | 9,130 | 3,675 | 9,828 | 22,633 |
| Trial DROP | 9,130 | 4,116 | 9,828 | 23,074 |
| Original LEARNED | 9,130 | 3,675 | 9,520 | 22,325 |
| Trial LEARNED | 9,130 | 4,116 | 10,052 | 23,298 |

The filter added 441 setup units and 532 query units on one reference panel. Its arithmetic-equivalence audit cost another 1,676 units in each arm, outside the query budget. The original and filtered learned arms each spent 1,564 units on independent final replay within the query total.

The [passive trace](reference-dispatch.csv) locates two different remaining costs. Each mirrored input opened 15 exact matches, all rejected for one matcher unit plus one cursor attempt unit each. Even removing every one of those 30 units on the recorded path would leave 190 of the original 220-unit gap against DROP. A subsequent investigation can measure the rest of the provider-opening and occurrence traversal cost. On normalization, a valid learned proof costs 103 verification units plus 103 replay units against 15 plus 15 for the primitive proof. That accounts for 176 of the 237 units between LEARNED and BASE on the observed witness. Making failed matches cheaper does not reduce those witness costs.

## Reproduce

Run `bash docs/research/prospective-distributive-coverage/dispatch-followup/reproduce.sh` from a checkout containing the public [primary result commit](https://github.com/carstenartur/Regelsuche/commit/dc39f6e0199d3f38fd8c82ac9a35230a83b0c66e) with Java 25, Maven, Git, tar and Python 3. The script verifies the complete source tree, extracts a fresh copy, applies the passive diagnostic, verifies its unchanged rows, then runs the [paid index patch](grandchild-filter.patch) in two oppositely ordered JVMs and checks both CSV ledgers. The diagnostic runs in a separate pass. No timing inference is drawn from instrumented execution.
