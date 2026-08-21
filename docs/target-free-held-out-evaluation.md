# Target-free held-out multi-step evaluation

This tranche follows the bounded conclusion retained from the first 6 × 4
target-free study. That study validated the freeze/qualification
infrastructure and known-structure bridges, but every qualified candidate was
at derivation depth one and no case supported an all-policy matched-work
comparison.

## Frozen matrix

The held-out matrix contains six case families, four target-blind policies and
six admitted-primitive-step checkpoints:

```text
8, 16, 32, 64, 128, 256
```

A configuration is identified by the case, policy and exact checkpoint, for a
total of 144 pre-registered rows. Engine calls, generated transitions and wall
clock remain recorded diagnostics; they are not substitutes for the admitted
primitive-work authority.

Five cases are positive and one is a near-miss negative control. Positive cases
must have a bounded witness of three to ten primitive rewrites. At least two
cases require a temporary increase in AST complexity, every positive case
contains an explicit distractor pack, and no direct primitive or macro edge may
reach a post-freeze reference.

The cases cover:

- a three-step cancellation chain that must retain `z != 0`;
- binomial expansion through a temporary complexity valley;
- reverse exposure of a difference-of-squares representation;
- an occurrence-local trigonometric bridge;
- a telescoping-capability bridge with denominator assumptions;
- a trigonometric near miss whose arguments differ.

## Information boundary

Formation receives source expressions, explicit assumptions, target-blind
policy identities, rule-pack selections, finite hard limits and one admitted
primitive-work checkpoint. It cannot inspect reference expressions, expected
outcomes, required capabilities or witness obligations.

The qualification resource is byte-bound by the preregistration but may be
opened only after every candidate batch and actual-work ledger for the
corresponding checkpoint has been frozen. Qualification cannot alter candidates
or work.

## Comparison rule

A policy comparison at a checkpoint is admissible only when all four policies
reached exactly that admitted-primitive-step checkpoint for the same case and
inventory. Early source exhaustion is retained as evidence but is not treated
as matched work.

## Execution and retained evidence

`TargetFreeHeldOutMatrixRunner` executes the complete matrix in canonical row
order. It writes three separate artifacts:

```text
target-free-held-out-plan.json
target-free-held-out-candidate-freeze.json
target-free-held-out-post-freeze-qualification.json
```

The plan contains only the preregistration and formation surface. The runner
then executes every row, records complete candidate lineages and actual-work
ledgers, writes the candidate-freeze artifact and reads it back byte-for-byte.
Only after that complete freeze has been verified does it open the sealed
qualification resource.

The post-freeze artifact binds every qualification row back to its frozen
configuration, candidate batch, candidate set, freeze receipt, work ledger,
classification catalog and rule inventory. The generated files are retained
under the discovery test report tree so CI and independent reproduction can
inspect the exact evidence rather than relying on console summaries.

## Host and pinned-container reproduction

The complete matrix is additionally executed twice in separate checkout JVM
runs and once in a digest-pinned, offline `linux/amd64` container. The gate
requires all three canonical artifact sets to be byte-identical and retains the
Dockerfile hash, pinned base-image digest and built-image ID in a schema-checked
receipt.

See [Target-free held-out container reproduction](target-free-held-out-container-reproduction.md)
for the exact command, isolation policy, generated paths and claim boundary.

The tranche remains bounded evidence. It establishes neither mathematical
novelty nor general policy superiority, and it does not treat wall-clock time as
a substitute for matched admitted primitive work.
