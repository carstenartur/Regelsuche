# Ablatable rule amplification implementation plan

**Goal:** Add a bounded, source-only runner for issue #730, with four independently executable profiles and honest post-freeze qualification.

**Architecture:** A new search authority composes the existing declarative matcher, exact polynomial quotient planner, concrete cancellation executor and #718 bridge. A separate experiment adapter freezes all native and separately named embedded SymPy candidates before opening qualification. Existing v2 characterization and V3/V4 product authorities retain their identities.

**Tech Stack:** Java 25, existing AST/guards/provenance, Maven controls, pinned embedded GraalPy/SymPy 1.14.0, checkout-owned Python reproduction verifier.

**Spec:** GitHub #730; `SymPyRuleAmplificationExperiment`, `RulePreparationPlanner`, `PatternTargetedLocalBridgeSearch`, `SafeRuntimeAdapter` and `GraalPySymPyRuntime` source audit at 1c695266b927daefb99b0fca7b755b64e2d90ffa.

## Global constraints

- No historical schema, selector, thresholds, defaults or fixture identities change.
- Formation sees only source, scalar domain, declared assumptions, complete visible inventory and bounded policy. No case ID, family, target or expected outcome.
- Profiles are cumulative but execute independently: structural direct; arithmetic AC; existing exact quotient preparation; local bridge last. Cheapest successful stage remains authoritative.
- Unknown, false and conflicting guards never authorize. No matrix AC, branch-sensitive operations or opaque CAS portfolio.
- Retain every attempted stage and failed/budget outcome. Logical work is complete at its declared granularity; additive observed exact work uses one run-owned cumulative authority. Source/projection/principal/bridge/SymPy interior work remains unavailable and matched total work stays blocked.
- No protected experiments or new comparative study runs. Root coordinates Gradle and publication.

## Tasks

1. Add `AblatableRulePreparationRunner` and canonical observation support in search. RED/GREEN controls distinguish direct/AC, exact preparation/bridge, guard failures, budget exhaustion and deterministic independent replay.
2. Add a versioned named-operation adapter in `regelsuche-math-sympy`, reusing the existing embedded runtime and pinned resource authority. Keep factorization source/default constructor byte semantics. Test input rejection, separate configurations, unavailable outcomes, and real bounded public controls.
3. Add `AblatableRuleAmplificationExperiment`: public three-family corpus; canonical plan, complete candidate freeze, then qualification. Test target-blind formation, tamper rejection, null result and staged coverage/work. Qualification must not choose an operation or candidate after seeing reference outputs.
4. Add local build/CLI adapters and a narrow two-host plus pinned-container comparison verifier. Bind exact plan/freeze/report bytes, fresh host identities and pinned image identity; never claim completed reproduction from local tests.
5. Document porting decomposition, guard/domain boundaries, operation identities, work granularity and remaining coordinated reproduction. Run focused Java 25 Maven/Python controls, inspect diff, commit and return exact receipts for independent root review.

Implementation is already authorized by the parent task. Execute inline without extra agents or a routine approval pause.
