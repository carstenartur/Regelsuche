package de.regelsuche.benchmark.polynomial;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Source-only inspection that stops each frozen row immediately before its first backend operation. */
public final class PolynomialTheoryUtilityExternalAdmission {
    private static final String PROFILE = "OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION";

    private PolynomialTheoryUtilityExternalAdmission() {}

    public record Prefix(PolynomialTheoryUtilityExecutionInput input,
            List<PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Occurrence> completed,
            List<Integer> pendingPath, Optional<FactorizationRequest<ExactRational>> firstRequest,
            PolynomialWorkLedger admittedWork) {
        public Prefix {
            completed = List.copyOf(completed);
            pendingPath = List.copyOf(pendingPath);
        }
    }

    public static List<Prefix> inspect() {
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases().stream().collect(Collectors.toMap(
            PolynomialTheoryUtilityCaseCorpus.FormationCase::caseId, Function.identity()));
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(input -> PROFILE.equals(input.profileId()))
            .map(input -> inspect(input, cases.get(input.caseId()))).toList();
    }

    private static Prefix inspect(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        var source = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.parse(formation.sourceExpression(), authority);
        var observations = new ArrayList<PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Occurrence>();
        for (var path : PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.paths(formation)) {
            try {
                observations.add(PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeOccurrence(
                    source, path, authority, () -> ENTRY));
            } catch (ReachedEngine entry) {
                return new Prefix(input, observations, path, Optional.of(entry.request), authority.ledger());
            }
        }
        return new Prefix(input, observations, List.of(), Optional.empty(), authority.ledger());
    }

    // Stop before the verifier can turn a sentinel report into a mathematical outcome.
    private static final class ReachedEngine extends Error {
        private final FactorizationRequest<ExactRational> request;
        private ReachedEngine(FactorizationRequest<ExactRational> request) {
            super(null, null, false, false);
            this.request = request;
        }
    }

    private static final FactorizationEngine<ExactRational> ENTRY = new FactorizationEngine<>() {
        public String engineId() {
            return PolynomialTheoryUtilityExecutionPlan.PROFILES.stream().filter(p -> PROFILE.equals(p.profileId()))
                .findFirst().orElseThrow().engineId();
        }
        public String coefficientDomainId() { return ExactRationalField.INSTANCE.id(); }
        public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
            throw new ReachedEngine(request);
        }
    };
}
