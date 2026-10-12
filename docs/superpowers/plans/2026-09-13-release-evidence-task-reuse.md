# Reuse deterministic release evidence within one checkout

Issue #514 still asks for measured, safe reuse of campaign/release-readiness work.
The repository already has Java/JUnit JMH history rendering and a guarded,
shared promotion-pipeline test fixture; neither should be reimplemented.

The bounded remaining target is the ordinary
`runQualifiedReleaseReadinessWithHiddenRuleEvidence` generator. It currently
declares neither its hidden-rule report input nor its generated report tree,
so an unchanged checkout executes its three campaign runs and three candidate
qualifications again. The independently executed evidence verifier must remain
active on every verification invocation. Independent checkout and Docker
reproduction must still generate their own evidence.

1. Record two unmodified, focused Gradle invocations of the real generator and
   its existing schema/hash verifier. Use the existing public hidden-rule pilot
   test; do not run any protected flagship freeze or FINAL TEST.
2. Inspect the actual JavaExec input model. Bind the external hidden-rule report
   and dedicated generated report directory; rely only on confirmed tracked
   runtime classpath, arguments, launcher and JVM inputs. Do not enable a shared
   build cache or reuse a previous verifier decision.
3. Verify that an unchanged second invocation skips generation while still
   running the independent verifier. Retain exact report-tree byte identities
   and per-task durations for both executions.
4. Exercise concrete invalidation controls: changed/missing output, changed
   hidden-rule input, and changed runtime classpath/resource must invalidate
   generation. Corrupt evidence passed directly to the verifier must still fail.
5. Document the measured scope and limitations, preserve all existing tests,
   three independent campaign executions within each generation, policy limits,
   canonical report schemas and independent reproduction authorities. Obtain
   independent review and complete CI before merging.

This is a local incremental-build claim. It does not turn reused outputs into a
fresh experiment or establish a microbenchmark speedup or research result.
