# External polynomial comparison

This is a **public preregistered pilot**, separate from the protected flagship
FINAL TEST and all historical #235 comparisons. Its application is finding
shorter equivalent rational-polynomial representations **without a target**.
Neither a known historical identity nor a syntactic reference answer enters a
worker request. This pilot is not external novelty or a universal leaderboard.

## Freeze and scope

Commit `476d41e1f3fecdcff9b4ccba45b13aa8a9265542` froze
`config/benchmarks/external-polynomial-v1.json` before implementation or evaluation.
Its exact UTF-8 bytes have SHA-256
`3308fe57ae55566552cd675fb7e43d4287c84402afa23b73ebd86777f2e82afc`.
Changing cases, objectives, budgets or profiles requires another protocol revision;
the runner rejects a changed v1 file. The run must descend from the freeze commit.

There are 24 polynomial inputs, three explicitly unsupported controls, eight
profiles and three measured repetitions: 648 retained rows, **not 648 independent
mathematical tasks**. These are project-authored public inputs, not an independently
curated or sealed challenge. Some mathematical families were already discussed in
the project. The Java adapter rejects overlap with its actual frozen TRAIN scope.
No protected holdout is read and no test outcome changes learned policies.

The shared fragment is formal commutative rational-polynomial algebra: at most
four variables, bounded nonnegative integer powers and constant nonzero rational
denominators. Polynomial exponent zero denotes the unit, including `0^0` in this
formal algebra; this is not a real-analysis convention. Variable denominators,
negative powers and logarithms remain separate unsupported controls. The calculus
domain issue #982 and the SAFE product adapter #972 are **not resolved or exercised**
by this lane. It does not grant production qualification under #745.

## Competitors and objective

Regelsuche uses the real `LearnedSchedulingModel` and its existing BASE,
LEARNED_NAIVE, LEARNED_RANKED and EXPERT provider/policy combinations. The source-only
adapter supplies an empty goal, keeps every admitted state's primitive lineage,
selects an incumbent and independently replays its selected path. The state-ranking
objective for this new pilot is arithmetic-operation count, rather than silently
reusing the earlier target-directed study's goal or score. No old engine policy or
historical evidence is changed.

SymPy is pinned to 1.14.0. Separate profiles run `simplify`, `factor` and `cancel`.
The fixed portfolio executes **all** of identity, simplify, factor, cancel and horner
before selecting by the same declared cost. Every attempted result and its time
remain visible; it is not a free best-of oracle. This follows SymPy's recommendation
to use targeted operations rather than treating `simplify` as the strongest operation
for every purpose: <https://docs.sympy.org/latest/explanation/best-practices.html>.

The objective counts each binary arithmetic operation and unary minus once, with
numeric and variable leaves costing zero. No algebraic simplification is performed
by this cost function. Strict improvement and at least 20% reduction are separate
outcomes; unchanged and worse equivalent outputs remain legitimate negative results.
A short incorrect expression is never a success.

The common verifier uses a bounded sparse polynomial implementation with Python
`Fraction`, independently of both Regelsuche and SymPy. It does not use `eval` or
SymPy's simplifier to confirm SymPy's own output. Its certificate strength is exact
coefficient equality in the declared fragment, not a Lean kernel proof.

## Costs and failure boundaries

The common two-second boundary includes worker request transport, response parsing
and the common exact verification. Worker timeouts kill the actual process group;
subsequent unavailable rows stay in the matrix and make qualification incomplete.
Setup, shared Java TRAIN and fixture warmup are measured separately. The Java worker
trains the existing shared experimental model once; its setup is not zero-cost even
when one profile subsequently ignores the learned providers. No product startup or
lifetime advantage is inferred from this experimental arrangement.

Native process CPU, native elapsed time, complete request time and common verifier
time are retained. Java's primitive/search/verification ledger and the additional
selected-path replay cost are separate from opaque CAS work. An internal logical
work overrun remains visible; an incumbent can be returned as an anytime result,
but that result is **not** a successful claim under the exceeded logical work budget.
The external quality comparison uses the common wall boundary, not a fictitious
conversion between CAS operations and Regelsuche's work units.

Three repetitions and deterministic rotated profile order expose noise but do not
establish steady-state JMH performance or a reliable general speed ranking. Timing
is retained as non-canonical diagnostics. `canonical-rows.json` removes clock fields;
deadline outcomes may still differ across machines and are not falsely required to
be byte-identical. No faster-than-SymPy threshold is added to CI.

## Reproduction

Use the supported Java 25 environment and the repository's normal build prerequisites:

```sh
python3 -m venv build/external-polynomial-venv
build/external-polynomial-venv/bin/python -m pip install sympy==1.14.0 mpmath==1.3.0
./gradlew -I scripts/external-polynomial-classpath.gradle \
  :regelsuche-learning:externalPolynomialClasspath
PYTHONPATH=scripts build/external-polynomial-venv/bin/python \
  -m external_polynomial_comparison.run \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/external-polynomial-comparison
PYTHONPATH=scripts build/external-polynomial-venv/bin/python \
  -m external_polynomial_comparison.run --verify \
  --output build/reports/external-polynomial-comparison
```

An existing result directory is never overwritten. Use a new directory for another
run. The dedicated workflow retains raw rows, metadata, setup/TRAIN/warmup evidence,
stderr, a complete matrix summary and a manifest. It supplements rather than replaces
full Java 25 Gradle, Maven/product/Docker, SymPy, JMH and checkout-owned ciCheck.
A passing harness test is not an executed benchmark or a demonstrated competitive win.
