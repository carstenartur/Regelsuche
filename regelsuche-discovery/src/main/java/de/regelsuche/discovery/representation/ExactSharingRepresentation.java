package de.regelsuche.discovery.representation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Exact definition/reference overlay for one material repeated-structure
 * candidate.
 *
 * <p>The overlay introduces no new expression syntax. It is accepted only when
 * the candidate can be regenerated from the normalized source, all selected
 * occurrences have the same exact AST presentation, their paths do not overlap,
 * and substituting the retained definition at every path therefore expands to
 * the unchanged source tree.</p>
 */
public record ExactSharingRepresentation(
    String schema,
    String identity,
    String candidateIdentity,
    String sourceExpression,
    String semanticValueKey,
    String definitionExpression,
    List<ExpressionOccurrencePath> referencePaths,
    RepeatedStructureExtractionCandidate.Policy policy,
    RepeatedStructureExtractionCandidate.SharingCost sharingCost,
    String sourceTreeHash,
    String expandedTreeHash,
    String claimBoundary
) {
    public static final String SCHEMA =
        "regelsuche.exact-sharing-representation/v1";
    public static final String CLAIM_BOUNDARY =
        "Exact normalized-AST definition/reference reconstruction and "
            + "sharing-cost evidence; not new expression syntax, an "
            + "executable rewrite, proof of usefulness, held-out superiority "
            + "or external mathematical novelty.";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    public ExactSharingRepresentation {
        schema = requireText(schema, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported exact-sharing schema: " + schema);
        }
        identity = requireHash(identity, "identity");
        candidateIdentity = requireHash(
            candidateIdentity, "candidateIdentity");
        sourceExpression = normalize(sourceExpression);
        semanticValueKey = requireText(
            semanticValueKey, "semanticValueKey");
        definitionExpression = normalize(definitionExpression);
        referencePaths = Objects.requireNonNull(
            referencePaths, "referencePaths").stream()
            .map(path -> Objects.requireNonNull(path, "referencePath"))
            .sorted()
            .toList();
        policy = Objects.requireNonNull(policy, "policy");
        sharingCost = Objects.requireNonNull(sharingCost, "sharingCost");
        sourceTreeHash = requireHash(sourceTreeHash, "sourceTreeHash");
        expandedTreeHash = requireHash(expandedTreeHash, "expandedTreeHash");
        claimBoundary = requireText(claimBoundary, "claimBoundary");
        if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
            throw new IllegalArgumentException(
                "unsupported exact-sharing claim boundary");
        }

        RepeatedStructureExtractionCandidate candidate = regenerate(
            sourceExpression,
            policy,
            candidateIdentity
        );
        verifyCandidate(
            candidate,
            semanticValueKey,
            definitionExpression,
            referencePaths,
            sharingCost
        );
        verifyExactOccurrences(
            sourceExpression,
            definitionExpression,
            referencePaths
        );

        String expectedTreeHash = treeHash(sourceExpression);
        if (!expectedTreeHash.equals(sourceTreeHash)
                || !sourceTreeHash.equals(expandedTreeHash)) {
            throw new IllegalArgumentException(
                "exact-sharing tree hashes do not reconstruct the source");
        }
        String expectedIdentity = identityFor(
            candidateIdentity,
            sourceExpression,
            semanticValueKey,
            definitionExpression,
            referencePaths,
            policy,
            sharingCost,
            sourceTreeHash,
            expandedTreeHash
        );
        if (!expectedIdentity.equals(identity)) {
            throw new IllegalArgumentException(
                "identity does not match exact-sharing evidence");
        }
    }

    public static ExactSharingRepresentation create(
        RepeatedStructureExtractionCandidate candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        List<ExpressionOccurrencePath> paths = candidate.occurrences().stream()
            .map(RepeatedStructureExtractionCandidate.Occurrence::path)
            .sorted()
            .toList();
        String source = normalize(candidate.sourceExpression());
        String sourceHash = treeHash(source);
        String identity = identityFor(
            candidate.identity(),
            source,
            candidate.semanticValueKey(),
            candidate.representativeExpression(),
            paths,
            candidate.policy(),
            candidate.sharingCost(),
            sourceHash,
            sourceHash
        );
        return new ExactSharingRepresentation(
            SCHEMA,
            identity,
            candidate.identity(),
            source,
            candidate.semanticValueKey(),
            candidate.representativeExpression(),
            paths,
            candidate.policy(),
            candidate.sharingCost(),
            sourceHash,
            sourceHash,
            CLAIM_BOUNDARY
        );
    }

    /** Exact normalized tree recovered after expanding the sharing overlay. */
    public String reconstructSourceExpression() {
        verifyExactOccurrences(
            sourceExpression,
            definitionExpression,
            referencePaths
        );
        return sourceExpression;
    }

    public String toCanonicalJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to render exact-sharing evidence", exception);
        }
    }

    private static RepeatedStructureExtractionCandidate regenerate(
        String sourceExpression,
        RepeatedStructureExtractionCandidate.Policy policy,
        String candidateIdentity
    ) {
        return new RepeatedStructureExtractor(policy)
            .extract(sourceExpression).stream()
            .filter(candidate -> candidate.identity().equals(candidateIdentity))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "candidate cannot be regenerated from the source"));
    }

    private static void verifyCandidate(
        RepeatedStructureExtractionCandidate candidate,
        String semanticValueKey,
        String definitionExpression,
        List<ExpressionOccurrencePath> referencePaths,
        RepeatedStructureExtractionCandidate.SharingCost sharingCost
    ) {
        if (!candidate.material()) {
            throw new IllegalArgumentException(
                "exact sharing requires a material extraction candidate");
        }
        if (!candidate.semanticValueKey().equals(semanticValueKey)
                || !candidate.representativeExpression().equals(
                    definitionExpression)
                || !candidate.sharingCost().equals(sharingCost)) {
            throw new IllegalArgumentException(
                "exact-sharing evidence differs from its candidate");
        }
        List<ExpressionOccurrencePath> candidatePaths =
            candidate.occurrences().stream()
                .map(RepeatedStructureExtractionCandidate.Occurrence::path)
                .sorted()
                .toList();
        if (!candidatePaths.equals(referencePaths)) {
            throw new IllegalArgumentException(
                "reference paths differ from the extraction candidate");
        }
        if (candidate.occurrences().stream().anyMatch(occurrence ->
                !occurrence.expression().equals(definitionExpression))) {
            throw new IllegalArgumentException(
                "exact sharing requires presentation-identical occurrences");
        }
    }

    private static void verifyExactOccurrences(
        String sourceExpression,
        String definitionExpression,
        List<ExpressionOccurrencePath> referencePaths
    ) {
        rejectDuplicateOrOverlappingPaths(referencePaths);
        Expr root = parse(sourceExpression);
        Expr definition = parse(definitionExpression);
        for (ExpressionOccurrencePath path : referencePaths) {
            if (!atPath(root, path).equals(definition)) {
                throw new IllegalArgumentException(
                    "definition does not match source occurrence " + path);
            }
        }
    }

    private static void rejectDuplicateOrOverlappingPaths(
        List<ExpressionOccurrencePath> paths
    ) {
        if (new HashSet<>(paths).size() != paths.size()) {
            throw new IllegalArgumentException(
                "exact-sharing reference paths must be unique");
        }
        for (int left = 0; left < paths.size(); left++) {
            for (int right = left + 1; right < paths.size(); right++) {
                if (isStrictPrefix(paths.get(left), paths.get(right))
                        || isStrictPrefix(
                            paths.get(right), paths.get(left))) {
                    throw new IllegalArgumentException(
                        "exact-sharing reference paths overlap");
                }
            }
        }
    }

    private static boolean isStrictPrefix(
        ExpressionOccurrencePath prefix,
        ExpressionOccurrencePath path
    ) {
        List<Integer> prefixIndexes = prefix.childIndexes();
        List<Integer> pathIndexes = path.childIndexes();
        return prefixIndexes.size() < pathIndexes.size()
            && pathIndexes.subList(0, prefixIndexes.size())
                .equals(prefixIndexes);
    }

    private static Expr atPath(
        Expr root,
        ExpressionOccurrencePath path
    ) {
        Expr current = root;
        for (Integer childIndex : path.childIndexes()) {
            if (current instanceof BinaryExpr binary) {
                current = switch (childIndex) {
                    case 0 -> binary.left();
                    case 1 -> binary.right();
                    default -> throw invalidPath(path);
                };
            } else if (current instanceof FunctionExpr function
                    && childIndex < function.arguments().size()) {
                current = function.arguments().get(childIndex);
            } else {
                throw invalidPath(path);
            }
        }
        return current;
    }

    private static IllegalArgumentException invalidPath(
        ExpressionOccurrencePath path
    ) {
        return new IllegalArgumentException(
            "invalid exact-sharing source path: " + path);
    }

    private static String identityFor(
        String candidateIdentity,
        String sourceExpression,
        String semanticValueKey,
        String definitionExpression,
        List<ExpressionOccurrencePath> referencePaths,
        RepeatedStructureExtractionCandidate.Policy policy,
        RepeatedStructureExtractionCandidate.SharingCost sharingCost,
        String sourceTreeHash,
        String expandedTreeHash
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA);
        append(descriptor, candidateIdentity);
        append(descriptor, sourceExpression);
        append(descriptor, semanticValueKey);
        append(descriptor, definitionExpression);
        append(descriptor, Integer.toString(referencePaths.size()));
        referencePaths.forEach(path ->
            append(descriptor, path.canonical()));
        append(descriptor, policy.contentHash());
        appendCost(descriptor, sharingCost);
        append(descriptor, sourceTreeHash);
        append(descriptor, expandedTreeHash);
        append(descriptor, CLAIM_BOUNDARY);
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static void appendCost(
        StringBuilder descriptor,
        RepeatedStructureExtractionCandidate.SharingCost cost
    ) {
        append(descriptor, Integer.toString(cost.repeatedTreeCost()));
        append(descriptor, Integer.toString(cost.definitionTreeCost()));
        append(descriptor, Integer.toString(cost.referenceTreeCost()));
        append(descriptor, Integer.toString(cost.explicitSharingTreeCost()));
        append(descriptor, Integer.toString(cost.netAstNodeSavings()));
        append(descriptor, Integer.toString(cost.minimumNetSavings()));
        append(descriptor, Boolean.toString(cost.material()));
    }

    private static String treeHash(String normalizedExpression) {
        return KnownStructureCatalog.sha256(
            SCHEMA + "/tree\n" + normalizedExpression);
    }

    private static String normalize(String expression) {
        return ExpressionFormatter.format(parse(requireText(
            expression, "expression")));
    }

    private static Expr parse(String expression) {
        return new ExpressionParser().parseTerm(expression);
    }

    private static void append(StringBuilder descriptor, String value) {
        KnownStructureCatalog.appendCanonicalField(descriptor, value);
    }

    private static String requireText(String value, String field) {
        return RepresentationCandidateAssessment.requireText(value, field);
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 identity");
        }
        return normalized;
    }
}
