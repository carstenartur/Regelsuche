# Finite-difference discovery on the six public #383 sequences

On the public square-number TRAIN prefix, the production finite-difference domain found the Newton coefficients `[1, 3, 2]` in each of four campaigns. The model gives `1 + 3n + 2·binom(n,2) = (n+1)²` and reproduced the withheld continuation of that TRAIN case. The seeded grammar found the same model in two campaigns. The ordered grammar exhausted its 30 proposals per prefix before reaching that model. Every arm recorded zero confirmations across its 16 VALIDATION and TEST case slots.

| Four campaigns per arm | Production domain | Ordered grammar | Seeded grammar |
| --- | ---: | ---: | ---: |
| Proposals validated | 4 | 240 | 240 |
| Duplicate proposals | 0 | 0 | 11 |
| Rejected proposals | 0 | 240 | 238 |
| Proposals outside the grammar | 0 | 0 | 0 |
| Campaigns with no supported model | 0 | 4 | 2 |
| Generating-prefix term checks | 20 | 1,200 | 1,200 |
| Reuse checks on observed terms | 136 | 0 | 68 |
| Reuse checks on continuation terms | 72 | 0 | 36 |
| Confirmed TRAIN case slots out of 8 | 4 | 0 | 2 |
| Confirmed VALIDATION or TEST case slots out of 16 | 0 | 0 | 0 |

## Formation and reuse

Each former received only two public TRAIN prefixes, in corpus order, and their maximum difference order. The domain received the first observed term in its required placeholder holdout field. Its transition operator, objective and candidate extractor built the difference table. The evaluator with holdout access was never invoked. The production domain performed 64 observed-term subtractions across the four campaigns.

The shared grammar fixes the intercept to the first observed term and allows free initial differences from −3 to 3 at positive degrees. The final difference must be nonzero. At least two terms must remain in the highest-order difference row. Each observed prefix admitted 342 coefficient vectors under these rules. The two controls traversed or sampled that same grammar with 30 proposal slots per prefix. Seeded draws use replacement and retain duplicates. Production proposals encounter the identical grammar and generating-prefix checks. The allowance applies to proposals. Fitting, traversing the grammar and sampling take different amounts of computation.

All twelve candidate sets were frozen and hashed before any evaluation row was read by the evaluator. Reuse predicts every prefix and continuation term at its original index with the frozen polynomial. The retained result contains all 484 attempts and all 72 evaluation rows, including an explicit row for every no-output campaign and every prefix mismatch. The six cases were public when the protocol was registered. The comparison supports a claim about reuse on this panel. [Preregistration](https://github.com/carstenartur/Regelsuche/commit/85c2150586c68aa3436f6b17f84cb54bf5ff7eb9) · [Frozen plan](../research/benchmarks/candidate-independent/track-c-finite-differences/comparison-plan.json) · [Raw evidence](../research/benchmarks/candidate-independent/track-c-finite-differences/comparison-result.json) · [Companion linear recurrence experiment](track-c-recurrence-comparison.md)

## Reproduction

From the repository root on Java 25, run the following commands.

```sh
mvn -B -pl regelsuche-release -am '-Dtest=TrackC*ComparisonTest' -Dsurefire.failIfNoSpecifiedTests=false test
python3 scripts/verify-track-c-finite-differences.py
```

The release-module test regenerates the complete 76,024-byte artifact and rejects a changed corpus. The standalone Python verifier independently enumerates coefficient tuples, replays each seeded draw, reconstructs the finite differences, calculates every prediction with exact binomial arithmetic and reconstructs the full JSON. Its SHA-256 is `c12c85e3e67829ea467784613a811e3f381024c74738315e174f6e62cffed447`.
