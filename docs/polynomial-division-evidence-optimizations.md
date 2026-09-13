# Polynomial division and evidence ordering after PR #999

Reference: merged main `673e37f92f1e9449a5801aa36e1bfbb047430eb4`.
This is a separate optimization round, not a change to mathematical rules,
learning policies, protected studies, work limits or verification requirements.

## Division metadata and known remainder degree

The three division stage labels are constructed once per invocation. Their
contents and the complete sequence of charges are unchanged, including null,
blank and Unicode prefixes. Early returns remain before this preparation.

A canonical coefficient view is already trimmed. Division starts from its known
degree, then scans only from the previous remainder degree. A subtraction cannot
change an entry above that bound. The previous degree is still checked; the
implementation does not blindly assume that the leading coefficient canceled.

## Invocation-local prime-field inverse

Only when both the supplied field and the coefficient domain are the concrete,
immutable `PrimeField` is the divisor's leading-coefficient inverse reused.
It is initialized lazily after the existing iteration and coefficient-division
charges have been accepted. Generic/custom field implementations retain their
own `divide` calls, even when advertising the same mathematical domain ID.

For a fixed nonzero leading coefficient b in F_p, every scalar quotient a/b
is exactly a times the same b^-1. The inverse is local to this division; it is
not retained across requests or shared between threads. Zero-divisor, ring and
field checks precede this path as before. Each logical scalar division and each
coefficient update is still charged at the same point: work units describe the
existing logical-operation contract, not a count of BigInteger inverse calls.

## Canonical keys are produced once for ordering

Factor sorting decorates each factor with its canonical text once. String order,
multiplicity tie-breaks, stable ties, exact-equality merging and checked addition
of multiplicities are retained. Zero/single-factor cases avoid decoration.

Proposal sorting and deduplication share a TreeMap keyed by the same canonical
material. `putIfAbsent` preserves the first occurrence of an equal key, matching
the previous stable sort and insertion-order map. Temporary keys die with this
operation; there is no persistent or global evidence cache. No evidence field,
UTF-8 encoding, mathematical verification or hash algorithm is changed. This
optimizes repeated ordering keys, not every source of serialization overhead.

## Regression and measurement boundaries

Eight new JUnit groups call independently executable differential checks. Four
optimization-specific tests fail on the previous implementation: stage-label
reuse, bounded degree scanning, factor-key serialization and proposal-key
serialization. The remaining groups characterize result and complete budget
prefix parity, generic fields, failure ordering, large prime inputs, exact
reconstruction, stable ordering, duplicate identity and multiplicity overflow.

The budget oracle retains the old division algorithm. It compares every budget
from zero through one beyond successful completion on deterministic rational,
prime-field, sparse, large-input and early-return cases. Existing 185 canonical
native-result records remain byte-identical, including budget failures; stream
SHA-256: `0172dbf00c5066c22666a59d0f4e7f91d330351e9a0eb7a0c4e6d3df48617ed8`.

Local validation uses Java 21.0.11, a partial retained source reconstruction and
four actual Jackson annotation class files as compilation dependencies. The two
changed production source blobs match the merged reference before editing.
This is not a full Gradle/Maven product build. The new tests run in ordinary core
JUnit CI; full exact-head Java-25 CI and review are still required before merge.

Four local comparison runs in before/after/after/before order retain the same
40 public tasks, 6,720 observations, exact compiled variants and all negative
outcomes. All 39 nonzero tasks remain solved in the explicit degree32 profile;
zero remains separate. All 1,680 paired native responses match except timings.
The fixed 36 primary tasks show approximately 3.6% lower total and native-call
median geometric time. The unchanged checker also moves by about 4%, so this
is exploratory, not a demonstrated causal speedup. Eight total-time case medians
worsen (up to 4.7%). Native/SymPy ratios are about 2.99 and 2.89 respectively.
A separate four-run probe of 2,880 calls each observes about 1.2% lower elapsed
time and 1.3% fewer calling-thread allocated bytes. Allocated bytes are neither
live heap nor peak memory. No broad superiority or additional learning is claimed.

Focused product command:

```sh
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.DivisionEvidenceOptimizationTest
```
