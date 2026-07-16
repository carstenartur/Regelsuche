package de.regelsuche.solver.portfolio;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Machine-readable capabilities and non-semantic runtime availability. */
public record BackendCapabilityProfile(
    String backendId,
    String backendVersion,
    List<String> supportedSchemas,
    List<Theory> supportedTheories,
    List<Relation> supportedGoalRelations,
    List<Relation> supportedAssumptionRelations,
    List<Sort> supportedSorts,
    List<BinaryOperator> supportedOperators,
    boolean supportsCalls,
    List<RequestedEvidence> supportedEvidence,
    List<BackendRole> roles,
    CostClass costClass,
    long estimatedCostUnits,
    boolean deterministic,
    boolean reproducible,
    BackendAvailability availability,
    String configurationHash,
    String semanticHash
) {
    public BackendCapabilityProfile {
        requireText(backendId, "backendId");
        requireText(backendVersion, "backendVersion");
        supportedSchemas = sortedStrings(supportedSchemas);
        supportedTheories = sortedEnums(supportedTheories);
        supportedGoalRelations = sortedEnums(supportedGoalRelations);
        supportedAssumptionRelations = sortedEnums(supportedAssumptionRelations);
        supportedSorts = sortedEnums(supportedSorts);
        supportedOperators = sortedEnums(supportedOperators);
        supportedEvidence = sortedEnums(supportedEvidence);
        roles = sortedEnums(roles);
        Objects.requireNonNull(costClass, "costClass");
        Objects.requireNonNull(availability, "availability");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("backend roles must not be empty");
        }
        if (estimatedCostUnits <= 0L) {
            throw new IllegalArgumentException("estimatedCostUnits must be positive");
        }
        requireSha(configurationHash, "configurationHash");
        requireSha(semanticHash, "semanticHash");
        String expected = semanticHash(
            backendId, backendVersion, supportedSchemas, supportedTheories,
            supportedGoalRelations, supportedAssumptionRelations, supportedSorts,
            supportedOperators, supportsCalls, supportedEvidence, roles, costClass,
            estimatedCostUnits, deterministic, reproducible, configurationHash);
        if (!expected.equals(semanticHash)) {
            throw new IllegalArgumentException("capability semanticHash does not match fields");
        }
    }

    public static BackendCapabilityProfile create(
        String backendId,
        String backendVersion,
        List<String> supportedSchemas,
        List<Theory> supportedTheories,
        List<Relation> supportedGoalRelations,
        List<Relation> supportedAssumptionRelations,
        List<Sort> supportedSorts,
        List<BinaryOperator> supportedOperators,
        boolean supportsCalls,
        List<RequestedEvidence> supportedEvidence,
        List<BackendRole> roles,
        CostClass costClass,
        long estimatedCostUnits,
        boolean deterministic,
        boolean reproducible,
        BackendAvailability availability,
        String configurationHash
    ) {
        List<String> schemas = sortedStrings(supportedSchemas);
        List<Theory> theories = sortedEnums(supportedTheories);
        List<Relation> goals = sortedEnums(supportedGoalRelations);
        List<Relation> assumptions = sortedEnums(supportedAssumptionRelations);
        List<Sort> sorts = sortedEnums(supportedSorts);
        List<BinaryOperator> operators = sortedEnums(supportedOperators);
        List<RequestedEvidence> evidence = sortedEnums(supportedEvidence);
        List<BackendRole> orderedRoles = sortedEnums(roles);
        return new BackendCapabilityProfile(
            backendId, backendVersion, schemas, theories, goals, assumptions,
            sorts, operators, supportsCalls, evidence, orderedRoles, costClass,
            estimatedCostUnits, deterministic, reproducible, availability,
            configurationHash,
            semanticHash(backendId, backendVersion, schemas, theories, goals,
                assumptions, sorts, operators, supportsCalls, evidence,
                orderedRoles, costClass, estimatedCostUnits, deterministic,
                reproducible, configurationHash));
    }

    public BackendCapabilityProfile withAvailability(BackendAvailability next) {
        return new BackendCapabilityProfile(
            backendId, backendVersion, supportedSchemas, supportedTheories,
            supportedGoalRelations, supportedAssumptionRelations, supportedSorts,
            supportedOperators, supportsCalls, supportedEvidence, roles, costClass,
            estimatedCostUnits, deterministic, reproducible, next,
            configurationHash, semanticHash);
    }

    public List<String> capabilityIssues(Obligation obligation) {
        Objects.requireNonNull(obligation, "obligation");
        List<String> issues = new ArrayList<>();
        if (!supportedSchemas.contains(obligation.schema())) {
            issues.add("UNSUPPORTED_SCHEMA:" + obligation.schema());
        }
        obligation.theories().stream()
            .filter(theory -> !supportedTheories.contains(theory))
            .forEach(theory -> issues.add("UNSUPPORTED_THEORY:" + theory.name()));
        if (!supportedGoalRelations.contains(obligation.goal().relation())) {
            issues.add("UNSUPPORTED_GOAL_RELATION:" + obligation.goal().relation().name());
        }
        if (!supportedEvidence.contains(obligation.requestedEvidence())) {
            issues.add("UNSUPPORTED_EVIDENCE:" + obligation.requestedEvidence().name());
        }
        obligation.declarations().stream()
            .filter(declaration -> !supportedSorts.contains(declaration.sort()))
            .forEach(declaration -> issues.add(
                "UNSUPPORTED_SORT:" + declaration.name() + ':' + declaration.sort().name()));
        obligation.assumptions().stream()
            .filter(assumption -> !supportedAssumptionRelations.contains(assumption.relation()))
            .forEach(assumption -> issues.add(
                "UNSUPPORTED_ASSUMPTION_RELATION:" + assumption.relation().name()));

        Set<BinaryOperator> operators = new LinkedHashSet<>();
        boolean[] calls = new boolean[] {false};
        inspect(obligation.goal().left(), operators, calls);
        inspect(obligation.goal().right(), operators, calls);
        obligation.assumptions().forEach(assumption -> {
            inspect(assumption.left(), operators, calls);
            if (assumption.right() != null) {
                inspect(assumption.right(), operators, calls);
            }
        });
        operators.stream()
            .filter(operator -> !supportedOperators.contains(operator))
            .forEach(operator -> issues.add("UNSUPPORTED_OPERATOR:" + operator.name()));
        if (calls[0] && !supportsCalls) {
            issues.add("UNSUPPORTED_CALL_EXPRESSION");
        }
        return issues.stream().distinct().sorted().toList();
    }

    public boolean canContributeTo(SolverObjective objective) {
        return roles.stream().anyMatch(role -> role.contributesTo(objective));
    }

    public boolean canConfirm(SolverObjective objective) {
        return roles.stream().anyMatch(role -> role.canConfirm(objective));
    }

    public boolean canRefute() {
        return roles.stream().anyMatch(BackendRole::canRefute);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("backendId", backendId)
            .property("backendVersion", backendVersion)
            .stringArray("supportedSchemas", supportedSchemas)
            .stringArray("supportedTheories", names(supportedTheories))
            .stringArray("supportedGoalRelations", names(supportedGoalRelations))
            .stringArray("supportedAssumptionRelations", names(supportedAssumptionRelations))
            .stringArray("supportedSorts", names(supportedSorts))
            .stringArray("supportedOperators", names(supportedOperators))
            .property("supportsCalls", supportsCalls)
            .stringArray("supportedEvidence", names(supportedEvidence))
            .stringArray("roles", names(roles))
            .property("costClass", costClass.name())
            .property("estimatedCostUnits", estimatedCostUnits)
            .property("deterministic", deterministic)
            .property("reproducible", reproducible)
            .property("availability", availability.name())
            .property("configurationHash", configurationHash)
            .property("semanticHash", semanticHash)
            .endObject()
            .toString();
    }

    private static void inspect(
        Expression expression,
        Set<BinaryOperator> operators,
        boolean[] calls
    ) {
        if (expression instanceof Binary binary) {
            operators.add(binary.operator());
            inspect(binary.left(), operators, calls);
            inspect(binary.right(), operators, calls);
        } else if (expression instanceof Call call) {
            calls[0] = true;
            call.arguments().forEach(argument -> inspect(argument, operators, calls));
        }
    }

    private static String semanticHash(
        String backendId,
        String backendVersion,
        List<String> schemas,
        List<Theory> theories,
        List<Relation> goals,
        List<Relation> assumptions,
        List<Sort> sorts,
        List<BinaryOperator> operators,
        boolean supportsCalls,
        List<RequestedEvidence> evidence,
        List<BackendRole> roles,
        CostClass costClass,
        long cost,
        boolean deterministic,
        boolean reproducible,
        String configurationHash
    ) {
        return SolverIr.sha256(
            "backend=" + backendId + '@' + backendVersion
                + "\nschemas=" + schemas
                + "\ntheories=" + theories
                + "\ngoals=" + goals
                + "\nassumptions=" + assumptions
                + "\nsorts=" + sorts
                + "\noperators=" + operators
                + "\ncalls=" + supportsCalls
                + "\nevidence=" + evidence
                + "\nroles=" + roles
                + "\ncost=" + costClass.name() + ':' + cost
                + "\ndeterministic=" + deterministic
                + "\nreproducible=" + reproducible
                + "\nconfiguration=" + configurationHash);
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted()
            .toList();
    }

    private static <E extends Enum<E>> List<E> sortedEnums(List<E> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
    }

    private static List<String> names(List<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
