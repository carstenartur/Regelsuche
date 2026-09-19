# Typed output-pattern application (#1026)

## Purpose

Apply a syntax hypothesis returned by TypedPatternGeneralizer to a selected
subset of the outputs in an ordered program/bundle expression. This is the
context-matching prerequisite of #1026, not its frozen learned-transfer study.
The source and target hypothesis must have the same function envelope and
number of corresponding output slots. Nothing here proves an identity.

## Contract

1. Use actual Expr and PatternExpr values and the existing exact ExprMatcher.
   Do not format/reparse, sort program outputs or add a ModPow-specific rule.
2. Enumerate injective assignments of pattern slots to physical output positions
   in deterministic lexicographic order. All slots share one matcher binding map.
3. Instantiate target slot i into the physical position matched by source slot i.
   Keep every unmatched output in place and preserve its exact Expr object.
4. Return source, target, the ordered position mapping and immutable bindings.
   These are untrusted syntax applications, not Transformations or proof receipts.
   Keep the originating hypothesis and its sample assumptions available; do not
   silently reinterpret those observations as generalized proof premises.
5. Bound program outputs to 8, AST/pattern occurrences to 1024 and depth to 64.
   Bound complete assignment attempts explicitly (1..4096). Exhaustion before
   trying a further assignment reports complete=false, never a false absence.
   Matcher diagnostics and oversized instantiated outputs also make the relation
   incomplete. Report assignment attempts and matcher steps separately; neither
   is a complete CPU/memory cost measure.
6. Reject incompatible envelopes/arity and target-only placeholder names.
   A valid nonmatching input returns an empty complete result, not an exception.

## Verification before implementation

Development tests learn arbitrary f/keep -> g/keep syntax pairs and use the
resulting Candidate rather than a handcrafted application rule. These fixtures
are NOT mathematical identities. Cover renamed inputs, reordered and extra
outputs, shared bindings, scoped identity, duplicate positions, bounded
exhaustion, incompatible hypotheses and resource limits. Test the scaffold
first, then the implementation, then the complete learning-module suite.

No official #1026 TEST corpus is used. Generalized arithmetic premises,
independent replay of concrete learned arithmetic moves, frozen TRAIN/model/TEST
artifacts, and fair equal-budget utility measurements remain separate gates.
The unchanged #1025 primitive optimum remains a control, not a baseline to
weaken in order to claim a learned improvement.
