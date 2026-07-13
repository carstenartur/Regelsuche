# Transformation descriptor v2

`regelsuche.transformation-descriptor/v2` extends the rule-ID-independent descriptor with a conservative local tree-difference view. It is computed from the public parent expression and an already-applicable candidate's child expression before policy selection.

## Local-change contract

The descriptor records a local status, occurrence depth, occurrence role, immediate context root, changed-subtree roots and changed-subtree AST deltas.

Roles are:

- `ROOT` for a whole-expression replacement;
- `LEFT` or `RIGHT` for ordered binary operators;
- `AC_CHILD` for addition and multiplication, where syntax-side labels would not be mathematically stable;
- `ARGUMENT` plus a zero-based argument index for function calls;
- `UNAVAILABLE` when no single local occurrence is justified.

Statuses are:

- `AVAILABLE` when exactly one changed path can be isolated;
- `AMBIGUOUS` when more than one sibling/argument changes or an AC swap has no unique local position;
- `UNPARSEABLE` when either expression cannot be parsed;
- `IDENTICAL` when no change exists.

Ambiguous and unavailable cases never receive an invented position. Their feature vector contains `local.available=0` and a named status only.

## Predictive safety

Concrete rule IDs, application keys, hidden family/reference metadata, selected-path labels and post-hoc outcomes are absent from the descriptor. Variable names and assumption values do not enter predictive identity. Assumptions contribute only through typed counts.

The schema transition is strict. A model trained for descriptor v1 is incompatible with v2 and must fall back to ordinary static BestFirst semantics. BestFirst remains responsible for applicability, guards, duplicate pruning and successful-enqueue budget accounting.

## Evidence

Focused tests cover root, ordered-child, AC-child and function-argument changes, as well as ambiguous multi-site changes and malformed output. The held-out five-family evaluation is performed separately through the bounded frontier-priority channel after the prerequisite PR merges.
