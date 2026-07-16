package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Capability-driven planner with no backend-name selection rules. */
public final class SolverPortfolioPlanner {

    public Plan plan(PortfolioRequest request, List<? extends PortfolioBackend> backends) {
        Objects.requireNonNull(request, "request");
        List<? extends PortfolioBackend> ordered = backends == null ? List.of()
            : backends.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                    .comparing((PortfolioBackend backend) -> backend.profile().backendId())
                    .thenComparing(backend -> backend.profile().backendVersion()))
                .toList();
        List<PlannedBackend> planned = new ArrayList<>();
        List<RejectedBackend> rejected = new ArrayList<>();
        for (PortfolioBackend backend : ordered) {
            BackendCapabilityProfile profile = backend.profile();
            List<String> issues = profile.capabilityIssues(request.obligation());
            if (!issues.isEmpty()) {
                rejected.add(new RejectedBackend(
                    profile, AttemptDisposition.FILTERED_UNSUPPORTED, issues));
            } else if (!profile.canContributeTo(request.objective())) {
                rejected.add(new RejectedBackend(
                    profile, AttemptDisposition.FILTERED_IRRELEVANT,
                    List.of("IRRELEVANT_TO_OBJECTIVE:" + request.objective().name())));
            } else {
                planned.add(new PlannedBackend(backend));
            }
        }
        planned.sort(comparator(request));
        String hash = SolverIr.sha256(
            "request=" + request.contentHash()
                + "\nplanned=" + planned.stream()
                    .map(item -> item.backend().profile().semanticHash()).toList()
                + "\nrejected=" + rejected.stream()
                    .map(item -> item.profile().semanticHash() + ':' + item.issues())
                    .toList());
        return new Plan(List.copyOf(planned), List.copyOf(rejected), hash);
    }

    private static Comparator<PlannedBackend> comparator(PortfolioRequest request) {
        Comparator<PlannedBackend> identity = Comparator
            .comparing((PlannedBackend item) -> item.backend().profile().backendId())
            .thenComparing(item -> item.backend().profile().backendVersion());
        Comparator<PlannedBackend> confirmation = Comparator
            .comparingInt((PlannedBackend item) ->
                item.backend().profile().canConfirm(request.objective()) ? 0 : 1);
        Comparator<PlannedBackend> cost = Comparator
            .comparingLong((PlannedBackend item) ->
                item.backend().profile().estimatedCostUnits())
            .thenComparingInt(item -> item.backend().profile().costClass().ordinal());
        Comparator<PlannedBackend> stage = Comparator
            .comparingInt(item -> stageRank(item.backend().profile()));
        return switch (request.policy()) {
            case CAPABILITY_FIRST -> confirmation.thenComparing(stage)
                .thenComparing(cost).thenComparing(identity);
            case COUNTEREXAMPLE_FIRST -> stage.thenComparing(cost)
                .thenComparing(confirmation).thenComparing(identity);
            case CHEAPEST_CONFIRMATION_FIRST -> cost.thenComparing(confirmation)
                .thenComparing(stage).thenComparing(identity);
            case INDEPENDENT_CONFIRMATION -> confirmation.thenComparing(cost)
                .thenComparing(stage).thenComparing(identity);
        };
    }

    private static int stageRank(BackendCapabilityProfile profile) {
        if (profile.roles().contains(BackendRole.COUNTEREXAMPLE)) {
            return 0;
        }
        if (profile.roles().contains(BackendRole.ORACLE_VALIDATION)) {
            return 1;
        }
        if (profile.roles().contains(BackendRole.SEARCH_GUIDANCE)) {
            return 2;
        }
        if (profile.roles().contains(BackendRole.SYMBOLIC_CONFIRMATION)) {
            return 3;
        }
        return 4;
    }

    public record Plan(
        List<PlannedBackend> planned,
        List<RejectedBackend> rejected,
        String contentHash
    ) {
        public Plan {
            planned = List.copyOf(planned);
            rejected = List.copyOf(rejected);
            if (contentHash == null || !contentHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("plan contentHash must be SHA-256");
            }
        }
    }

    public record PlannedBackend(PortfolioBackend backend) {
        public PlannedBackend {
            Objects.requireNonNull(backend, "backend");
        }
    }

    public record RejectedBackend(
        BackendCapabilityProfile profile,
        AttemptDisposition disposition,
        List<String> issues
    ) {
        public RejectedBackend {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(disposition, "disposition");
            issues = List.copyOf(issues);
        }
    }
}
