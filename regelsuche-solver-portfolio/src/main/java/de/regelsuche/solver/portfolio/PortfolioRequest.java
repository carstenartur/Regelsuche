package de.regelsuche.solver.portfolio;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.util.Objects;

/** Hash-linked execution objective independent of concrete backend classes. */
public record PortfolioRequest(
    String schema,
    Obligation obligation,
    SolverObjective objective,
    PortfolioPolicy policy,
    PortfolioBudget budget,
    String configurationId,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.solver-portfolio-request/v1";

    public PortfolioRequest {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported portfolio request schema");
        }
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(budget, "budget");
        if (configurationId == null || configurationId.isBlank()) {
            throw new IllegalArgumentException("configurationId must not be blank");
        }
        requireSha(contentHash, "contentHash");
        String expected = hash(
            obligation, objective, policy, budget, configurationId);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("portfolio request hash does not match fields");
        }
    }

    public static PortfolioRequest create(
        Obligation obligation,
        SolverObjective objective,
        PortfolioPolicy policy,
        PortfolioBudget budget,
        String configurationId
    ) {
        return new PortfolioRequest(
            SCHEMA, obligation, objective, policy, budget, configurationId,
            hash(obligation, objective, policy, budget, configurationId));
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("obligationHash", obligation.contentHash())
            .property("objective", objective.name())
            .property("policy", policy.name())
            .property("budgetHash", budget.configurationHash())
            .property("configurationId", configurationId)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static String hash(
        Obligation obligation,
        SolverObjective objective,
        PortfolioPolicy policy,
        PortfolioBudget budget,
        String configurationId
    ) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(budget, "budget");
        if (configurationId == null || configurationId.isBlank()) {
            throw new IllegalArgumentException("configurationId must not be blank");
        }
        return SolverIr.sha256(
            SCHEMA
                + "\nobligation=" + obligation.contentHash()
                + "\nobjective=" + objective.name()
                + "\npolicy=" + policy.name()
                + "\nbudget=" + budget.configurationHash()
                + "\nconfiguration=" + configurationId);
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
