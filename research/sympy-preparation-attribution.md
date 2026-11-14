# How much did preparation add on the two public SymPy panels?

On the original eight positive inputs, exact matching authorized three applications
under the supplied assumptions. Declared theory authorized a fourth, the reordered
Pythagorean sum. The local search found four more consequences, each conditional
on extra nonzero premises. Its incremental gain under the original input assumptions
was zero. In the separate guard panel, local search authorized the two hidden
cancellations whose sources explicitly supplied the missing premise. The
[measurement protocol](sympy-preparation-work-accounting.md) fixes how the
retrospective accounting extends the already public experiment.

## Coverage

All four profiles attempted the three independent principals on eleven main
sources and nine guard sources, giving 132 and 108 decisions respectively.
Counts below are cumulative. A consequence counts as authorized when its
principal and expression match the case after candidate freeze and its premises
are supported by the input. Conditional counts cover matching consequences with additional
obligations. The guard panel contains nine distinct premise controls.

| Enabled profile | Main authorized / 8 positives | Main conditional | Guard authorized / 9 controls | Guard conditional |
| --- | ---: | ---: | ---: | ---: |
| Exact | 3 | 0 | 1 | 0 |
| + declared theory | 4 | 0 | 1 | 0 |
| + registered exact specialists | 4 | 0 | 1 | 0 |
| + bounded local search | 4 | 4 | 3 | 2 |

No exact specialist was registered for any of the three principals. One shared
preparer, `ast_cancel_division_factor`, accounts for all positive preparation
paths. Its seven main-panel applications comprise three for trigonometry,
two for difference of squares and two for telescoping. Each of the four new
consequences requires `a != 0`. Three also require `b != 0`. In the guard
panel, two applications of the preparer are authorized and two conditional.

| Principal | Main gains from exact / theory / specialist / local under source assumptions | Additional conditional local results | Guard gains from exact / theory / specialist / local under source assumptions | Additional conditional guard results |
| --- | --- | ---: | --- | ---: |
| Pythagorean | 1 / 1 / 0 / 0 | 2 | 0 / 0 / 0 / 1 | 1 |
| Difference of squares | 1 / 0 / 0 / 0 | 1 | 0 / 0 / 0 / 1 | 1 |
| Rational telescoping | 1 / 0 / 0 / 0 | 1 | 1 / 0 / 0 / 0 | 0 |

All three main-panel near misses stayed negative. At the final profile, 25 of
33 main-panel attempts exhausted the bounded closure. Of 27 guard attempts,
20 exhausted the closure and two rational attempts were unsupported because
their necessary premise was absent or contradicted. The explicit-zero guards
authorized no application. Neither panel produced an invalid replay, false
positive or budget-inconclusive decision.

## Work of the enabled stages

Each count covers one complete pass of its panel, including unsuccessful
attempts. The two columns refer to the main and guard panels respectively.
Direct matching ran on all rule/source pairs. Declared theory ran only for
a principal with additional theory enabled. Registry checks produced zero
actual specialist dispatches. The final stage used the original budget of
depth 3, 128 visited states, 1,024 generated transitions, eight primitive
steps per path, 160 expression nodes, 128 successors per state, 32 match
results, 5,000 matcher steps and 2,500 pattern branches.

| Stage / measured operation | Main | Guard |
| --- | ---: | ---: |
| Exact stage, attempts / matcher steps / structural comparisons / executor calls | 33 / 33 / 73 / 3 | 27 / 27 / 57 / 1 |
| Theory stage, attempts / matcher steps / branches / structural comparisons / executor calls | 10 / 10 / 11 / 34 / 1 | 9 / 9 / 6 / 30 / 0 |
| Exact specialist stage, registry checks / dispatches | 29 / 0 | 24 / 0 |
| Local stage, attempts / discovered states / expanded states / generated transitions | 29 / 68 / 64 / 51 | 24 / 36 / 32 / 18 |
| Local formation, matcher steps / branches / structural comparisons | 80 / 24 / 172 | 36 / 6 / 65 |
| Local formation, preparation engine calls / returned transitions / principal replays | 64 / 51 / 4 | 32 / 18 / 4 |
| Bridge verifier, invocations / hashes checked | 8 / 8 | 8 / 8 |
| Bridge verifier, terminal matcher steps / preparation engine calls / returned transitions / principal replays | 8 / 14 / 20 / 8 | 8 / 8 / 8 / 8 |
| Outer coordinator, additional terminal schema match steps | 4 | 4 |
| Raw parsed preparation ASTs sized / peak node increase over the raw source before principal replay | 51 / 0 | 18 / 0 |

The producer verifies each accepted bridge internally. The coordinator verifies
it again before using it. Re-executing the whole profile for reproducibility
consumes a separate pass, excluded from the table. The operations count
actual matcher and engine calls and their returned work. Each engine call
may inspect multiple AST positions. The units measure work at those interfaces.

The local search on the main panel explored 17 transitions for each principal.
It expanded 20 trig states, 22 polynomial states and 22 rational states. Its
four conditional paths had depths 1, 2, 2 and 2. Among all eight returned
main-panel expressions, canonicalized endpoint node-count changes ranged from −16 (twice
obscured Pythagorean identity) to +4 (direct rational partial fractions).
The JSON retains per-principal counts, authorization, selected stage, condition
sets, per-source decisions, and verifier observations including zero-gain
principals. Each source row binds its outcomes to the canonical decision digest.

## External comparison and reproduction

The independently pinned SymPy 1.14.0 / mpmath 1.3.0 reference executed
`fu`, `factor` and `apart(expr, n)` on every original source. The prespecified
family operation returned the expected expression for all eight positive inputs.
The external outputs do not certify nonzero conditions lost through cancellation.
The three operations also processed every control. In particular, `apart`
expanded the step-two rational control into a valid consequence outside the
step-one principal. Regelsuche retains the primitive derivation and its
denominator obligations. The external results contain the expression and
declared input domain. Its public API exposes no comparable primitive work
ledger. [Named operations and results](../pattern-targeted-local-bridge-search.md#external-operation-results-for-the-three-family-pilot).

The original decision reports at `ffa34108fa0c458cc4735c4b7e4cf4b8e6ec6d06`
have SHA-256 `06aa04236b7cb4c7cd32ad47f8c94687990de9377113ea0b34f4f18f76002a72`
and `1526ad11d9e8e44fd45e32fb97bd9e61736fa2627ac7bfb8d1a123b0b9020837`.
Their hosted checkout reproduced the bytes extracted from the final pinned
runtime image. [Hosted verification](https://github.com/carstenartur/Regelsuche/actions/runs/43568182309/job/132378150429).

On a clean checkout with JDK 25, run

```sh
mvn -pl regelsuche-experiments -am -Dtest=PreparationAblationTest,PreparationGuardPanelTest,PreparationStageAttributionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The three JSON reports are under
`regelsuche-experiments/target/reports/preparation-ablation/`.
`stage-attribution.json` adds the measured formation and verification
counters. Reports include the checkout revision. Compare reproduction bytes
at the same revision. The two small, public panels support the qualified
applications above. They provide no population estimate or cross-engine
work ranking.
