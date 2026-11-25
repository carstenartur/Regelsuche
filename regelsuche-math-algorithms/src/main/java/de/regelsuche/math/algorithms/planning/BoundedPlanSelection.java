package de.regelsuche.math.algorithms.planning;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Compare a verified fixed method with one explicitly budgeted, independently checked proposal. */
public final class BoundedPlanSelection {
    private BoundedPlanSelection() {}

    /** Conservative cells for the two retained rows, estimates, callbacks and list/bookkeeping. */
    public static final int TRACE_CELLS = 32;

    public enum Kind { FIXED, REUSED, LEARNED }
    public enum Disposition { SKIPPED, FAILED, SELECTED }
    public enum Unit { MODELED_LIMB_WORK, CERTIFICATE_WORK }

    /** Both estimates in a decision refer to its unit. Missing estimates cannot justify a probe. */
    public record Estimate(String provider, Kind kind, String evidence, OptionalLong cost) {
        public Estimate {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(evidence);
            if (cost.isPresent() && cost.getAsLong() < 0) throw new IllegalArgumentException("NEGATIVE_ESTIMATE");
        }
    }

    public record Attempt(Estimate estimate, Disposition disposition, String reason, long work) {}

    /** priorWork is the separately reported acquisition charge when known. work() covers this invocation only. */
    public record Decision<P, T>(P profile, Unit unit, long priorWork, long preparationWork, List<Attempt> attempts,
            Optional<T> value, String reason) {
        public Decision { attempts = List.copyOf(attempts); }
        public long work() { return preparationWork + attempts.stream().mapToLong(Attempt::work).sum(); }
        public Optional<String> selectedProvider() {
            return attempts.stream().filter(a -> a.disposition() == Disposition.SELECTED)
                .map(a -> a.estimate().provider()).findFirst();
        }
    }

    public record Checked<T>(Optional<T> value, String reason, long work) {}

    /** The operation must return its complete work, including failed checks, within the supplied limits. */
    public record Offer<T>(Estimate estimate, Optional<String> unmetRequirement, Function<IterationLimits, Checked<T>> operation) {
        public Offer(Estimate estimate, Function<IterationLimits, Checked<T>> operation) {
            this(estimate, Optional.empty(), operation);
        }
    }
    public record Offers<T>(Offer<T> fixed, Optional<Offer<T>> proposal, long retainedCells) {}

    /** A tie keeps the fixed method. Only a verified proposal suppresses the fixed fallback. */
    public static <P, T> Decision<P, T> choose(P profile, Unit unit, long priorWork, long maximumProbeWork,
            IterationLimits limits, BooleanSupplier cancellation, Function<CertificateWork, Offers<T>> prepare) {
        if (priorWork < 0 || maximumProbeWork < 0) throw new IllegalArgumentException("NEGATIVE_PORTFOLIO_WORK");
        var account = new CertificateWork(limits, cancellation);
        var attempts = new ArrayList<Attempt>(2);
        try {
            account.cells(TRACE_CELLS);
            account.charge(1);
            Offers<T> offers = prepare.apply(account);
            long retained = Math.addExact(TRACE_CELLS, offers.retainedCells());
            account.cells(retained);
            account.charge(1);
            long beforeTrials = account.used();
            var fixedCost = offers.fixed().estimate().cost();
            if (offers.proposal().isPresent()) {
                var proposal = offers.proposal().orElseThrow();
                Optional<String> skip = skipReason(proposal, fixedCost, maximumProbeWork);
                if (skip.isEmpty()) {
                    var checked = run(proposal, account, retained, maximumProbeWork, attempts);
                    if (checked.value().isPresent()) {
                        attempts.add(new Attempt(offers.fixed().estimate(), Disposition.SKIPPED, "PROPOSAL_VERIFIED", 0));
                        return new Decision<>(profile, unit, priorWork, beforeTrials, attempts, checked.value(), checked.reason());
                    }
                    if (terminal(checked.reason())) throw new CertificateWork.Stop(checked.reason());
                } else {
                    attempts.add(new Attempt(proposal.estimate(), Disposition.SKIPPED, skip.orElseThrow(), 0));
                }
            }
            var checked = run(offers.fixed(), account, retained, Long.MAX_VALUE, attempts);
            return new Decision<>(profile, unit, priorWork, beforeTrials, attempts, checked.value(), checked.reason());
        } catch (CertificateWork.Stop stop) {
            long trialWork = attempts.stream().mapToLong(Attempt::work).sum();
            return new Decision<>(profile, unit, priorWork, account.used() - trialWork, attempts,
                Optional.empty(), stop.getMessage());
        }
    }

    private static Optional<String> skipReason(Offer<?> offer, OptionalLong fixed, long probeWork) {
        if (probeWork == 0) return Optional.of("PROBE_DISABLED");
        if (offer.unmetRequirement().isPresent()) return offer.unmetRequirement();
        var estimate = offer.estimate().cost();
        if (estimate.isEmpty() || fixed.isEmpty()) return Optional.of("NO_COMPARABLE_ESTIMATE");
        return estimate.getAsLong() < fixed.getAsLong() ? Optional.empty() : Optional.of("NO_ESTIMATED_ADVANTAGE");
    }

    private static <T> Checked<T> run(Offer<T> offer, CertificateWork account, long retained, long cap,
            List<Attempt> attempts) {
        long spent = 0;
        try {
            var remaining = account.remainingLimits(retained);
            var allowance = remaining.withMaximumWork(Math.min(cap, remaining.maximumWork()));
            var result = offer.operation().apply(allowance);
            if (result.work() < 0 || result.work() > allowance.maximumWork())
                throw new IllegalStateException("INVALID_PROVIDER_WORK");
            account.chargeCompleted(result.work());
            spent = result.work();
            account.charge(0);
            attempts.add(new Attempt(offer.estimate(), result.value().isPresent() ? Disposition.SELECTED : Disposition.FAILED,
                result.reason(), spent));
            return result;
        } catch (CertificateWork.Stop stop) {
            attempts.add(new Attempt(offer.estimate(), Disposition.FAILED, stop.getMessage(), spent));
            return new Checked<>(Optional.empty(), stop.getMessage(), spent);
        }
    }

    private static boolean terminal(String reason) {
        return "CANCELLED".equals(reason) || "TIME_LIMIT".equals(reason);
    }
}
