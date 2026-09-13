# Bounded linear polynomial plan and program integration

This implements the next ordinary issue #874-C slice from solver commit
`46737023e569a6b69e776bede9f86f59d5aa8927`. No study is executed.

1. Freeze source, template, declared coefficient holes, assumption context and
   all rational fragment, dimension, scalar-bit and work limits in an additive
   linear formation identity before calling the solver. Reuse the generic
   schematic plan with a new linear grammar, not finite candidate domains.
2. Retain the actual solver result, including failed/incomplete outcomes,
   concrete coefficient constraints, row operations, certificate, work and any
   complete plan resolution in versioned canonical run bytes.
3. Verify loaded bytes against an independently retained plan and formation by
   executing the real resolver again. Only this verifier may issue opaque
   replay/candidate evidence. Self-consistent hashes and caller outcome labels
   are insufficient authority.
4. Compile one verified, uniquely solved candidate through the existing
   `RewritePrograms.budgetedSource` protocol. Charge original mathematical work
   plus the actual independent replay; preserve the interpreter's cumulative
   path budget and distinct primitive/theory semantics.
5. Run real composition, foreign binding, tamper, unsupported/non-unique and
   exhausted-work controls, then the existing finite controls. Compare retained
   finite v1 examples byte for byte with the untouched base. Commit after green
   focused offline Maven checks with fresh generated classes.

The fragment remains affine rational coefficients and empty assumptions.
Nonempty assumptions and nonlinear templates cannot become executable evidence.
The grammar, source and program order are declared inputs, never selected from
held-out reference values. This does not add recurrence discovery (#874-F),
genome primitive compilation, default runtime activation, proof publication or
study/novelty/promotion authority.
