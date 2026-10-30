# Derived state repair and performance

## Specification

Fix PR 1119's new complexity hotspot without changing the quality policy. Replace
quadratic counter partition refinement with linear period analysis over the exact
observation word. Reversible updates use the shortest global period; insertion-only
updates use the smallest prefix plus periodic suffix. The existing independent
checker and runtime count guards remain authoritative. No observation/update
templates, new dependencies, or separate test frameworks.

For a material application improvement, profile the actual constellation search.
If exact root-isolation polynomial arithmetic dominates, add bounded fixed-point
sign evaluation for this domain's nonnegative polynomial, with a proved rounding
enclosure and exact fallback. Preserve certificate contents, search decisions and
full legacy reference replay. Keep generic state mathematics in Regelsuche and
the constellation polynomial arithmetic in Primachsenraum. Expose a direct bounded
sign path as a separate baseline so arithmetic gains are not attributed to state
synthesis. Existing PRs are updated; no stacked PRs or merges.

## Task 1: Linear state derivation and CI gate

Interfaces: CounterStateSynthesis's existing Specification, Plan, Derivation and
verifier. Add JUnit tests for bounded linear work on support with 8192 counts and
minimal state counts against exhaustive finite-word/lasso enumeration, including
boundary cases and both update policies. Watch the original implementation fail.
Implement prefix-function period analysis and focused helpers below the hotspot
threshold. Keep public API and verifier semantics. Run the 706+ search tests and
the real version-0.1.10 AI Knowledge complexity gate locally, without exceptions
or baseline changes. Expected: linear charged work, all tests green, no hotspot.

## Task 2: Profile and accelerate exact sign decisions

Interfaces: the real CertifiedAdmissiblePrimeConstellationBound and optimizer;
legacy exact evaluator remains the oracle and independent certificate check.
Profile the existing path before modifying it. Add failing JUnit equivalence,
rounding-bound, exact-fallback, endpoints, sparse-degree and precision tests.
Implement a downward-rounded Horner value at bounded precision and its analytical
error enclosure; decide a sign only outside the enclosure, otherwise use the
original exact numerator. Reuse validated coefficients per isolation. Apply to
root isolation and opt-in bounded search; make all complete certificates and
logical ledgers equal to the exact reference. Expected: tests pass and JFR confirms
work is removed from the measured hot path.

## Task 3: Fair evidence, review and PR updates

Keep the previous six universes and full legacy replay. Compare exact direct,
bounded-sign direct, native incremental and generated incremental paths in fresh
JVMs, reporting every case and all setup/check/replay/memory costs. Also report
charged derivation work across fixed bounds; distinguish preparation improvement,
arithmetic improvement and total application improvement. Preserve the previous
evidence as v1. Update docs and existing PRs. Run appropriate JUnit suites, real
complexity/CI gates, and one fresh whole-change review; address material findings
with regression tests. Do not claim an overall speedup beyond the measurements.

## Review focus

Finite-word boundary wildcards and minimality; malicious plan data; work-budget
charging; strict rounding inequalities and ambiguous zero; exact reference replay
remaining independent; attribution and repeatability of performance evidence.
