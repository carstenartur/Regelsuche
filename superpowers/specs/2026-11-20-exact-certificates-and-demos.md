# Exact certificates, visible demonstrations, and research consolidation

## Accepted goal

Carsten requested implementation of the opportunities and documentation repairs
identified in the 2026-11-20 audit of Regelsuche, Primachsenraum, Sandbox and
`openai/math`. This specification records that authorization and the boundaries
of the mathematical claims. An open conjecture is a research target, not an
acceptance criterion that can be declared proved by implementation alone.

## Required outcomes

1. Exact rational Bernstein bounds on finite rational boxes, with subdivision,
   source-bound certificates and an independent verifier. Exact symmetric Gram
   positivity certificates must handle singular matrices and check polynomial
   reconstruction before asserting a polynomial inequality.
2. A Hunter spanning-tree survivor lower bound for activated congruences,
   using exact CRT intersection counts and a source-bound independent verifier.
   A small typed proof DAG must compose independently checked finite leaves and
   justified deductions. It must reject changed sources, cycles, invalid
   references, incomplete certificates and unjustified conclusions.
3. Genuine Workbench demonstrations of Hall deficits and Montgomery schedules;
   the pair-reduction demonstration must execute a large reduced calculation
   and label the unexecuted quadratic comparison as an operation count.
4. General mathematical proposal logic belongs in the Java mathematical core.
   The SDK preserves Java type/domain semantics and translates typed inputs.
5. Documentation distinguishes current source, released SDK 0.5.3, Workbench
   support and Sandbox integration. New algorithms have contracts, limits,
   executable examples, tests and entries in the research strategy index.
6. Primachsenraum compares new finite methods on previously unoccupied residue
   classes, with chronological assumptions and negative controls. A consolidated
   review draft contains the beta theorem, finite factor bound and shift-three
   error bound, with version-bound provenance and no claim that the general
   prime deadline or an infinite factor conjecture has been proved.

## Mathematical and engineering constraints

- Reuse the authoritative `ExactRational`; no floating-point proof decisions.
- Every certificate verifier receives the expected original input separately.
- Search failure, timeout, cancellation and budget exhaustion carry no proof.
- Exact operation counts are distinct from timing measurements and actual work.
- Hall blockers require application-owned completeness and capacity hypotheses.
- The Hunter bound is classical; the Bernstein and Gram methods are classical.
  Credit the external transfer sources without claiming historical novelty.
- Keep SDK APIs thin and typed. No dependency from core to SDK or application.
- Preserve existing released behavior and published review versions.
- Do not automatically send the review draft or claim publication.
- Use isolated branches, meaningful tests, review and required CI gates.

## External transfer references

The inspected OpenAI source revision is
`4eaee87548250b2682affda7d05d947c05554bae`. Relevant paths are the arithmetic
certificate in *Hellinger contraction with arbitrary Boolean output bias*, the
verification section in *Exact Fourier certificates for complex Hadamard
matrices of order six*, and the independent certificate checker in *Snaky in
21 Maker moves*. Hunter's bound is from DOI `10.2307/3212481`.

## Delivery

Reviewable repository changes, reproducible comparison data, actual validation
results and focused pull requests. Report precisely which checks ran and which
claims remain open. A new public release is distinct from a source implementation.
