# Bounded Mathematical Transfers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** Adopt useful finite methods from the November 24 collection update and complete the earlier 116/142 applicability review.
**Architecture:** Independent core producers and small verifiers, thin SDK facades, with a separate Primachsen chronology consumer.
**Tech Stack:** Java 25, Maven, JUnit; Python for independent research checks.
**Spec:** ../specs/2026-11-25-openai-math-follow-up.md

## Global Constraints
All binding constraints in the spec apply verbatim. Work starts from Regelsuche 44ce67edab538c5c5a69f992e75f3f222ec4fd72 and Primachsenraum 6af7837788d9ae00443fefaab09f527a1dc47ae4. Independent workers own disjoint source files. The controller alone edits shared audits, capability tables and strategies.md and creates commits. Serialize Maven builds that share a checkout.

## Review Focus
- A changed source or objective must not reuse an unrelated certificate.
- Rank-deficient, singular, zero and constant cases keep their documented meaning.
- A resource stop must not become equality, infeasibility, or a zero count.
- The original rational formula domain and field characteristic survive transformations.
- Greedy evidence uses strict earlier times; local finite success does not imply the universal deadline.

## Tasks
- [x] 1. Rational LP certificates: see task-1-brief.md; verify exact outcomes, normalize/sparsify Farkas witnesses, SDK and focused documentation.
- [x] 2. Operator plans: see task-2-brief.md; verify residual and workspace identities; evaluate operation/storage tradeoffs and retain honest negative findings.
- [x] 3. Rational formula and finite-field extensions: see task-3-brief.md; inspect first, then implement source-bound finite procedures with independent controls.
- [x] 4. Primachsen chronology: compile bounded canonical semantics to the existing CNF engine, compare to independently enumerated small models and the p=71 countermodel, document sunflower/Hall limitations.
- [x] 5. Update audit, capabilities and research strategies with evidence for each adopted/deferred method.
- [x] 6. Independent mathematical/code reviews and all affected module test classes. Publication and remote CI status are recorded in the pull requests.

Each implementation task writes a failing behavioral test, records the failure, implements the minimal general procedure, runs focused tests, and supplies a report. The controller runs integration and broader affected-module tests after integration. No modification to existing test outcomes or performance claims to manufacture success.
