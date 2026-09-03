package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Binding;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Emits the canonical retained-byte projection of one validated exact finite
 * polynomial plan run.
 *
 * <p>The projection is not parsed into authority. A later replay-confirmation
 * verifier regenerates these exact bytes from frozen inputs and compares both
 * the bytes and their metadata-bound artifact reference.</p>
 */
public final class ExactFinitePolynomialPlanRunArtifactCodec {
    public static final String SCHEMA =
        "regelsuche.exact-finite-polynomial-plan-run-artifact/v1";
    public static final int MAX_CANONICAL_BYTES = 1_000_000;
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            SCHEMA,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLUTION_IDENTITY_REVISION,
            "normalized-source-template-and-typed-domain-projection",
            "complete-retained-solution-binding-projection",
            "resolved-candidate-solution-resolution-link-projection",
            "artifact-content-hash-over-json-without-self-hash"));

    private ExactFinitePolynomialPlanRunArtifactCodec() {}

    public static String toCanonicalJson(
        ExactFinitePolynomialPlanRun planRun
    ) {
        ExactFinitePolynomialPlanRun run = Objects.requireNonNull(
            planRun,
            "planRun");
        String payload = render(run, null);
        requireSize(payload);
        String artifactContentHash = SchematicProofPlan.hash(payload);
        String canonicalJson = render(run, artifactContentHash);
        requireSize(canonicalJson);
        return canonicalJson;
    }

    public static String artifactContentHash(
        ExactFinitePolynomialPlanRun planRun
    ) {
        ExactFinitePolynomialPlanRun run = Objects.requireNonNull(
            planRun,
            "planRun");
        String payload = render(run, null);
        requireSize(payload);
        return SchematicProofPlan.hash(payload);
    }

    private static String render(
        ExactFinitePolynomialPlanRun run,
        String artifactContentHash
    ) {
        SearchResult result = run.solverResult();
        List<Solution> solutions = result.solutions().stream()
            .sorted(Comparator.comparing(Solution::bindingKey))
            .toList();
        List<ExactFinitePolynomialResolvedCandidate> candidates =
            run.candidates().stream()
                .sorted(Comparator.comparing(
                    ExactFinitePolynomialResolvedCandidate::contentHash))
                .toList();

        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("codecRevisionHash", REVISION_HASH)
            .property("resolverId", run.resolverId())
            .property("resolverRevisionHash", run.resolverRevisionHash())
            .property("planHash", run.planHash())
            .property("planRunHash", run.contentHash())
            .property("solverId", result.solverId())
            .property("solverRevisionHash", result.solverRevisionHash())
            .property("solverResultHash", result.contentHash())
            .property("sourceExpression", result.sourceExpression())
            .property("ansatzTemplate", result.ansatzTemplate())
            .array("holeDomains", writer ->
                result.holeDomains().forEach(domain ->
                    writeDomain(writer, domain)))
            .property("totalAssignments", result.totalAssignments())
            .property(
                "evaluatedAssignments",
                result.evaluatedAssignments())
            .property("matchingAssignments", result.matchingAssignments())
            .property(
                "retainedSolutionLimit",
                result.retainedSolutionLimit())
            .property("solverStatus", result.status().name())
            .property("runStatus", run.status().name())
            .array("solutions", writer ->
                solutions.forEach(solution ->
                    writeSolution(writer, solution)))
            .array("resolvedCandidates", writer ->
                candidates.forEach(candidate ->
                    writeCandidate(writer, candidate)));
        if (artifactContentHash != null) {
            json.property("artifactContentHash", artifactContentHash);
        }
        return json.endObject().toString();
    }

    private static void writeDomain(
        JsonWriter writer,
        HoleDomain domain
    ) {
        writer.objectValue(object -> object
            .property("holeId", domain.holeId())
            .property("kind", domain.kind().name())
            .stringArray(
                "values",
                domain.values().stream()
                    .map(value -> value.canonicalText())
                    .toList()));
    }

    private static void writeSolution(
        JsonWriter writer,
        Solution solution
    ) {
        writer.objectValue(object -> object
            .property("solutionHash", solution.contentHash())
            .array("bindings", bindings ->
                solution.bindings().forEach(binding ->
                    writeBinding(bindings, binding)))
            .property(
                "instantiatedExpression",
                solution.instantiatedExpression())
            .property("exactNormalForm", solution.exactNormalForm()));
    }

    private static void writeBinding(
        JsonWriter writer,
        Binding binding
    ) {
        writer.objectValue(object -> object
            .property("holeId", binding.holeId())
            .property("kind", binding.kind().name())
            .property("value", binding.value().canonicalText()));
    }

    private static void writeCandidate(
        JsonWriter writer,
        ExactFinitePolynomialResolvedCandidate candidate
    ) {
        writer.objectValue(object -> object
            .property("candidateHash", candidate.contentHash())
            .property("solutionHash", candidate.solution().contentHash())
            .property(
                "resolutionHash",
                candidate.resolution().contentHash())
            .property(
                "resolutionPlanHash",
                candidate.resolution().planHash())
            .property(
                "resolutionState",
                candidate.resolution().state().name())
            .property("solverResultHash", candidate.solverResultHash()));
    }

    private static void requireSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length
                > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException(
                "finite polynomial plan-run artifact exceeds byte limit");
        }
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
        }
        return result.toString();
    }
}
