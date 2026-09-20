# Frozen modular-program transfer v1 (#1026)

Base: integration 7f0d847475b056715dd357b1d508fce04ce4370f. This follow-up does not change the integration PR or the existing learner, output matcher, arithmetic replay, or historical #1025 records.

## Question and controls

Learn a syntax hypothesis from two target-blind TRAIN searches, freeze its exact serialized bytes and SHA-256, then apply it to source-only TEST programs. TRAIN and TEST are committed separately before execution. No TEST target is supplied. Public development examples already informed the implementation; this is a process-separated, preregistered small evaluation, not an independently blinded benchmark.

Each input has exactly one modular power with a product exponent. The unchanged AstRewriteTransport with the two general product-composition rules enumerates its two possible one-step rewrites. Every resulting edge is independently checked by the unchanged ModPowCompositionReplay. Require exactly two returned primitive steps for this deliberately bounded domain; otherwise abort, do not claim exhaustion. No learner-specific or Pocklington-specific rule is put in that inventory.

TRAIN selects the minimum DAG-cost state from source plus all verified primitive successors without a known target. It forms TypedPatternGeneralizer examples only for strict savings. Two examples form one candidate; no alternative pattern, tuning, or commutativity normalization is added after TEST. Sample assumptions and canonical source/target witnesses are retained. TEST receives explicit concrete premises; sample premises cannot authorize a new application.

SHUFFLED uses the same TRAIN rows with the independent retained exponent replaced by an unrelated variable of the same bit profile, breaking reuse. It must produce no saving and hence no candidate. This is a relationship-breaking control, not a random permutation that leaves the examples mathematically equivalent.

TEST includes renamed symbols, reordered outputs, an extra untouched output, reversed product factors, and a no-reuse negative. No expected target forms appear in TEST. Both full primitive enumeration (NO_LEARNING) and learned proposals are evaluated with the same independent replay and cost. The source is always an available fallback.

## Cost and conclusions

Reuse the existing #1025 exponent-bit TREE/DAG proxy after an injective role-to-canonical-symbol renaming, solely for scoring. It is not runtime, total CPU work, or bit complexity. Additional constant outputs cost zero under this historical proxy. Report primitive generated/replayed candidates, learned assignment attempts and matcher steps separately; do not combine incomparable counters or claim speedup.

NO_LEARNING is the complete one-step primitive optimum, not the unoptimized input. A one-step macro cannot beat it. Report optimum parity, source-cost savings, and failure to transfer separately. This explicitly corrects the impossible strict-cost-superiority condition in the original issue without weakening the baseline. No fixed-budget speed claim is evaluated in v1.

GREEN (structural transfer only): frozen artifact before TEST, all four positive rows obtain a replay-approved learned proposal with strict source-cost savings and primitive-optimum parity, extra outputs preserved, negative/shuffled controls clean. YELLOW: a nonzero proper subset of positive rows transfers correctly. RED: no positive transfer, incorrect approval, control violation, hash mismatch, or TEST-informed tuning. Hash mismatch aborts before TEST is read. Technical failures are not scientific results. Never rewrite the failed reversed-order case away.

## Execution and implementation plan

1. Commit this plan, train.json and test.json before any official run.
2. Add a small app-level research runner, reusing the existing typed learner, exact output matcher, primitive transport, replay and cost. No production defaults change.
3. Test data validation, model encoding/reconstruction, corrupt hash rejection before TEST access, missing premises, and frozen-model reuse. Observe failing tests before implementing the missing runner behavior.
4. Run TRAIN in a process whose only corpus argument is train.json; write a new model file without overwriting an earlier artifact. Close the process. Record file SHA-256 externally and commit the artifact before the official TEST invocation.
5. Run TEST in another process using the already frozen model and explicit expected SHA-256. Serialize actual proposals, conditions, per-case outcomes and all diagnostic counters. Preserve any negative result.
6. Add concise evidence/reproduction instructions and a PR separate from #1034. Focused local tests must state the actual JDK and do not substitute for the normal complete Java-25 repository CI.

The output remains a research hypothesis plus independently checked conditional applications. No automatic rule promotion, inferred general premise theorem, novel exponent law, general optimizer superiority or completion of the broader #1026 scope is claimed.
