# Comparison follow-up implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Complete implementation and verification without pausing between authorised reversible tasks.

**Goal:** Resolve the concrete correctness, usability, documentation and evaluation defects found in the November 10 comparison, and make remaining research and external validation limits explicit.

**Architecture:** Keep exact mathematics in the Java modules and expose it through the existing search, SDK and web interfaces. Repair the existing implementations before adding new abstractions. Retain independent verification and versioned experimental evidence.

**Tech stack:** Java 25, Maven 3.9.x, JUnit, existing browser JavaScript and Playwright infrastructure.

**Spec:** Carsten's request to fix the deficiencies identified in the preceding comparison; the acceptance inventory below records the concrete scope and distinguishes software defects from outcomes requiring external evidence.

## Global constraints

- Java 25 remains the implementation language for mathematical logic; no Python prototype and no new ULR module.
- Preserve independent proof replay, assumptions, parameter relations, input binding and numeric semantics. Never promote an empirical check to a formal proof.
- Keep public APIs source compatible where practical; any unsupported objective or extension path must be explicit.
- Use Maven for reproducible verification. Do not remove a legacy gate before its replacement preserves coverage.
- Preserve all existing historical benchmark inputs and results. New protocols have new names and include acquisition, rejected attempts, selection and verification costs.
- Do not claim measured speedup, human usability validation, external novelty or marketplace availability without evidence.
- No external outreach messages or publication submissions are part of this change.

## Review focus

1. Guarded or empirically validated rules must not become unconditional e-class equalities.
2. Exceptions and budget stops must remain observable and leave a valid graph.
3. Non-additive cost objectives must not silently use operator count.
4. Browser entry routes, deep links and keyboard navigation must retain access to all existing views.
5. A learning candidate that wins one unrepresentative task must not be promoted over a regressing realistic workload.

## Task 1: Complete equality-saturation contracts

**Owned files:** `regelsuche-egraph/**`, equality-saturation-specific source/tests under `regelsuche-search/**`, `docs/equality-saturation.md`.

- [ ] Reproduce the fixed extraction objective, swallowed rule exceptions and coarse node-budget checks with focused tests.
- [ ] Add an explicit extraction objective contract, supporting existing objectives accurately or explicitly rejecting/falling back where an exact extractor is unavailable. Default behavior remains compatible.
- [ ] Bound work between rule applications and report the budget stop. Preserve graph invariants even if one RHS cannot fit.
- [ ] Separate non-applicability, unresolved conditions and rule failures; retain bounded structured diagnostics and surface them through the strategy.
- [ ] Replace the synthetic derivation claim with truthful provenance; provide replayable concrete witnesses where supported and clearly label missing explanations. Do not invent a proof for congruence closure.
- [ ] Extend tests for guarded/custom rules and alternative representatives; document the actual native/conditional/AC/proof boundary.
- [ ] Run the affected Maven reactor tests and commit this isolated change.

## Task 2: Repair the public entry path and documentation

**Owned files:** README and user/architecture/SDK documentation except `docs/equality-saturation.md`; external SDK examples; static documentation demo assets.

- [ ] Replace outdated claims that no SDK release exists with the verified 0.5.2 release path, while distinguishing unreleased main features.
- [ ] Provide one preferred external SDK consumption route and a runnable external Maven example using released artifacts. Verify exact coordinates and archive layout.
- [ ] Correct architecture, module and build descriptions against source (exact rationals, existing factorisation/Groebner facilities, optimisation SDK, Maven migration).
- [ ] Add a concise English entry page and a self-contained browser-viewable recorded example using real reproducible evidence. Link the three primary workflows and honest proof/capability boundaries.
- [ ] Prepare focused human-DX tasks and outreach-ready demo links without claiming a human trial was conducted or sending messages.
- [ ] Verify links and run the external example or record a concrete environment limitation. Commit this isolated change.

## Task 3: Make learning promotion representative and measurable

**Owned files:** `regelsuche-learning/**` and new, versioned learning evaluation documentation. Do not edit existing historical reports.

- [ ] Reproduce the mismatch between single deep-offset promotion and the actual mixed stream.
- [ ] Replace the unrepresentative promotion workload with a deterministic, independent mixed workload covering shallow, compound, deep, negative and already-solved tasks.
- [ ] Keep quality non-regression and full acquisition/verification/failed-attempt costs in the decision. Add adversarial tests showing that one specialised win cannot conceal a regression.
- [ ] Add a prospective versioned reproduction command/report with input separation and amortisation clearly reported; measure it if the environment supports the run.
- [ ] Inspect the representation audit cost and implement a smaller exact certificate verifier only if completeness and rank/solution coverage remain independently checked. Otherwise record the exact remaining proof obligation.
- [ ] Run affected Maven tests and commit this isolated change.

## Task 4: Make the workbench task-oriented and evidence-aware

**Owned files:** web resources and app UI tests; inventory adapter and corresponding tests.

- [ ] Add three clear entry routes: explore an expression, learn/compare, optimise a computation; retain all advanced views and deep links.
- [ ] Present result, assumptions, evidence strength and next action together, with accurate distinctions between replay, exact checks, solver evidence and kernel checks.
- [ ] Harden the legacy inventory adapter so stored empirical/conditional rules cannot silently enter unconditional rewriting; reuse the existing authorisation boundary or reject unsupported records with diagnostics.
- [ ] Exercise navigation, a successful result, a budget/failure state and a conditional/weak-evidence state using existing browser/JUnit infrastructure.
- [ ] Commit and integrate with the other tasks; run a combined affected reactor and review.

## Task 5: Resolve remaining architecture and comparison items

- [ ] Inspect the current Extension SPI v2 compatibility and Maven migration acceptance criteria; repair concrete public-entry inconsistencies and executable gaps without deleting unported verification.
- [ ] Evaluate a narrow optional Symja baseline for the same supported input families, with isolated dependencies and explicit timeouts. Implement it if it can be reproduced without contaminating the runtime.
- [ ] Maintain a final inventory mapping every finding to changed files, verification, or a specific external/research dependency. General CAS breadth, independent human studies and universal learning improvement are not completion claims a code change can establish.
- [ ] Review the complete branch and open a small number of coherent PRs with fresh test evidence.
