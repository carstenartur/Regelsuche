# Task 1: rational LP certificates and support reduction

Read ../specs/2026-11-25-openai-math-follow-up.md first. Own ONLY new linalg LP/Farkas implementation files, their tests, SDK linalg facade, and docs/research/linear-program-certificates.md. Do not edit shared docs, POMs or other tasks' files. Do not commit; controller owns integration commits.

Implement a bounded exact-rational API for a supplied Ax<=b, max c^T x. Reuse existing exact types. Suggested types in linalg: LinearProgramCertificates (immutable Problem and certificate variants) and LinearProgramVerifier; FarkasSparsifier separate if useful. SDK LinearProgramSdk delegates.
Problem: nonempty rectangular rational matrix, compatible b,c, at least one variable. Certificate binds the full source problem. Variants:
- infeasible: y>=0,A^T y=0,b^T y<0.
- optimal: Ax<=b,y>=0,A^T y=c,c^T x=b^T y.
- unbounded: Ax<=b,Av<=0,c^T v=1.
Verify by original equalities/inequalities, no optimization solver. Invalid evidence differs from unavailable budget. Inspect source/certificate shapes, values and storage before allocations; all arithmetic/traversal uses CertificateWork. Final cancellation/deadline must be checked before success. Counters include nested verification.

Given a valid Farkas y, construct normalized sparse z with b^T z=-1, nonnegative coefficients, support subset of y and support count <= rank(A)+1. Use dependence elimination/min-ratio cancellation on augmented row generators (A_i,b_i); actual dependencies computed exactly; each step removes a nonzero coefficient. No floating-point or special-case answers. Independent final verifier must accept only actual source-valid outcome. Do not claim minimum-cardinality core.

Tests first: simple inconsistent x<=0,x>=1; redundant contradictions with rational rescaling and at least one strict support reduction; rank-deficient matrices; feasible bounded maximum with exact primal/dual match; unbounded ray; negative y, wrong objective, wrong source, wrong sizes; 200-bit rational values; zero/tiny budgets, cancellation; seeded small generated contradictions checked independently against original dot products and a test-only rational rank computation. SDK delegation tests.
Document actual evidence, limitations and pinned source:
https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/lean/OAI/Optimization/LinearProgramming/Farkas/Sparse.lean
and lean/docs/380.md. This is classical Farkas/sparsification, NOT the entire strongly polynomial LP solver.

Test command (serialize): mvn -B -ntp -pl regelsuche-optimization-sdk -am -Dtest=YOUR_TESTS -Dsurefire.failIfNoSpecifiedTests=false test
Add strict warning compiler flags for changed sources where build supports them. Full test review is controller responsibility. Report the results with the implementation. Include RED/GREEN, changed files and exact API, no subagents.
