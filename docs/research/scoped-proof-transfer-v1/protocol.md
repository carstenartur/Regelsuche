# Public synthetic witness and induction transfer protocol, v1

Frozen before running the comparison. This protocol concerns the finite
logical slice of #874 M5. The historical residual-composition comparison
and independently blinded material have separate protocols and custodians.

## Experiment

Learn a priority order for a fixed grammar of witness-construction and
induction proposals from replayed TRAIN certificates. Freeze the order, its
training evidence, its revision, and its digest before generating validation
or final cases. Validation is observational. No tuning, rejection, or
replacement follows exposure of validation or final outcomes.

Three witness templates place the binder w in w+A=B, w−A=B, and A−w=B.
Operands A and B contain no w. The expression on either side may exchange
sides according to case parity. An additional impossible w+w=w+w+1 case
must retain non-use and exhaustion. The six constructive proposals, in
canonical order, are zero, B, B+A, B−A, A−B, and B×A. The learner may
condition its order on the binder's operator and side within that operator.
These selectors use no variable names or concrete coefficients.

Natural-induction cases contain named Nat→Prop predicates plus an explicit
zero case and successor step. The only general induction inference is
Nat.rec, with the two declared proof leaves. Candidate predicates are sorted
by their names when enumerating. A learned selector may propose the predicate
of the first exact zero premise. Add decoys with unmatched premises in
validation and final. Each partition also has a cyclic successor step with
no base; it cannot authorize induction. No candidate receives the desired
witness, a historical endpoint, or the designated induction conclusion
during formation. Witness formation has the mathematical existential
statement as input, as required by a witness-construction problem. Freeze
each proposed deduction before comparing it with the independent recipient
statement.

## Committed partitions

Each split has two distinct operand families. Each family contributes the
three valid witness forms and the impossible control. Each split also has
two valid induction contexts with different predicate ordering, and one
missing-base control. The partition identifiers, coefficients and predicate
names do not overlap. The shapes below are the generation rules; variable
names are prefixed by the split and family.

| Split | Operand families (A, B) | Induction contexts |
| --- | --- | --- |
| TRAIN | (a,b), (a+2,b−3) | one predicate; two predicates with irrelevant declaration |
| VALIDATION | (a×b,b+5), (a−b,(a+b)×7) | two predicates, one unmatched step |
| FINAL | ((a+b)×(a−b),a×a+b×b+11), (a×a−b,(a+b)×13−b) | three predicates, unmatched and crossed steps |

All outcomes are retained, including ties, losses, rejected proposals,
exhaustion and zero uses. The comparisons are:
1. learned order frozen from TRAIN;
2. the fixed canonical grammar order;
3. deterministic random permutation, Java Random seed 8741066 plus the case ordinal (shuffle valid, well-scoped proposals only);
4. exhaustive bounded proposal enumeration, retaining all results;
5. hand-written structural inverse for the three witness forms and the
   base-premise induction selector;
6. ordinary existing witness enumeration (or full native natural-induction
   enumeration), separately identified where its candidate universe differs.

Each constructive arm may try at most six distinct proposals per case.
The ordinary witness arm uses the same six-candidate ceiling and retains
budget exhaustion. Count generated/evaluated proposals and exact checker
calls separately. Charge all TRAIN candidate measurements to the learned
arm. Record proof roots, assumptions, learned-plan usage, and actual
receiver-side replay. Compare correctness/reach first, then measured work.
A win over only some comparators cannot establish overall superiority.
No independence, historical priority, external performance, or global proof
search statement follows from this public deterministic study.
