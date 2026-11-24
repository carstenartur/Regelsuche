# Public direct reduction comparison, version 1

The protocol in `config/benchmarks/external-polyhedral-v1.json` is frozen before
implementing and running the comparison. It contains every input in the pinned
upstream artifact, unchanged, and three separately labelled project controls.
The original `prefix_sum` compiler was already run as an installation smoke test;
this disclosed development exposure is not a sealed evaluation.

The opponent is the original Alpha compiler distributed with **Maximal
Simplification of Polyhedral Reductions**, POPL 2025, at commit
`d91815edbfb0463c1b195173dbff46a4a2e52f39`. This is a successor artifact, not
an asserted reproduction of the exact compiler used in arXiv:2411.17498v1.
Both distributed JAR hashes and the complete seven-file input inventory are
bound by the protocol. The upstream compiler remains unmodified.

## Input and evaluation contract

Each procedure receives the same original Alpha source, without an answer or
preferred rewritten program. The native Regelsuche core accepts prefix sums of
integer polynomials in one or more strictly increasing row bindings. Its
ordered-reduction search normalizes the source polynomial, derives the row
factors, and independently checks the resulting plan. A generic prefix
evaluation returns the existing accumulated tuple sum after each input row.
This is the established prefix/ordered-summary method, not a claim of a new
algorithm.

The importer recognizes the whole declared program shape; it must not match
only a convenient summand while ignoring a changed output domain. Other
reductions, higher-rank outputs, non-prefix domains and variable denominators
remain visible as unsupported outcomes. The external compiler is invoked for
every source, including those outside the shared fragment. Compiler errors,
timeouts, missing simplifications and unverified output remain distinct.

For admitted sources an independent exhaustive integer interpreter enumerates
every increasing tuple for each prefix, without calling the native plan or
external compiler. The predeclared row counts and three input sequences are
used for both outputs. Generated C is compiled with undefined-behaviour
sanitization for verification. Source expressions and exhaustive sums receive
conservative signed-64-bit bounds. Every executed generated-C intermediate is
checked by the sanitizer; a failing execution cannot qualify. This checks these
finite executions; it is not an independent proof of all generated C programs
or an IEEE floating-point optimization claim. General native plan validity
comes from the existing source-bound mathematical verifier.

## Resource boundary and retained evidence

The fixed manifest declares one run per source and arm, subprocess deadlines,
Java heap limits and native work limits. The Java heap cap is not a hard cap
on native/JNI memory or total resident size. Acquisition/checkout is explicitly
unmeasured; source-verification setup, process setup,
synthesis, C compilation, output execution and common checking are reported
separately. Native work counts are never converted into compiler instruction
counts. One measured run permits no relative speed or scalability conclusion.
Original-source parse/import costs belong to the native request; adapter
restriction is reported as such, rather than as a theorem about the underlying
algorithm's expressive power.

All result rows, including errors and unsupported cases, must be retained.
The result archive includes original subprocess logs, generated sources and
checksums. Reproduction uses Java/Maven and the pinned public artifact; no
additional Python product dependency is introduced.

## Scope of evidence

The seven upstream examples are genuinely authored outside Regelsuche. They
are small public compiler-artifact examples, not an independently adjudicated
industrial deployment or a blinded challenge. The project controls measure
the common boundary but do not increase the foreign-corpus count. The old
SymPy studies, Enumo/Ruler, AutoLifter and the learning experiments remain
separate evidence. No baseline, prefix scan, memoization or equality-saturation
method is renamed as an original Regelsuche invention.

## Source-semantics correction after the development run

The complete first run exposed an input-language distinction: the project pair
control has an empty reduction at its first output. The original Alpha loader
rejects that output as undefined; the native mathematical API defines an empty
sum as zero. The final Alpha boundary therefore rejects this unguarded form.
It does not silently add a zero-valued branch. The original public input
`prefix_sum` is unaffected. Native prefix evaluation for arbitrary ordered
tuples remains available through the typed API.

The project polynomial control failed in the original loader with an ISL
context error and a null-pointer exception. Adding parentheses in a separate
diagnostic did not resolve it. This is retained as an unresolved original
frontend/context failure, not evidence of better reduction search. The frozen
sources, their order, budgets and negative rows remain unchanged. The first
run is retained as development evidence; the corrected implementation is run
again. A nominally successful process that finishes after the declared wall
deadline cannot qualify even if the operating system delays timeout delivery.
