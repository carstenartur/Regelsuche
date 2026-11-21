# Frozen synthetic proof transfer results

The learned order reached all eight provable FINAL cases: six witnessed ring
equalities and two natural induction statements. All six arms left the two
impossible equalities and the induction case missing its base unconfirmed.
The learned order tied the hand-written selector on reach and target work.
With acquisition charged, its 55 proposals and 71 checker calls exceeded
the hand-written arm's 13 proposals and 21 checker calls. This public
synthetic study does not support an overall efficiency improvement.

The [protocol](protocol.md) was committed at `abf2aba070`. Code for all arms,
[TRAIN records](train.json) and the [plan](plan.json) were committed at
`e4059fa7b6` before VALIDATION and FINAL execution. The evaluator required
the same frozen plan digest in both invocations. The plan records the
training evidence hash and the priorities learned from all 42 training
proposals. Validation produced no edits or tuning before FINAL.

| Arm | VALIDATION reached / 11 | Proposals | Checker calls | FINAL reached / 11 | Proposals | Checker calls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Learned, target work | 8 | 12 | 20 | 8 | 13 | 21 |
| Fixed grammar order | 8 | 31 | 39 | 8 | 33 | 41 |
| Seeded random valid order | 8 | 20 | 28 | 8 | 22 | 30 |
| Exhaustive grammar | 8 | 44 | 52 | 8 | 47 | 55 |
| Hand-written selector | 8 | 12 | 20 | 8 | 13 | 21 |
| Ordinary native enumeration | 2 | 54 | 56 | 2 | 57 | 59 |

For a cold-start comparison add **42 proposals and 50 checker calls** to the
learned arm, once per independently charged deployment. For one deployment
serving both partitions, charge training once overall. A proposal-level call
checks a witness or induction candidate. A formed proof consumes an
additional call for replay against the recipient. These counters do not
measure the internal primitive operations of normalization or wall time.
The ordinary witness search has its existing general-purpose seed order and
the same six-trial ceiling. Its two reached cases were induction proofs.
The reported counts retain every rejected trial and the first accepted
witness or leaf IDs. No numerical sample or incoming status is accepted as
a mathematical proof.

The full [validation](validation.json) and [final](final.json) records include
the source obligation, exact recipient, assumptions, arm, candidate, terminal
status, proof-root and instance/conclusion hashes. Formation gets the
existential statement without its witness. For induction the formation
obligation has an unrelated neutral equality as its goal; the designated
quantified receiver is checked after the candidate is bound.

## Reproduction

From a fresh checkout with the repository's supported JDK/Maven, run
`mvn -pl regelsuche-benchmarks -am -Dtest=ScopedProofTransferStudyTest -Dsurefire.failIfNoSpecifiedTests=false test`.
The replay test generates TRAIN, VALIDATION and FINAL in a temporary directory
and checks the committed bytes and accepted proof roots. For direct separate
invocations use `de.regelsuche.benchmarks.ScopedProofTransferStudy` on the
benchmark module's runtime classpath with these arguments:

```text
train <fresh-directory>
validation <same-directory> sha256:d3adcb985e3a61f07f8a33c410893d76c7e9750bb41e8857da638b2ee6e053f7
final <same-directory> sha256:d3adcb985e3a61f07f8a33c410893d76c7e9750bb41e8857da638b2ee6e053f7
```

The witness leaf uses the existing exact rational polynomial normalizer;
the induction plan checks the declared zero and successor premises and
the exact receiver. The separate real Lean tests exercise representative
witness/induction kernel rebuilds. The study rows do not each carry an
external Lean compilation. Independent historical custody and the separate
Brahmagupta residual-composition comparison are outside this protocol.
