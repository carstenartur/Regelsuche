# Comparing rational-polynomial procedures

This is a **public preregistered pilot**, separate from the protected flagship
FINAL TEST and all historical #235 comparisons. Its application is finding
shorter equivalent rational-polynomial representations **without a target**.
Neither a known historical identity nor a syntactic reference answer enters a
worker request. The pilot studies representation quality and resource costs in
the declared rational-polynomial fragment. See the [research methodology](research-methodology.md)
for the purpose of these procedure comparisons and the choice of reference software.

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

## Procedures and objective

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

Three repetitions and deterministic rotated profile order expose timing variation.
They measure this pilot under its declared boundary; steady-state performance
would require a separate JMH experiment. Timing is retained as non-canonical
diagnostics. `canonical-rows.json` removes clock fields;
deadline outcomes may still differ across machines and are not falsely required to
be byte-identical. CI adds no relative-speed acceptance threshold.

## Historical host reproduction (not routine CI authority)

The original checkout-owned Gradle/host invocation is retained here for historical
reproduction. The current required path is the Maven qualification below. Use the
historical source revision, Java 25 and its original build prerequisites:

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
run. The historical `External polynomial comparison` job retained raw rows, metadata,
setup/TRAIN/warmup evidence, stderr, a complete matrix summary and a manifest.
Its last integrated host driver is available at [the paired comparison revision](https://github.com/carstenartur/Regelsuche/blob/6bf44b7bfc1474f52cb19e85941130f62f366c3d/gradle/run-external-polynomial-comparison.sh).
The dedicated showcase branch-creation authority keeps its existing scope.
A passing harness test verifies the harness. Measured quality and resource
conclusions require an executed protocol and its retained evidence.


## Maven qualification of the frozen pilot

The dedicated Maven profile resolves the original learning worker's runtime,
executes the original matrix in the digest-pinned JDK 25 / CPython 3.12.12
worker with SymPy 1.14.0, and independently verifies its retained rows. Run it
from the root of a source checkout with Docker available.

```sh
mvn -Ppolynomial-comparison -pl regelsuche-release -am \
  -Dtest=ExternalPolynomialComparisonWorkerTest,PolynomialSourceProofTest,PolynomialRuntimeClasspathTest,PolynomialComparisonArchiveTest \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

The worker retains stdout, stderr, the ten original study products, the required
raw output tarball and the verified source admission in
`regelsuche-release/target/reports/polynomial-comparison`. Its two worker
handshakes, original protocol bytes and complete result matrix are required.
The source proof rejects modified tracked files before either the checkout or
the registered archive can supply a verified receipt to the worker.
The pinned Maven pilot must succeed before `ciCheck` can pass. It executes the
reference controls, the pilot controls, the original worker and its independent
archive verifier; the raw evidence is retained even on failure. The unrelated
typed/native host comparisons and their Maven replacements remain required.
The duplicate original pilot host job and driver are retired without changing
any frozen protocol, budget, threshold or mathematical verifier.

The paired [main CI run 45448386547](https://github.com/carstenartur/Regelsuche/actions/runs/45448386547)
on `6bf44b7bfc1474f52cb19e85941130f62f366c3d` produced both the
[legacy host archive](https://github.com/carstenartur/Regelsuche/actions/runs/45448386547/artifacts/14948190559)
and the [Maven archive](https://github.com/carstenartur/Regelsuche/actions/runs/45448386547/artifacts/14945509313).
The original verifier passed on both archives after independently checking the nine
manifest entries in each. All 648 canonical rows were byte-identical (SHA-256
`cba1bbdd1d378b75e6122db6bb2dd472e454b869a8613ef2e59ac3bd259543a9`).
The protocol and report were byte-identical, and differences in raw rows, setup,
warmup, summary and paired comparisons were timing/runtime diagnostics only.
The unmodified originals are retained; this is not wall-clock or memory parity,
independent study evidence or a claim that all of #749 is complete.

A same-run comparison at the earlier source `8a38b6ff` retained 648 matching
canonical rows from the Maven pilot and the existing lane. Timing observations
come from independently executed workers. The recovered integration needs its
own hosted result. [Historical comparison run](https://github.com/carstenartur/Regelsuche/actions/runs/44830486518)

`config/benchmarks/polynomial-freeze-source-objects.json.gz` contains the
original Git objects from the public history of e115. Their byte identities and
commit parent links prove ancestry of the three registered studies. The
original learned-schema protocol is additionally verified via its frozen tree,
file path and SHA-256. The source archive carries two receipts under
`build/authority-evidence`. To register an archive, run the Maven tests
`QualityAcceptancePublicationTest,PolynomialSourceProofTest` in the original
checkout with `-Dregelsuche.qualityAcceptance.registerArchive=true` and
`-Dregelsuche.polynomial.registerSource=true`. Set
`-Dregelsuche.qualityAcceptance.registration` and
`-Dregelsuche.polynomial.registration` to the new absolute output paths
`quality-source-tree.json` and `polynomial-source.json` in that archive's
evidence directory. The archive can then run the same experiment without a Git
checkout or Git executable.
