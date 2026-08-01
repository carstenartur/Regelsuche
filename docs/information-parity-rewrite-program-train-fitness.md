# Information-parity TRAIN fitness for evolved rewrite programs

Status: authoritative paired TRAIN evaluator for #521

## Comparison contract

The unit under evaluation is one exact `EvolutionRewriteProgramCandidate`:

- one executable rule genome;
- one executable strategy topology over those rules.

The comparison must isolate the value of the topology. Therefore both sides
receive:

- the same input, syntax-exact target and declared assumptions;
- the same ordinary AST rule inventory;
- every rule compiled from the candidate genome as an independently applicable
  flat rule;
- the same scorer, canonicalizer, best-first implementation and budgets.

Only the candidate side additionally receives the compiled
`ProgrammedTransformationEngine` for the topology.

```text
baseline  = ordinary rules + flat genome rules
candidate = ordinary rules + flat genome rules + learned program
```

A rule introduced by the genome can therefore help both sides. A measured gain
requires the program to compose, order, guard, repeat or prune those same rules
more effectively.

`InformationParityRewriteProgramTrainFitnessEvaluator` is the authoritative
evaluator for this contract. The earlier default-rules-only prototype is not
sufficient for topology claims because it does not give the flat baseline the
candidate genome.

## Exact path validation

Search reachability is not mathematical truth. Every adjacent expression in a
reached path is checked with exact rational-function normal forms under the task
assumptions. Generated path assumptions must be contained in the declared
assumption set.

Path status remains one of:

- `CONFIRMED`;
- `REFUTED`;
- `MISSING_ASSUMPTION`;
- `UNSUPPORTED`;
- `NOT_EVALUATED` when the target was not reached.

A case is newly solved only when:

1. the information-parity flat baseline does not reach the target;
2. the candidate reaches it;
3. an actual `program:` transformation occurs in the retained path;
4. the full path is exactly confirmed.

A flat genome rule solving a task on both sides produces no newly-solved credit.
An unsafe rule that reaches the expected target string is retained as refuted
negative evidence and blocks the candidate.

## Primitive work rather than macro-edge illusion

A composed program can expose several primitive transformations as one search
edge. Evidence expands the retained `program:[rule-a -> rule-b -> ...]`
identity and counts the primitive steps. Path-length fitness therefore cannot
claim a two-step program costs one primitive operation merely because the
search graph stores it as one replayable edge.

Explored-state and primitive-step reductions contribute fitness only for a
confirmed candidate path that actually contains a `program:` edge. Merely
adding a program to the candidate frontier can change queue pressure or tie
ordering; it must not earn topology credit when the retained solution path uses
only the same flat rules already available to the baseline.

Explored states and generated transformations remain separate dimensions. Raw
fitness components remain authoritative independently of the frozen scalar
population profile.

## Boundary enforcement

The suite candidate limit must be at least as wide as the candidate genome's
source limit. Otherwise the flat and program sources could inspect a candidate
set wider than the search policy admits, and evaluation fails closed with:

```text
SUITE_CANDIDATE_BOUND_NARROWER_THAN_GENOME_AND_PROGRAM_SOURCES
```

Compiler failure, reachability regression, refutation, missing assumptions,
unsupported exact validation and unsupported requested fitness components are
retained as blockers rather than negative scalar hints.

## Characterized example

For the genome rules:

```text
(A*B)/(A*C) -> B/C   requiring A != 0
a/1         -> a
```

and the task:

```text
(x*y)/(x*1) -> y   under x != 0
```

both sides know both flat rules. With search depth one, the flat baseline can
perform one primitive step but cannot complete the two-step path. A learned
`Sequence(cancel_factor, divide_one)` can expose the complete, exactly validated
composition as one search edge while evidence still counts two primitive
operations. This isolates genuine composition value.

## Claim boundary

This establishes paired, exact, information-parity TRAIN fitness for one
combined candidate. It does not establish population superiority, VALIDATION
selection, FINAL TEST utility, formal proof, external novelty or release
promotion.
