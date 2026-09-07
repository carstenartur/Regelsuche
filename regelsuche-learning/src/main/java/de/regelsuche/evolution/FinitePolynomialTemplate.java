package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.LearnedPlan;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable, non-authorizing template shared by trace learning and strategy selection. */
public final class FinitePolynomialTemplate {
    public static final String REVISION = "regelsuche.finite-polynomial-template/v1";
    public static final String VARIABLE_SLOT = "@v";

    public enum Origin { DECLARED_GRAMMAR, VERIFIED_TRACE_DERIVED }
    public enum Applicability { ANY_SUPPORTED_UNIVARIATE, EXACT_SOURCE_SHAPE }

    private final String id;
    private final String expression;
    private final List<HoleDomain> domains;
    private final Origin origin;
    private final String sourceShape;
    private final String formationHash;
    private final List<String> provenanceRoots;
    private final List<String> formationInputIdentities;
    private final List<String> formationStateIdentities;

    private FinitePolynomialTemplate(String id, String expression, List<HoleDomain> domains,
                                    Origin origin, String sourceShape, LearnedPlan formation) {
        this.id = SchematicProofPlan.requireId(id, "template id");
        if (id.length() > 64 || expression == null || expression.isBlank() || expression.length() > 16_384
                || !StandardCharsets.UTF_8.newEncoder().canEncode(expression)
                || expression.chars().anyMatch(Character::isISOControl)
                || !expression.contains(VARIABLE_SLOT)) {
            throw new IllegalArgumentException("invalid finite template or variable slot @v");
        }
        this.expression = expression.trim().replaceAll("\\s+", " ");
        this.domains = List.copyOf(domains).stream().sorted(Comparator.comparing(HoleDomain::holeId)).toList();
        if (this.domains.isEmpty() || this.domains.size() > 12
                || this.domains.stream().map(HoleDomain::holeId).distinct().count() != this.domains.size()
                || assignmentCount() > 100_000) {
            throw new IllegalArgumentException("invalid or excessive finite template domains");
        }
        this.origin = origin;
        this.sourceShape = sourceShape;
        this.formationHash = formation == null ? "" : formation.contentHash();
        this.provenanceRoots = formation == null ? List.of() : formation.trainingRoots();
        this.formationInputIdentities = formation == null ? List.of() : formation.trainingInputIdentities();
        this.formationStateIdentities = formation == null ? List.of() : formation.formationStateIdentities();
    }

    public static FinitePolynomialTemplate declared(String id, String expression, List<HoleDomain> domains) {
        return new FinitePolynomialTemplate(id, expression, domains, Origin.DECLARED_GRAMMAR, "", null);
    }

    // Only a verifier-backed, privately issued LearnedPlan can supply this origin.
    static FinitePolynomialTemplate learned(LearnedPlan formation, int stageIndex) {
        Objects.requireNonNull(formation, "formation");
        var stage = formation.stageData(stageIndex);
        String digest = formation.contentHash().substring("sha256:".length());
        return new FinitePolynomialTemplate("trace-" + digest.substring(0, 40) + "-" + stageIndex,
            stage.ansatzTemplate(), stage.holeDomains(), Origin.VERIFIED_TRACE_DERIVED,
            stage.sourceShape(), formation);
    }

    public String id() { return id; }
    public String expression() { return expression; }
    public List<HoleDomain> domains() { return domains; }
    public Origin origin() { return origin; }
    public Applicability applicability() {
        return origin == Origin.DECLARED_GRAMMAR
            ? Applicability.ANY_SUPPORTED_UNIVARIATE : Applicability.EXACT_SOURCE_SHAPE;
    }
    public String sourceShape() { return sourceShape; }
    public String formationHash() { return formationHash; }
    public List<String> provenanceRoots() { return provenanceRoots; }
    public List<String> formationInputIdentities() { return formationInputIdentities; }
    public List<String> formationStateIdentities() { return formationStateIdentities; }

    public long assignmentCount() {
        long count = 1;
        for (HoleDomain domain : domains) {
            if (count > 100_000L / domain.values().size()) {
                throw new IllegalArgumentException("template exceeds 100000 assignments");
            }
            count *= domain.values().size();
        }
        return count;
    }

    String instantiateVariable(String variable) { return expression.replace(VARIABLE_SLOT, variable); }

    record ShapeCheck(boolean matches, long visitedNodes) {}

    ShapeCheck checkShape(ExactFinitePolynomialInput.Projection input) {
        return applicability() == Applicability.ANY_SUPPORTED_UNIVARIATE ? new ShapeCheck(true, 0)
            : ExactFinitePolynomialTraceLearner.checkSourceShape(sourceShape, input.parsed());
    }

    public String toCanonicalJson() {
        JsonWriter writer = new JsonWriter().beginObject();
        writeJson(writer);
        return writer.endObject().toString();
    }

    void writeJson(JsonWriter writer) {
        writer.property("schema", REVISION).property("authority", "REQUIRES_FRESH_VERIFICATION")
            .property("id", id).property("expression", expression).property("variableSlot", VARIABLE_SLOT)
            .property("origin", origin.name()).property("applicability", applicability().name())
            .property("sourceShape", sourceShape)
            .property("applicabilityRevision", origin == Origin.VERIFIED_TRACE_DERIVED
                ? ExactFinitePolynomialTraceLearner.REVISION : ExactFinitePolynomialInput.REVISION)
            .property("formationHash", formationHash).stringArray("provenanceRoots", provenanceRoots)
            .property("inputIdentityRevision", ExactFinitePolynomialInput.REVISION)
            .property("inputViewBudget", ExactFinitePolynomialInput.BUDGET.canonicalMaterial())
            .stringArray("formationInputIdentities", formationInputIdentities)
            .stringArray("formationStateIdentities", formationStateIdentities)
            .array("domains", values -> domains.forEach(domain -> values.objectValue(value ->
                value.property("id", domain.holeId()).property("kind", domain.kind().name())
                    .array("values", scalars -> domain.values().forEach(scalar -> scalars.value(scalar.canonicalText()))))));
    }

    public String contentHash() { return SchematicProofPlan.hash(toCanonicalJson()); }
}
