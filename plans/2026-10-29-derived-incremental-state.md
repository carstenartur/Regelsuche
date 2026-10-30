# Verified incremental counter states

## Specification

Implement bounded state derivation from an observation expressed in the existing
`Expr` AST and legal insert/delete changes. Partition refinement must derive a
sufficient finite state and transition table; a separate checker must validate
every legal count and transition. This is bounded synthesis of counter observers,
not unrestricted program synthesis and not a new mathematical theorem. A grouped
accumulator must enforce the count bounds before changing state.

Primachsenraum consumes the generated data through its own checker in the actual
certified constellation optimizer. It retains exact arithmetic, branch order,
limits, and reference certificate replay. Compare complete results with the
existing direct path and a hand-written incremental baseline. Record derivation,
checking, initialization, cache rebuilds, memory bounds, execution and replay;
make no speed claim from operation counts alone. Demonstrate a non-prime use.

## Global constraints

Reuse Expr, Java 25 and JUnit. No new dependencies, test frameworks, CI workflows,
generic search frontier or unpublished SDK dependency in Primachsenraum's root.
Keep the direct default until measurements justify switching. Generated plans
must never become an unchecked authority for pruning. Bound exact-value sizes,
refinement work, tables and cached polynomial weights.

## Task 1: Generic derivation and independent verification

Interfaces: `CounterStateSynthesis.Specification`, immutable `Plan`, `derive`,
`verify`, and a checked grouped accumulator in regelsuche-search.
Write JUnit tests for insertion-only support (two states), reversible support
(all counts distinguished), parity (two states), arithmetic observers, forged
tables, bounds, rejected ASTs and budget exhaustion. Run the focused tests RED,
implement, then run them GREEN and the existing search tests.

Expected: all legal bounded transitions preserve the requested observation;
unsupported input and exhausted budgets produce explicit failures.

## Task 2: Certified native consumer and adapter

Interfaces: a data-only independently checked native plan; an opt-in incremental
entry point on CertifiedAdmissibleConstellationOptimizer, an actual-work ledger,
and an isolated SDK adapter. Derive both support and binary additive observers.
Write failing equivalence, sibling restoration, limit, malformed-plan and cache
fallback tests. Implement and run them GREEN with existing optimizer tests.

Expected: reference and incremental results, logical work and certificate hashes
agree, including independently replayed certificates. Changing the incumbent
invalidates the exact polynomial cache. Memory exhaustion selects the exact
reference polynomial calculation.

## Task 3: Transfer and honest comparison

Use ordinary JUnit for integration/transfer correctness. Add a runnable comparison
covering predeclared dense, sparse and wheel universes in fresh JVM runs; include
setup and independent replay for all methods. Compare against direct and native
incremental methods. Document measured positive or negative results and scope.

Expected: reproducible commands, actual measurements and no unsupported novelty
or general speedup claim. Finish with a fresh whole-change review and verified
fixes of material findings; publish separate reviewable branches/PRs.

## Review focus

Boundary transitions, forged/aliased data, resource exhaustion before allocation,
DFS sibling restoration, changing dyadic precision, equality of certificate work
versus actual execution work, and fair accounting against hand-written methods.
