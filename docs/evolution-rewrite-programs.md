# Evolved rewrite programs

Status: executable semantic bridge for #521

## Purpose

The evolutionary subsystem previously compiled an accepted genome into a flat
list of ordinary `RewriteRule` instances. That remains useful for evolving rule
templates, but it cannot represent a learned search strategy with ordered
phases, alternatives, repetition, semantic guards and explicit pruning.

`EvolutionRewriteProgramPlan` adds a canonical evolution-side topology whose
nodes map directly to the already implemented `RewriteProgram` interpreter.
`EvolutionRewriteProgramCompiler` resolves the plan against exactly one
validated genome and returns an ordinary `ProgrammedTransformationEngine`.
Every existing search strategy can therefore execute an evolved program without
introducing a second search implementation.

```text
EvolutionGenome ── preflight ── compiled RewriteRule sources ──┐
                                                               │
EvolutionRewriteProgramPlan ── topology and budget checks ─────┤
                                                               ▼
                                                    RewriteProgram
                                                               │
                                                               ▼
                                           ProgrammedTransformationEngine
                                                               │
                              best-first / beam / A* / other SearchStrategy
```

This is the first implementation slice of the flagship experiment in #521. It
does not yet mutate program topology, execute the preregistered flagship corpus
or authorize a FINAL TEST result.

## Canonical plan

The versioned contract is:

```text
regelsuche.evolution-rewrite-program-plan/v1
```

A plan binds:

- the exact genome content hash;
- one immutable root node;
- explicit maximum node and depth limits;
- an alpha-structural hash that ignores node identifiers, normalizes referenced
  genes through one stable program-wide alias table, and retains topology,
  ordered rule use, bounds, guards and priorities;
- an exact content hash over the complete canonical payload.

The alias table is established before the tree is traversed. It is therefore
independent of execution order. Swapping two different source rules in a
`Sequence` or `FirstApplicable` node changes the alpha identity, while merely
renaming node IDs does not. This distinction is required because rule order can
change executable search semantics and must remain visible to population
diversity and duplicate suppression.

Node IDs are unique within one plan. Source gene IDs are ordered and unique.
Cycles, reused node instances, unknown node kinds, duplicate JSON fields,
unknown JSON fields, trailing JSON values and inconsistent hashes fail closed.

The strict Draft 2020-12 schema is
[`regelsuche-evolution-rewrite-program-plan-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-plan-v1.schema.json).

## Supported topology

| Evolution node | Runtime meaning |
|---|---|
| `Source` | One AST transformation engine containing the referenced genome rules. |
| `Choice` | Deterministic union of all alternatives. |
| `FirstApplicable` | First alternative that produces at least one candidate. |
| `Sequence` | Feed every candidate from one phase into the next phase. |
| `Repeat` | Bounded repetition with explicit minimum and maximum iteration counts. |
| `Require` | Hard candidate filter with a serializable supported condition. |
| `Prioritize` | Soft deterministic ordering without candidate removal. |
| `Prune` | Explicitly incomplete candidate truncation with a retained reason. |

Supported hard requirements are deliberately finite and reviewable:

- equivalence preserving by construction;
- assumption free;
- maximum estimated cost delta;
- maximum primitive step count.

Supported priorities are:

- estimated cost, then rule identity;
- a declared preferred genome-gene order.

Arbitrary Java lambdas are not serialized into evolutionary evidence. New
condition or ordering kinds require a new controlled enum value, compiler
semantics, schema update and characterization.

## Compilation boundary

Compilation performs the following checks before search can observe the plan:

1. the plan is bound to the exact genome content hash;
2. the ordinary genome preflight succeeds;
3. every source and preferred gene exists in that genome;
4. node count and depth remain within the genome program budget;
5. repeat and primitive-step bounds remain within
   `maxApplicationsPerPath`;
6. pruning remains within `maxCandidatesPerState`;
7. every source becomes an `AstRewriteTransformationEngine` with the frozen
   genome growth and candidate limits.

The resulting `CompiledRewriteProgram` retains both genome identities, both
plan identities, the executable program, its ordinary engine adapter, the
referenced genes and a human-readable rendering.

## Example

```java
EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
    genome,
    new EvolutionRewriteProgramPlan.Prioritize(
        "cheap_first",
        new EvolutionRewriteProgramPlan.Require(
            "bounded_steps",
            new EvolutionRewriteProgramPlan.Sequence(
                "normalize",
                List.of(
                    new EvolutionRewriteProgramPlan.Source(
                        "multiply_identity", List.of("mul_one")),
                    new EvolutionRewriteProgramPlan.Source(
                        "additive_identity", List.of("add_zero"))
                )
            ),
            EvolutionRewriteProgramPlan.Requirement.maxPrimitiveSteps(2)
        ),
        EvolutionRewriteProgramPlan.Priority.estimatedCostThenRule()
    ),
    8,
    8
);

EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled =
    new EvolutionRewriteProgramCompiler().compile(genome, plan);

List<Transformation> candidates =
    compiled.engine().transform("(x * 1) + 0");
```

The successful candidate is returned through the ordinary transformation model.
A multi-step result retains its complete primitive rule sequence and appears as
one explicit program transformation to the enclosing search strategy.

## Claim boundary

This bridge establishes that a bounded, content-addressed program topology can
be compiled and executed using the production interpreter. It does not establish
that evolution has found a useful topology, that the topology generalizes, that
a FINAL TEST has been consumed, that a result is formally proved or that any
mathematics is externally novel.

The next #521 slices must add deterministic topology mutation and then freeze
the rational/polynomial TRAIN, VALIDATION and FINAL TEST corpus before any
evaluated program search begins.
