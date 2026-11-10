# Preserve incomplete matching in equality saturation

## Goal and evidence

Correct an observed false-fixpoint result in the existing concrete EqSat adapter.
The actual Java 25 / JUnit 6.0.3 regressions show that nine commutative operands or
the default 10,000-branch limit produce an inconclusive matcher outcome, which the
adapter currently reduces to a normal miss and `saturated=true`. Both dirty and
full-scan modes fail. The branch example finds the missed `F + 0 -> F` match with
20,000 branches. This is an outcome-propagation repair, not native AC matching or
a complete extraction-proof implementation.

The user's continuation authorizes this repair. The design choices below are
implementation decisions, not a claim of separate user approval.

## Design decisions

- Keep the native exact base-pattern path, graph budgets, extraction objective,
  candidate ordering and existing rule eligibility checks unchanged.
- In the concrete path, use `EquivalenceAwarePatternMatcher.matchDetailed` only
  when `rule.getClass() == PatternRewriteRule.class`. Use the same source,
  recognition profile and default matcher limits as the old boolean call.
- Preserve `MATCHED`, `NOT_MATCHED` and `INCONCLUSIVE`. A matched base pattern
  instantiates its target from the returned bindings after the existing
  assumptions stage; it does not repeat matching through `apply`.
- Subclasses and arbitrary rules retain their actual
  `matches -> assumptions -> apply` callbacks and exception-stage attribution.
  Hidden limits inside opaque boolean callbacks cannot be inferred.
- Immediately charge `RetainedOperation.work(attempt.visitedBranches())` exactly
  once after every returned detailed attempt, before inspecting its status.
  Matcher exceptions already settle their branch debit; do not debit in a caller
  `finally` block. Internal algebraic work is not charged again.
- Add `SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE` and
  `SaturationStats.Reason.MATCHER_LIMIT`. Keep the event record shape unchanged:
  record the actual limit code in its bounded message, `MATCHES` as its stage,
  and an empty exception type. Expected limits are not fabricated exceptions.
- Aggregate incomplete-attempt counts across the run, including after the event
  sample is full. Continue other candidates and rules after a matching limit.
- At a no-progress exit, actual rule failures retain `RULE_FAILURES` precedence;
  otherwise any inconclusive owned match yields `MATCHER_LIMIT` and
  `saturated=false`. Only an absence of either permits `FIX_POINT`.
- Existing node/iteration stop reasons keep their precedence, with matching
  diagnostics still retained. Do not assert that later passes discharged every
  earlier incomplete attempt without evidence.
- The new enum values are an intentional additive public contract. Existing
  event/result constructors and record components remain unchanged. Current
  strategy consumers already expose the records without reinterpreting them.
- Do not add a recognition-depth status: this low-level matcher does not consume
  the separate theory/provider recognition-depth settings.

## Implementation task

Modify `EqualitySaturation`, `SaturationDiagnostics` and `SaturationStats`; extend
the focused `EqualitySaturationMatchLimitTest` and relevant existing contract or
strategy tests where needed. Update `docs/equality-saturation.md` with the actual
outcomes and priority. No new module, dependencies, CI relaxation or Python math.

1. Retain the four genuine failing regression executions and their matcher
   preconditions, then implement the smallest detailed-outcome adapter.
2. Cover ordinary miss, successful AC rewrite, subclass/custom callback order,
   independent progress after a limit, event truncation, stop-reason precedence
   and exactly-once returned branch accounting. Reuse relevant existing native
   matching/strategy tests rather than duplicating their implementation.
3. Run the focused current-source Java 25/JUnit suite, preserving real commands
   and raw results. The local missing Netty BOM is an existing Maven blocker;
   supplemental tests do not constitute canonical CI.
4. Independently review the complete source change and combine the qualified
   repair with the ongoing mathematics-correctness PR before one new automatic
   CI of its corrected head. Keep the current failed quality-gate evidence.

## Acceptance and limits

No owned `INCONCLUSIVE` match may be reported as a proven ordinary miss or a
clean saturation fixpoint. Known applicable rules must still run, exact native
matching and opaque callback dispatch remain intact, and work accounting must
retain its delegated ownership. A limit diagnostic is not an extraction proof,
an unrestricted AC-completeness claim, or a new hard deadline for arbitrary Java
callbacks. Public documentation must preserve these limits.
