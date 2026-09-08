package de.regelsuche.polynomial;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.mining.PolynomialTheoryCandidateObserver;
import de.regelsuche.mining.PolynomialTheoryFormationOutcomeLedger;
import de.regelsuche.mining.RuleCandidateFormationObserver;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.ExactPolynomialTransformationSource;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import de.regelsuche.transform.PolynomialDerivedMacroCache;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit application composition; no profile changes the default rule inventory. */
public final class PolynomialSearchIntegration {
    public static final String EXTERNAL_ENGINE_ID =
        "regelsuche.factorization.sympy-graalpy.rational/v1";

    public enum Profile {
        NO_FACTORIZATION,
        ON_DEMAND_VERIFIED_FACTORIZATION,
        VERIFIED_DERIVED_MACRO_CACHE,
        SPECIALIZED_BINARY_QUARTIC_CONTROL,
        OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION
    }

    private PolynomialSearchIntegration() { }

    /**
     * Opens one isolated cache lifetime. The optional backend is supplied and
     * closed by the caller; a missing or different backend is never substituted.
     */
    public static Session open(Profile profile,
            Optional<FactorizationEngine<ExactRational>> external, int cacheCapacity) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(external, "external");
        if (profile != Profile.OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION && external.isPresent()) {
            throw new IllegalArgumentException("external engine supplied to another profile");
        }
        return switch (profile) {
            case NO_FACTORIZATION -> new Session(profile, null, null, List.of());
            case SPECIALIZED_BINARY_QUARTIC_CONTROL -> new Session(profile, null, null,
                List.of(new PolynomialDecompositionSynthesisOperator()));
            case ON_DEMAND_VERIFIED_FACTORIZATION, VERIFIED_DERIVED_MACRO_CACHE ->
                exact(profile, NativeUnivariateFactorizationEngine.boundedRationals(), cacheCapacity);
            case OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION -> {
                var engine = external.orElseThrow(() -> new IllegalArgumentException(
                    "optional pinned GraalPy/SymPy engine is not configured"));
                if (!EXTERNAL_ENGINE_ID.equals(engine.engineId())
                        || !ExactRationalField.DOMAIN_ID.equals(engine.coefficientDomainId())) {
                    throw new IllegalArgumentException("external backend differs from the selected profile");
                }
                yield exact(profile, engine, cacheCapacity);
            }
        };
    }

    private static Session exact(Profile profile, FactorizationEngine<ExactRational> engine, int capacity) {
        var mode = profile == Profile.VERIFIED_DERIVED_MACRO_CACHE
            ? ExactPolynomialTransformationSource.Mode.VERIFIED_CACHE
            : ExactPolynomialTransformationSource.Mode.ON_DEMAND;
        var source = new ExactPolynomialTransformationSource(engine, mode, List.of(), capacity);
        return new Session(profile, engine, source, List.of());
    }

    /** The historical control deliberately remains separate from exact-theory search sources. */
    public static final class Session {
        private final Profile profile;
        private final FactorizationEngine<ExactRational> engine;
        private final ExactPolynomialTransformationSource source;
        private final List<HypothesisOperator> specializedControls;

        private Session(Profile profile, FactorizationEngine<ExactRational> engine,
                ExactPolynomialTransformationSource source, List<HypothesisOperator> controls) {
            this.profile = profile;
            this.engine = engine;
            this.source = source;
            specializedControls = List.copyOf(controls);
        }

        public Profile profile() { return profile; }
        public List<HypothesisOperator> specializedControls() { return specializedControls; }
        public Optional<ExactPolynomialTransformationSource> sourceAt(List<Integer> path) {
            Objects.requireNonNull(path, "path");
            return source == null ? Optional.empty() : Optional.of(source.atPath(path));
        }

        /**
         * Composes the post-formation classifier with this session's executable
         * cache. Learning work is admitted separately, never borrowed from the
         * subsequent search invocation or made into a standard/kernel rule.
         */
        public RuleCandidateFormationObserver learningObserver(PolynomialDerivedMacroCache macros,
                PolynomialTheoryFormationOutcomeLedger outcomes, long retentionWorkLimit) {
            if (profile != Profile.VERIFIED_DERIVED_MACRO_CACHE) {
                throw new IllegalStateException("learning handoff requires the explicit cache profile");
            }
            if (retentionWorkLimit < 0) throw new IllegalArgumentException("negative retention authority");
            var classifier = new PolynomialTheorySubsumptionClassifier(engine);
            return new PolynomialTheoryCandidateObserver(classifier, macros, outcomes,
                (classification, evidence) -> source.retainLearned(
                    classification, evidence.sourceProvenance(), retentionWorkLimit));
        }
    }
}
