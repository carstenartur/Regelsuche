# General numerical structure, not corpus-specific rewrites

## Intent

Real SWT and Bouncy Castle code identify useful missing capabilities; neither
repository, class names, fixed curve constants nor source hashes may authorize a
production rewrite. Independent examples and negative controls must demonstrate
that each capability transfers to another code base.

## Existing architecture

Regelsuche already has exact symbolic matrices, ordered matrix-expression rules,
independent matrix-formation and vector-replay verification, and a bounded affine
iteration model with simultaneous/sequential state updates. Reuse these facilities.
Sandbox owns Java binding, control flow, effects, source freshness and code emission.
ULR remains a small language-independent description, not another CAS or Java analyzer.

## Approaches and decision

A second matrix engine or BC-specific adapters would duplicate existing machinery
and violate the user's generality requirement. Converting every scalar calculation
into heap-allocated matrices would add avoidable runtime costs. Instead, expose
optional mathematical structure during analysis, propose bounded transformations,
and return to the existing scalar/Java proof and emission paths.

The concrete first matrix slice adds ordered chain parenthesization to the existing
experimental operator preparation. Dynamic programming finds the lowest dense
scalar-multiplication count among parenthesizations of the same ordered factors.
It does not permute factors, invent inverses, infer sparsity, change an algebra,
or claim a timing benefit. It introduces no new matrix representation.

## Matrix slice contract

- At most 32 ordered factors; existing dimensions, polynomial, coefficient and
  expression-depth caps remain unchanged.
- Sums, inverse witnesses and other non-product expressions are opaque factors.
- Validate the entire original expression before proposing. Revalidate the new
  grouping with a fresh evaluator so a cached deep subtree cannot bypass depth caps.
- Charge factor inspection, DP storage and work, reconstruction and validation to
  the existing request ledger. Exhaustion must not return a partial plan.
- Emit only a strict dense-operation-count improvement. Equal-cost plans are not
  extra suggestions. Ties in the bounded search are deterministic.
- The existing MatrixRepresentationBridge and VectorReplay remain acceptance
  authorities; the chain proposal is not a Java equivalence certificate.

## Java and recursion boundaries

A matrix-equation representation or equivalent solution set is not permission to
rewrite Java expressions. Primitive wrapping, checked operations, floating-point
rounding, division, exceptions, identity, initialization and effects need the
existing typed Java checks. In particular, matrix multiplication is not commutative.
The pair `(a*c-b*d, a*d+b*c)` has a structured matrix view, but obtaining three
products still requires joint bilinear optimization, not merely changing notation.

A direct, exact-dispatch tail call can be represented as an ordered state transition.
Every next argument is evaluated left to right using the old state; assignments
become visible together only after those evaluations. Reuse the existing affine
iteration/matrix transition when that mathematical fragment is actually proved.
Do not put Java ASTs, method-name recognition or recursion-specific algebra in ULR.

Removing recursion changes stack consumption and potentially StackOverflowError
and stack observations. A strict Preserve Java route must not silently authorize
that change. Initial source support must be narrow, explicit and separately tested;
mutual/branching recursion, synchronization and finally blocks are not implied.

## Remaining work outside this matrix slice

The Java value-object source adapter, typed scalar-to-matrix bridge, recursion
source admission and optional IDE application, compiled consumer qualification and
new performance measurements are separate integration work. This change does not
claim completion of that larger request or any new BC/SWT runtime result.
