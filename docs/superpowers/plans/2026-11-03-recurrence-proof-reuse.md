# Reuse universally checked recurrence-family steps

> **For agentic workers:** use superpowers:executing-plans task by task.

**Goal:** Apply a previously proved recurrence family without re-expanding its universally checked step identity on each query; keep source binding, nontriviality, input guards, paid initial-value evaluation and restart validation.

**Architecture:** `VerifiedFamily` is an immutable, privately issued mathematical authority, constructed only after anchor and symbolic-parameter checks. Instantiate its stored exact polynomial for the caller's bound transition, then use the existing initial-value checker to issue a concrete `VerifiedLaw`. Ordinary untrusted `Proposal` verification stays unchanged. The existing cumulative adapter calls this checked application path only for actual family objects; exact entries and baseline retain the original verifier.

**Stack:** existing Java, exact `Polynomial`/`Rational`, bounded work ledger, Maven/Gradle JUnit. No new solver, persistent schema, global cache or dependency.

**Spec:** design contract above; explanatory results belong in `docs/research/recurrence-family-transfer.md`.

## Constraints and review focus

- The merged persistence fix on main `8f051ce1` is retained; source tree `720c5351d7b57654f662c848279c17fd1caa244a`.
- Stored bytes never directly become proof authority. Loading must reconstruct and prove the family exactly as before.
- Fixed transition entries, dimension, degree, nonconstant specialization, canonical-text bound, initial-value limits and one request budget remain enforced.
- Results for changed initial values must be calculated afresh, not copied or cached.
- A concrete family instance is one law, not a complete invariant basis. No claimed runtime speedup without measurements.
- The concurrent lifecycle PR #1136 and its historical experiment/protocol/results are not edited or retuned.

## Plan

- [x] Add and execute an integration regression on unchanged production: a family application must be cheaper than replaying its full step identity, with the result independently audited through the original verifier.
- [x] Extract common bounded specialization and initial-value binding; add a typed checked-family application path that cannot accept a public forged proposal as authority.
- [x] Connect that path to the existing cumulative adapter, preserving exact-law fallback and all request cost accounting.
- [x] Test unrelated sources, offsets, varying rational/negative parameters, initial-state changes, nonlinear proof versus fitting, zero specializations, narrow budgets, immutable/concurrent reuse and actual restart behavior.
- [x] Re-run existing mathematical, recurrence, family, payload and expression tests; retain before/after outputs, correct for local environment limitations, and run fault injections.
- [ ] Verify final diff/source hashes, publish on current main without changing the other PR, request review and check exact-head CI.

## Ledger

- The prior unpublished payload fix is already merged and natively tested; it is not applied again.
- Existing complete-lifecycle evidence at 64 requests is negative for time/CPU despite lower logical work. This change addresses redundant work, not a claimed mathematical capability expansion.
- The container currently has Java 21 and no working external DNS. Native Java-25/JUnit/complete-CI qualification is not inferred from direct tests.

- The behavioral regression failed before production changes (602 application units versus 551 full-replay units). It passes after reuse (195 units).
- Direct tests and four compiled fault injections completed. Two combined commands hit the outer time limit; final suites completed separately. Payload regression bodies required local plain-Java assertion adapters, with a corrected int/long boxing comparison. None of this changes native project tests.
