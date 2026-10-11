# Selected optimization search-path qualification

The SDK retains the actual witness of the existing JointPlanSearch/TypedSourceOnlySearch.
It does not run a second search to manufacture an explanation and does not add target
formulas, source-name selectors, or mathematical rules.

## Revisions and actual execution

Production implementation: `c48d160800a5d1ab82a7adbb0804be768256e94b`, based directly
on main `419a23b615a6a6cf4a902a52d8b5cc789952db6a`.
Production tree: `84d305bdd23d94fcc36762b6b0c187655fd3f889`.

Run 38046119232 executed the initial test-only SDK with 99 tests and six failures:
retained history was absent and an incorrectly declared empty history was accepted.
No existing numerical contract test was disabled.

Run 38046991786 passed the ordinary SDK dependency reactor, with 105 SDK tests and
320 math-algorithms tests, no failures/errors/skips in those suites. The unchanged
MavenOptimizationSdkDistributionTest then executed two clean builds, 105 SDK tests
per build, two freshly compiled standalone consumers, and byte equality of all ten
distribution files. The five MavenWorkflowSemanticsContractTest cases also passed.
The fresh consumer now checks the retained path from input to final candidate.

Distribution artifact: 11668316074, SHA-256
`7fed8589c28e8d2d80b9b4f0a35a82db65077dc69e7e57ddf7e80f3ce0e6539b`.
Its receipts remain bound to c48d160; they must not be relabelled as a later build.

Review regression: `dcbd12bad508f6a824630dbf4502808f0bdf95c8` changes only
EmptySearchDerivationTest. Run 38047876959 executed the full dependency reactor and
verified 106 SDK tests, zero failures/errors/skips, before publishing the test.
The real optimizer shares two original x*y evaluations without changing either output
envelope. Reverification and presentation must preserve its present zero-edge path,
not collapse it into absent legacy history. The execution check includes overflow.
Production source and the previously qualified distribution are unchanged by this test.

## Scope of the record

Every selected edge retains its real generator rule and typed before/after output
expressions. Replay checks the evidence binding, contiguous endpoints, membership
in independently regenerated proposals and independent numeric equivalence at each
edge, under the same caller allowance/deadline as final proof and presentation.
The original generation configuration is retained separately from that allowance;
changing a replay budget must not alter the generator inventory or grant extra work.
Existing exact-budget tests remain unchanged and passing.

An absent legacy history, a present zero-edge path and a present nonempty path are
three distinct states. Compound proposals remain single recorded edges; their internal
primitive algebra is not invented. The data is replayable, not a signed historical
audit log. The original source evaluation trace and Java numerical/exception policies
remain authoritative. No measured speedup or general loop-invariant inference is claimed.

Commands:

```sh
mvn -B -ntp -Psdk-release -pl regelsuche-optimization-sdk -am test
mvn -B -ntp -Psdk-release -pl maven-build-contract \
  -Dtest=MavenOptimizationSdkDistributionTest,MavenWorkflowSemanticsContractTest test
```

Normal repository-wide final-head CI and review remain separate merge gates. These
focused receipts do not imply that a PR is merged or the Sandbox consumer is shipped.
