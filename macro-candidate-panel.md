# Canonical reusable-macro candidate panel

## Purpose

`regelsuche.macro-candidate-panel/v1` evaluates every macro formed from the frozen
TRAIN replay surface against the same twelve-task downstream stream. It closes
the evidence gap between testing only the production-selected candidate and
retaining a reproducible comparison panel.

The panel is descriptive. It does not replace or reselect the production
candidate after held-out outcomes are visible.

## Authoritative command

```bash
./gradlew verifyMacroCandidatePanel
```

The repository-owned lifecycle:

1. independently verifies `paired-task-utility/v1` and its frozen downstream
   stream;
2. repeats TRAIN-only formation before reading either evaluated input;
3. requires the three formed candidates and all replay evidence to match the
   paired-utility formation ledger;
4. reuses each verified primitive baseline once for all candidate comparisons;
5. evaluates every candidate with baseline plus exactly that one candidate;
6. retains all twelve rows for all three candidates, including unchanged,
   unsupported, regressing and never-selected candidates;
7. records exact macro usage counts rather than inferring use from formation;
8. executes the panel twice and requires byte-identical canonical JSON;
9. validates a strict Draft 2020-12 schema and every nested semantic hash;
10. independently recomputes outcomes, regressions, usage, resource differences,
    aggregate counts and physical execution accounting;
11. rejects candidate omission, candidate substitution, task reordering,
    baseline drift, usage inflation, post-hoc reselection and publication
    overclaims.

## Formation and information boundary

The Java runner reads only the frozen case corpus and primitive profile before
formation. It completes `adapter.form(traces)` before reading the downstream
stream or the already evaluated paired-utility artifact.

Every candidate is therefore formed from the same four TRAIN traces used by the
production exact-one evaluation. Held-out tasks, targets, baseline results,
candidate-enabled results and outcomes are unavailable during formation.

The runner independently compares the re-formed candidates, atomic steps and
replay evidence with the retained formation ledger before beginning panel
execution.

## Shared baseline contract

The baseline for each task is taken from the already verified
`paired-task-utility/v1` artifact. The immutable baseline handle is bound to the
complete frozen task value. Reusing it for another task is rejected, and a
baseline containing any learned macro rule is invalid.

This avoids repeated primitive searches while preserving the exact baseline
used by the production candidate. For the production-selected candidate, every
candidate-enabled search result, outcome, regression flag and resource delta
must be identical to the existing paired utility row.

## Candidate coverage and observed use

The canonical panel contains:

- exactly three formed candidates in stable macro-ID order;
- exactly twelve candidate-enabled task rows per candidate;
- exactly one enabled candidate ID in every row;
- an explicit `macroUsageCount` and `exercised` flag per candidate;
- top-level exercised and unexercised candidate counts;
- explicit outcome counts and correctness-regression counts per candidate;
- complete candidate search paths, rules, terminal outcomes and resource use;
- aggregate counts over all 36 candidate-task evaluations;
- physical accounting for one formation, twelve shared baselines and 36
  candidate-enabled searches.

A formed candidate can be evaluated on every frozen task without being selected
by best-first search on any task. Such a candidate is retained with
`exercised: false` and `macroUsageCount: 0`; it is not omitted and is not
misreported as `CANDIDATE_NOT_FORMED`.

The production-selected candidate must be exercised because the existing
paired-utility contract already establishes its measured use. Alternative
candidates are allowed to remain unexercised, which is itself a negative held-out
result.

## Decision boundary

The production selection remains the TRAIN-only policy recorded by
`paired-task-utility/v1`. The panel uses the fixed policy:

`DESCRIPTIVE_PANEL_DOES_NOT_RESELECT_PRODUCTION_CANDIDATE`

Consequently, a held-out alternative with a more favorable observed result does
not silently become the production candidate. Such comparisons may inform a
future preregistered selection study, but not this frozen evaluation.

## Retained artifacts

```text
build/reports/macro-candidate-panel/
  first/
    raw-panel.json
    macro-candidate-panel.json
  second/
    raw-panel.json
    macro-candidate-panel.json
  verification/
    verification.json
```

## Claim boundary

A green run establishes reproducible evaluation of all currently formed macro
candidates on one independently frozen task stream, with exact production-run
parity for the selected candidate and explicit negative evidence for unused
alternatives. It does not establish:

- complete lifecycle amortization;
- superiority of the production selection policy;
- statistical generalization beyond the frozen panel and stream;
- formal theorem proof;
- external mathematical novelty;
- publication authorization.

Those claims remain governed by their separate evidence and review gates in
#384 and related issues.
