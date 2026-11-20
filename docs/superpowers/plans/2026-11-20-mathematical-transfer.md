# Mathematical transfer implementation

Spec: [accepted design](../specs/2026-11-20-mathematical-transfer-design.md).

## Global constraints

Mathematical implementation belongs in the core math module with thin typed SDK access. Certificates bind the complete expected input. Resource exhaustion, cancellation and unsupported semantics never produce success. Exact source-level observation is stronger than modular equality. Each focused branch includes meaningful tests and honest scope documentation. Work in isolated branches; do not modify other ongoing checkouts. Ordinary pull requests target main. Do not publish a release or claim a performance improvement without the corresponding evidence.

## Task 1: Modular linear discovery and exact certificates

Implement a generic bounded modular homogeneous linear solver using existing rational/matrix types, CRT, rational reconstruction and selected original rows. Provide distinct substitution and complete-kernel verification, thin SDK access and a usable opt-in recurrence-invariant synthesis integration. Test compatible/incompatible and rank-dropping primes, source/certificate tampering, all-source residuals, rational reconstruction, cancellation and budgets against the exact solver. Document the family-266 transfer and update the research strategy index.

## Task 2: Bouncy Castle Montgomery dot products

Implement ML-DSA delayed reduction, followed by ML-KEM fused vector base multiplication with a measured choice between three-reduction and Karatsuba schedules. Prove the relevant caller ranges, preserve the final normalization boundary, test all parameter sets, known-answer vectors, malformed inputs and alias behavior where applicable. Include deterministic kernel/reference tests and a reproducible whole-operation benchmark with baseline comparison. Retain only transformations supported by both correctness and performance evidence.

## Task 3: Bounded noncommutative identity checker

Implement the characteristic-zero family-116 triangular hitting-point evaluator for bounded division-free formula trees, using the existing exact rational implementation. Add typed thin SDK entry, exact tree-size accounting including both sides, dimension/bit/work limits and cancellation. Test noncommutativity, true identities, independent polynomial references, unsupported inputs, shared-DAG expansion and resource limits. Keep experimental and opt-in; document the theorem dependency and scope.

## Task 4: Certified Montgomery schedule core

Implement typed machine representation, range propagation and modular schedule identity checking with reusable certificates, independent replay and a thin SDK. Support signed low-word reductions at radix 2^16 and 2^32, nested reductions and accumulator products. Demonstrate both BC parameter families and boundary/overflow/source-tamper controls. Add a typed planner for delayed dot-product reduction and distinguish modular from canonical-observation claims.

## Task 5: Sandbox source integration

Read the actual JDT adapter contracts, bind requests to source, prove the supported loop and caller normalization/range conditions, and emit the chosen Java transformation through the normal mathematics cleanup. Reject unsupported effects or missing proofs. Use the new SDK dependency, compile original/transformed fixtures and compare actual observations. Respect the repository's review-size and Maven/JUnit policies.

## Task 6: Review and integration

Independently review each focused diff for spec compliance and mathematical/Java correctness. Repair findings on the same branch. Run module gates and source-reference validation, publish reviewable focused PRs, inspect exact-head CI and fix task-related failures. Record measured results, remaining limits and dependency status in each PR and the final response.
