# Checked quartic construction implementation plan

**Goal:** Connect general bounded binary-quartic coefficient synthesis to the
existing verified construction and cumulative-learning path.

**Architecture:** Keep V1 frozen. Add a V2 profile whose additional provider
projects exact source polynomials into the existing integer quartic engine,
renders independently verified factors, and uses the existing replay boundary.

**Tech stack:** Java 25, existing polynomial, search and learning modules, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-05-quartic-construction-design.md`.

## Tasks

1. Add failing integration contracts for a five-term quartic, source-bound
   replay, invalid domains, units, budgets and V1/V2 profile separation.
2. Implement the bounded exact adapter and profile-specific provider set; keep
   all work accounted and retain independent product verification.
3. Exercise learning and snapshot restoration with held-out expressions where
   the existing validation gate permits promotion; retain negative outcomes.
4. Run targeted Java 25/JUnit reactor tests and applicable regression checks,
   inspect the final diff, and publish a reviewable branch or PR if authorized
   and the available checks justify it.
