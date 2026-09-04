package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Resource;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Dependency-free fluent assertions for examples and external SDK consumers.
 *
 * <p>The assertions inspect the public {@link DiscoveryRun} view and throw
 * {@link AssertionError}. They deliberately do not reinterpret evidence or
 * upgrade a non-confirmed result into mathematical confirmation.</p>
 */
public final class DiscoveryRunAssertions {
    private DiscoveryRunAssertions() {
    }

    /** Starts assertions for one completed discovery run. */
    public static <C, K> RunAssert<C, K> assertThat(DiscoveryRun<C, K> actual) {
        return new RunAssert<>(actual);
    }

    /** Fluent assertion object bound to one immutable run. */
    public static final class RunAssert<C, K> {
        private final DiscoveryRun<C, K> actual;

        private RunAssert(DiscoveryRun<C, K> actual) {
            this.actual = Objects.requireNonNull(actual, "actual");
        }

        /** Returns the asserted run for domain-specific checks. */
        public DiscoveryRun<C, K> actual() {
            return actual;
        }

        /** Requires one exact terminal outcome. */
        public RunAssert<C, K> hasOutcome(Outcome expected) {
            Objects.requireNonNull(expected, "expected");
            if (actual.outcome() != expected) {
                fail("expected outcome " + expected + " but was " + actual.outcome());
            }
            return this;
        }

        /** Requires a confirmed result with its mandatory selected objects. */
        public RunAssert<C, K> isConfirmed() {
            return hasOutcome(Outcome.CONFIRMED)
                .hasCandidate()
                .hasCertificate();
        }

        /** Requires a refuted result without selected candidate or certificate. */
        public RunAssert<C, K> isRefuted() {
            return hasOutcome(Outcome.REFUTED)
                .hasNoCandidate()
                .hasNoCertificate();
        }

        /** Requires an inconclusive result without selected objects. */
        public RunAssert<C, K> isInconclusive() {
            return hasOutcome(Outcome.INCONCLUSIVE)
                .hasNoCandidate()
                .hasNoCertificate();
        }

        /** Requires an unsupported result without selected objects. */
        public RunAssert<C, K> isUnsupported() {
            return hasOutcome(Outcome.UNSUPPORTED)
                .hasNoCandidate()
                .hasNoCertificate();
        }

        /** Requires budget exhaustion without a fabricated result. */
        public RunAssert<C, K> isBudgetExhausted() {
            return hasOutcome(Outcome.BUDGET_EXHAUSTED)
                .hasNoCandidate()
                .hasNoCertificate();
        }

        /** Requires an invalid-seed result without selected objects. */
        public RunAssert<C, K> isInvalidSeed() {
            return hasOutcome(Outcome.INVALID_SEED)
                .hasNoCandidate()
                .hasNoCertificate();
        }

        /** Requires a selected candidate. */
        public RunAssert<C, K> hasCandidate() {
            if (actual.selectedCandidate().isEmpty()) {
                fail("expected a selected candidate");
            }
            return this;
        }

        /** Requires no selected candidate. */
        public RunAssert<C, K> hasNoCandidate() {
            if (actual.selectedCandidate().isPresent()) {
                fail("expected no selected candidate but was "
                    + actual.selectedCandidate().orElseThrow());
            }
            return this;
        }

        /** Requires a selected certificate. */
        public RunAssert<C, K> hasCertificate() {
            if (actual.selectedCertificate().isEmpty()) {
                fail("expected a selected certificate");
            }
            return this;
        }

        /** Requires no selected certificate. */
        public RunAssert<C, K> hasNoCertificate() {
            if (actual.selectedCertificate().isPresent()) {
                fail("expected no selected certificate but was "
                    + actual.selectedCertificate().orElseThrow());
            }
            return this;
        }

        /** Applies domain-specific assertions to the selected candidate. */
        public RunAssert<C, K> candidateSatisfies(Consumer<? super C> assertion) {
            Objects.requireNonNull(assertion, "assertion");
            C candidate = actual.selectedCandidate().orElseThrow(() ->
                failure("expected a selected candidate for a domain-specific assertion"));
            assertion.accept(candidate);
            return this;
        }

        /** Applies domain-specific assertions to the selected certificate. */
        public RunAssert<C, K> certificateSatisfies(Consumer<? super K> assertion) {
            Objects.requireNonNull(assertion, "assertion");
            K certificate = actual.selectedCertificate().orElseThrow(() ->
                failure("expected a selected certificate for a domain-specific assertion"));
            assertion.accept(certificate);
            return this;
        }

        /** Requires exactly the expected number of retained counterexamples. */
        public RunAssert<C, K> hasCounterexampleCount(int expected) {
            if (expected < 0) {
                throw new IllegalArgumentException("expected must be non-negative");
            }
            int observed = actual.counterexamples().size();
            if (observed != expected) {
                fail("expected " + expected + " counterexamples but found "
                    + observed + ": " + actual.counterexamples());
            }
            return this;
        }

        /** Requires at least one retained counterexample containing the fragment. */
        public RunAssert<C, K> hasCounterexampleContaining(String fragment) {
            if (fragment == null || fragment.isBlank()) {
                throw new IllegalArgumentException("fragment must not be blank");
            }
            List<String> counterexamples = actual.counterexamples();
            if (counterexamples.stream().noneMatch(value -> value.contains(fragment))) {
                fail("expected a counterexample containing '" + fragment
                    + "' but found " + counterexamples);
            }
            return this;
        }

        /** Requires no retained counterexample. */
        public RunAssert<C, K> hasNoCounterexamples() {
            return hasCounterexampleCount(0);
        }

        /** Requires one exact executed-work value. */
        public RunAssert<C, K> hasExecutedWork(Resource resource, int expected) {
            Objects.requireNonNull(resource, "resource");
            if (expected < 0) {
                throw new IllegalArgumentException("expected must be non-negative");
            }
            int observed = actual.executedWork().getOrDefault(resource, 0);
            if (observed != expected) {
                fail("expected work " + resource + "=" + expected
                    + " but was " + observed);
            }
            return this;
        }

        /** Requires positive executed work for the selected resource. */
        public RunAssert<C, K> hasPositiveExecutedWork(Resource resource) {
            Objects.requireNonNull(resource, "resource");
            int observed = actual.executedWork().getOrDefault(resource, 0);
            if (observed <= 0) {
                fail("expected positive work for " + resource + " but was " + observed);
            }
            return this;
        }

        /** Requires deterministic canonical evidence with a SHA-256 content hash. */
        public RunAssert<C, K> hasContentAddressedEvidence() {
            String first = actual.canonicalEvidence();
            String second = actual.canonicalEvidence();
            String hash = actual.evidence().contentHash();
            if (!first.equals(second)) {
                fail("canonical evidence changed between repeated reads");
            }
            if (!hash.matches("sha256:[0-9a-f]{64}")) {
                fail("evidence content hash is not canonical SHA-256: " + hash);
            }
            if (!first.contains("\"contentHash\":\"" + hash + "\"")) {
                fail("canonical evidence does not contain its content hash");
            }
            return this;
        }

        /** Requires that accepted and rejected transitions carry no assumptions. */
        public RunAssert<C, K> hasNoTransitionAssumptions() {
            List<String> assumptions = actual.evidence().transitions().stream()
                .flatMap(transition -> transition.assumptions().stream())
                .distinct()
                .sorted()
                .toList();
            if (!assumptions.isEmpty()) {
                fail("expected no transition assumptions but found " + assumptions);
            }
            return this;
        }

        private void fail(String message) {
            throw failure(message);
        }

        private AssertionError failure(String message) {
            return new AssertionError(message + "; evidenceHash="
                + actual.evidence().contentHash());
        }
    }
}
