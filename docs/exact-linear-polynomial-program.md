# Linear polynomial plans, replay evidence and executable programs

Issue #874-C now has an explicit native path from a predeclared linear
coefficient template to a budgeted `RewriteProgram`. It reuses the existing
schematic plan and mathematical source protocols. The finite enumeration
resolver, finite evidence authority, v1 bytes and primitive genome compiler
keep their existing meaning.

## Pre-solution binding

`ExactLinearPolynomialPlanResolver.createPlan` binds the complete formation
before solving: source expression, template, sorted unique coefficient hole IDs,
assumption context, and every solver and schematic-plan limit. Expressions use
the existing program convention of trimmed, collapsed whitespace. Control
characters and unpaired UTF-16 surrogates are rejected before formation hashing;
valid supplementary Unicode remains byte-exact. The bound fragment is
`affine-rational-polynomial-holes-empty-assumptions/v1`; the hole grammar is
`exact-linear-coefficients/v1`.

`HoleBudget.maxCandidates = 1` means one unique rational solution may be retained.
It does not describe a finite list of candidate scalar values. The solver limits
bound holes, projected monomials, exact numerator/denominator bits and cumulative
mathematical work. Plan limits independently bound the schematic topology and
canonical plan bytes. Neither a loaded run nor a selected result may replace
these formation inputs. This binding is an input contract, not independent
proof of a historical preregistration time or absence of target leakage.

The actual `ExactLinearPolynomialHoleSolver` performs coefficient projection,
bounded exact RREF, row-operation/back-substitution checks and independent
polynomial identity checking of the original source against the instantiated
template. The new resolver does not implement another algebraic checker.

## Evidence boundary

`Run.toCanonicalJson()` retains source/template/holes/assumptions/limits, every
constraint, native status and detail, full work profile, concrete RREF reduction
and certificate (including row operations, solution data and capability fields),
and a complete schematic resolution only for a unique solution. Its additive
schema is `regelsuche.exact-linear-polynomial-plan-run/v1` with a 1,000,000-byte
UTF-8 limit. An oversized export fails explicitly and issues no authority.

The artifact-reference container and loader are reused from the existing byte
protocol, with the distinct `linear-polynomial-plan-run` role and the new run
schema. This is a generic byte address, not a finite-solver receipt.

`ExactLinearPolynomialPlanEvidenceVerifier.verify` first validates the
independently supplied plan and formation, then checks the loaded artifact key,
role, schema, length and byte hash. It executes the native resolver again and
requires byte-for-byte equality with the complete independently generated
canonical run. Comparing exact canonical bytes rejects unknown/duplicate fields,
invalid UTF-8, foreign plans/limits and fully rehashed false claims without
granting authority to a deserializer or caller-supplied `CONFIRMED` outcome.

Only this verifier can issue the sealed `VerifiedReplay`. A replayed
`INCONSISTENT`, `UNDERDETERMINED`, `UNSUPPORTED`, `BUDGET_INCONCLUSIVE` or
`CHECK_FAILED` run remains available with its work and negative evidence;
`verifyCandidate` refuses to compile it. Nonempty assumptions remain explicitly
`UNSUPPORTED`, and an unchanged textual representation is not a rewrite.

## Explicit executable use

```java
var input = new ExactLinearPolynomialPlanResolver.Formation(
    "17*x/7+3*y/11", "${alpha}*(x+y)+${beta}*(x-y)",
    List.of("alpha", "beta"), List.of(),
    new ExactLinearPolynomialHoleSolver.Limits(4, 32, 128, 100_000));
var resolver = new ExactLinearPolynomialPlanResolver();
var plan = resolver.createPlan("declared-basis", input,
    new SchematicProofPlan.Limits(8, 8, 4, 200_000));
var run = resolver.resolve(plan, input);
var verifier = new ExactLinearPolynomialPlanEvidenceVerifier();
var reference = verifier.describeRun(run);
byte[] retainedBytes = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
var replay = verifier.verify(plan, input, reference,
    id -> new ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact(id, retainedBytes));
var evidence = verifier.verifyCandidate(replay);
var program = RewritePrograms.budgetedSource("linear-basis",
    new VerifiedLinearPolynomialCandidateSource(evidence));
var execution = new RewriteProgramInterpreter().executeBudgeted(program,
    input.sourceExpression(),
    new BudgetedRewriteProgramExecution.PathBudget(0, evidence.mathematicalWorkUnits()),
    new BudgetedRewriteProgramExecution.ExplorationLimits(100, 100, 8));
```

In persisted use the expected plan/formation and reference are retained
independently, and the loader supplies the actual stored run bytes. The example
uses an in-memory loader but still performs native independent replay.

The adapter exposes exactly one verified candidate for its bound source. Its
identity binds the candidate, complete evidence and adapter revision. A genuine
source mismatch is `NO_MATCH`; inadequate mathematical authority is
`BUDGET_INCONCLUSIVE`, which the existing interpreter retains as an incomplete
path. `sequence` deducts work before invoking the next source;
`firstApplicable` cannot skip an unresolved first source.

The mathematical charge is the retained solver's complete stage work plus the
one actual verifier replay's complete stage work. These are canonical theory
units, not enumerated assignments or primitive rewrites. Source matching,
budget admission and transition materialization retain separate mechanical
protocol counts of one, two and three. Byte hashing and Java execution time are
not renamed mathematical work. Both full stage profiles remain in the evidence.
An unbudgeted program invocation rejects this source.

## Verification and remaining scope

The public synthetic controls run the real solver, artifact loader, independent
replay and two-step program composition. They include a coupled rational
solution outside the old integer enumeration, cumulative one-unit-short
failure, foreign source/template/hole/assumption and every budget binding,
fully rehashed candidate/resolution forgery, byte/key/role substitution and
replayable negative native outcomes. Expected coefficients are checked only
after execution, not fed into formation. Existing finite controls and retained
finite plan/evidence/program samples are compared against the unchanged base.

This is an explicitly constructed mathematical `BudgetedSource` program. It
does not add a learned genome primitive, default runtime registration, a broad
template generator, nonlinear coefficient solving, assumption discharge,
free-parameter selection, recurrence discovery (#874-F), formal proof export,
novelty evidence or production promotion. Scientific use still requires its
applicable preregistration, split and authorization gates; no protected study
or held-out reference data is used for these implementation controls.
