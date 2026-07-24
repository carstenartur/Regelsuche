# Candidate-independent reusable-macro replay foundation

## Purpose

This slice establishes the production replay boundary for the
`reusable-search-macros` challenge frozen in issue #383. Before a macro may be
learned, generalized or compared with a baseline, every TRAIN trace must be
reproducible through the current production rewrite graph using exactly the
versioned abstract operation order retained in `macro-primitives/v1`.

Replay success is necessary formation evidence. It is not yet evidence that a
generalized macro improves held-out search.

## Frozen TRAIN traces

The corpus contains four formation-only replays:

| Trace | Source | Target | Abstract operation sequence |
|---|---|---|---|
| `case-13-trace-1` | `(x+1)^2` | `x^2+2*x+1` | power expand, distribute, collect |
| `case-13-trace-2` | `(y+2)^2` | `y^2+4*y+4` | power expand, distribute, collect |
| `case-14-trace-1` | `(x+x)-x` | `x` | collect, subtract cancel |
| `case-14-trace-2` | `(y*1)+0` | `y` | multiply identity, add zero |

Targets and primitive sequences come only from the TRAIN `formationInput`.
Held-out evaluation tasks are not represented by the replay API.

## Production replay contract

`CandidateIndependentMacroReplayAdapter`:

1. accepts the frozen mapping from abstract operations to current production
   rule IDs;
2. rejects profile entries whose concrete rule is absent from the production
   `AstRewriteTransformationEngine` inventory;
3. runs a deterministic bounded breadth-first replay;
4. permits multiple local AST rewrites inside one abstract phase, for example
   repeated distribution;
5. allows a concrete rule mapped to more than one abstract operation only when
   the resulting phase assignment follows the frozen sequence;
6. rejects generated side conditions unless they are covered by the trace's
   declared assumptions;
7. retains the concrete rule path, assigned abstract operations, compressed
   operation sequence, expression path and explored-state count;
8. emits an ordinary `SuccessfulTransformationPath` only for a complete replay.

The current production graph reproduces all four TRAIN traces. Representative
concrete paths include multiple distribution steps for the polynomial traces
and two separate canonical-normalization applications assigned to the collect
and subtract-cancel phases of `case-14-trace-1`.

## Verification

The focused JUnit contract is:

```bash
./gradlew :app:test \
  --tests de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapterTest
```

Root `check` and `fullCheck` execute the same test through the ordinary project
lifecycle. The test requires all four frozen traces, rejects a reordered
abstract sequence and rejects an invented production rule.

## Claim boundary

A green replay result establishes that the frozen TRAIN evidence can be
reproduced using the declared production primitive profile. It does not yet
establish:

- a generalized macro identity;
- held-out reachability;
- lower search cost than the primitive baseline;
- correctness under undeclared assumptions;
- cross-family transfer;
- external mathematical novelty;
- completion of the reusable-macro challenge or publication readiness.

The next slice must learn bounded macros only from these replayed TRAIN paths
and evaluate baseline and macro-enabled search on every frozen task under
identical inputs, targets, primitive inventory, strategy and budgets.
