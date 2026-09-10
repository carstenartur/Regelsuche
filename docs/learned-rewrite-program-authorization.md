# Learned RewriteProgram authorization

Issue #745 requires learned strategy programs to cross a different production
boundary from individual learned pattern rules. A `RewriteProgram` is a
composition of rule sources plus control flow: sequence, choice,
first-applicable choice, repetition, guards, prioritization and pruning. Treating
that topology as if it were one pattern rewrite would hide both its semantics
and its real work.

`LearnedRewriteProgramAuthorizationService` therefore authorizes the program as
a **program**.

## Two independent authorities

The contract deliberately separates mathematical rule authority from strategy
authority:

```text
EvolutionRewriteProgramCandidate
  = EvolutionGenome + canonical EvolutionRewriteProgramPlan
                         |
                         | referenced gene IDs
                         v
       independently authorized learned pattern rules
       (one LearnedPatternRuleAuthorizationService.Authorization per leaf)
                         |
                         v
       EvolutionRewriteProgramCompiler.compileAuthorized(...)
                         |
                         | substitutes promoted/proved rules
                         | into the original canonical topology
                         v
               executable RewriteProgram
                         |
                         v
          deterministic replay case set
                         |
                         v
 learned-rewrite-program-replay-evidence/v1
                         |
                         v
 learned-rewrite-program-authorization-receipt/v1
```

The ordinary `EvolutionRewriteProgramCompiler.compile(...)` remains the research
path and may compile raw evolutionary genome rules for TRAIN/evolution work. It
is **not** the production authorization path. `compileAuthorized(...)` instead
requires an exact map from every referenced gene ID to an explicitly supplied
rule, rejects missing and extra entries, and rejects any supplied rule that is
not equivalence-preserving by construction.

The program authorizer obtains those supplied rules from the promotion products
inside the already reconstructed learned-pattern authorizations introduced by
#964. A program therefore cannot turn an unqualified raw genome gene into an
authorized production rule merely by referencing it from a successful strategy.

## Deterministic program replay

`LearnedRewriteProgramReplayEvidence` binds the exact candidate, genome and plan
hashes and re-executes a fixed set of named input expressions through the
authorized program. Each replay case retains:

- ordered output candidates;
- origin node ID;
- ordered rule IDs;
- complete primitive-rule lineage;
- normalized assumptions;
- provenance hash;
- primitive and exact-theory execution work;
- completeness;
- rewrite-program work revision;
- path-budget identity when present;
- the full deterministic `TransformationWorkMetrics` ledger.

This makes control-flow changes observable. Sequence and repeat work remains
represented by the complete underlying step path; requirements, prioritization,
pruning, alternatives, deduplication and composition retain their interpreter
work counters. A learned program is therefore a search/scheduling abstraction,
not a zero-cost proof edge.

Authorization never trusts a retained replay artifact merely because its hash is
self-consistent. `authorize(...)` recompiles the exact candidate using the
currently usable leaf authorizations and recreates the replay evidence from its
bound inputs. The supplied and reconstructed canonical artifacts must be
identical.

`replayStoredAuthorization(...)` applies the same rule to a stored program
receipt: it revalidates every leaf authorization, recompiles the program,
replays the evidence, checks the work-revision set and requires the stored leaf
receipt hashes and program lifetime to match the reconstructed authority.

## Lifetime and repository binding

The program receipt binds:

- exact rewrite-program candidate hash;
- exact genome content and alpha-structural hashes;
- exact plan content and alpha-structural hashes;
- exact lower-case 40-character repository revision;
- deterministic replay-evidence hash;
- a gene-ID-to-leaf-authorization-receipt-hash map;
- all replay work-semantics revisions;
- explicit authorization and expiry instants.

The composite program expires at the **earliest** expiry of its referenced leaf
authorizations. It can never remain valid after one of its mathematical rule
authorities has expired. Stored receipts also fail closed before their
`authorizedAt` instant, at the `validUntil` boundary, after a repository revision
change, or after candidate/plan substitution.

## Canonical strategy language, not arbitrary closures

Production authorization starts from `EvolutionRewriteProgramPlan`, not from an
arbitrary runtime `RewriteProgram` object. The evolution-side plan exposes only
content-addressable declarative operators and closed enums for requirements and
priority policies. The compiler may translate those declarations to runtime
predicates/comparators, but callers cannot smuggle an arbitrary Java closure into
a production-authorized learned strategy.

This distinction is important for replayability: the plan hash identifies the
actual strategy grammar, while the repository revision identifies the compiler
and interpreter implementation used to realize it.

## Schemas

This slice adds:

- `docs/schemas/regelsuche-learned-rewrite-program-replay-evidence-v1.schema.json`;
- `docs/schemas/regelsuche-learned-rewrite-program-authorization-receipt-v1.schema.json`.

Both artifacts use the same strict canonical JSON support as learned-pattern
authorization: duplicate keys, unknown properties, trailing tokens and
non-canonical encodings fail closed.

## Claim boundary

Program authorization is a safety, identity and replay contract. It does **not**
show that the learned program is faster or more successful than a primitive
baseline. Product-default selection and matched-work performance qualification
remain separate #745 tasks.

Likewise, retained primitive-path length is evidence about the path actually
executed, not proof that no shorter path exists. A later utility/minimality layer
may distinguish observed, shortest-known and certified compression, but the
program authorization layer must not manufacture such a claim.