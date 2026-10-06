# Periodic signal discovery implementation plan

Spec: `docs/superpowers/specs/2026-10-06-periodic-signal-discovery.md`.

Use the existing Java 25 build, ExactRational, DiscoveryDomainBuilder and
DomainDiscoveryRunner. Do not change core search semantics, add an FFT library,
claim unbounded proof, or count a whole transform as one cheap search operation.

## Task 1: Exact signal mathematics and independent checking

Add `de.regelsuche.discovery.signal` types for bounded rational comb inputs,
direct/merged plans, explicit work counters, and an independent cyclotomic DFT
checker. Write tests before the implementation. Check invalid divisors, DC,
length one, cancellation, rational normalization and deliberately wrong output.

Verification: focused `PeriodicSignalTest` and `CyclotomicDftVerifierTest`,
including every divisor and frequency for small lengths.

## Task 2: Typed discovery integration and separate holdout evaluation

Add a typed SDK domain selecting plans by training work only, preserving the
existing runner's budgets and evidence. Validate inputs and canonical payloads;
emit replayable finite certificates and separate work measurements. Both fixed
plans must have access to the same imported Fourier identity.

Verification: SDK domain tests for confirmation, exhausted budgets, stable
replay, holdout independence and reuse on unseen lengths/weights.

## Task 3: Reproducible comparison, documentation and final review

Add a JavaExec example and document input limits, exact normalization, plan
search, independent checking and cost-model limitations. Run the affected module
suites and the example, then the repository suite if the environment supports
the required toolchain. Record concrete infrastructure blockers. Review the
whole branch independently, fix material findings, and publish the branch/PR.

Review focus: mathematical independence of checker, actual work vs search event
counts, strict separation of training and holdout, finite proof status, bounds,
canonical identity and reproducibility. Review tests as behavior, not snapshots
of incidental implementation details.
