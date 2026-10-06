# Periodic signal discovery

Implement a bounded discovery domain for rational sums of divisibility signals
`s(k) = sum(weight[d] * [d divides k])`, with `d` a divisor of the signal length
`L`. The normalized forward DFT has the exact coefficient
`sum(weight[d] / d * [L/d divides j])`. This identity is imported knowledge,
not a newly discovered theorem or an integer-factorization algorithm.

The existing discovery runner selects between direct evaluation of the combs
and merging equal periods before evaluation. It ranks the two plans on training
queries and freezes the selected plan before evaluating separate holdout queries.
Both fixed baselines have the same exact Fourier primitive. There is no claim of
speedup against a baseline denied that knowledge.

An independent checker constructs the sampled DFT polynomial and reduces it
modulo the appropriate cyclotomic polynomial using exact arithmetic. It checks
finite instances, reports a concrete nonzero remainder on disagreement, and
does not promote a finite witness to a universal proof. Input bounds make this
reference computation explicit and finite. No numerical FFT dependency or
hidden factorization is introduced.

Expose reusable mathematics in discovery and a typed domain in the discovery
SDK, with a reproducible executable comparison. Report construction, evaluation,
plan-selection and independent verification work separately. Arithmetic event
counts and operand bit sizes are diagnostics, not CPU timings or FFT complexity.

Acceptance: exact boundary/cancellation cases; exhaustive small comb checks;
wrong coefficients rejected; invalid/oversized inputs rejected; typed payload
round trips; no holdout influence on selection; exhausted audit budget remains
inconclusive; reproducible evidence; honest comparison with fixed plans.
