package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_BLOCKED_BY_INTRODUCED_SYMBOLS;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_BLOCKED_BY_STRUCTURAL_REGRESSION;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_MATERIAL_MULTI_DIMENSIONAL;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_NON_MATERIAL;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_DOWNSTREAM_CAPABILITY_BRIDGE;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_KNOWN_SUBFORM_BRIDGE;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_KNOWN_WHOLE_FORM_BRIDGE;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_NO_MATERIAL_REPRESENTATION_GAIN;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_SUBEXPRESSION_COMPRESSION;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_WHOLE_EXPRESSION_COMPRESSION;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_INTRODUCED_FUNCTION_SYMBOLS;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_INTRODUCED_VARIABLE_SYMBOLS;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_STRUCTURAL_COMPRESSION_REGRESSION;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_VALIDATION_BELOW_SYMBOLIC_CONFIRMATION;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Assesses compression and known-structure signals independently of validation. */
public final class RepresentationCandidateAssessor {
    private static final int MINIMUM_IMPROVED_DIMENSIONS = 2;

    private final ExpressionParser parser = new ExpressionParser();
    private final SemanticDescriptionMeasurer measurer =
        new SemanticDescriptionMeasurer();
    private final KnownStructureMatcher matcher;

    public RepresentationCandidateAssessor(KnownStructureCatalog catalog) {
        matcher = new KnownStructureMatcher(catalog);
    }

    public RepresentationCandidateAssessment assess(
        RepresentationCandidateProposal proposal
    ) {
        Objects.requireNonNull(proposal, "proposal");
        Expr sourceRoot = parser.parseTerm(proposal.sourceExpression());
        Expr candidateRoot = parser.parseTerm(proposal.candidateExpression());
        boolean wholeExpression = proposal.wholeExpression();

        if (!wholeExpression && !sameContextOutsideOccurrence(
                sourceRoot, candidateRoot, proposal.occurrencePath(), 0)) {
            throw new IllegalArgumentException(
                "source and candidate differ outside occurrence "
                    + proposal.occurrencePath());
        }

        SemanticDescriptionMetrics wholeSource = measurer.measure(sourceRoot);
        SemanticDescriptionMetrics wholeCandidate = measurer.measure(candidateRoot);
        SemanticDescriptionMetrics scopedSource = wholeExpression
            ? wholeSource
            : measurer.measure(resolve(sourceRoot, proposal.occurrencePath()));
        SemanticDescriptionMetrics scopedCandidate = wholeExpression
            ? wholeCandidate
            : measurer.measure(resolve(candidateRoot, proposal.occurrencePath()));
        SemanticCompressionDelta wholeDelta =
            SemanticCompressionDelta.between(wholeSource, wholeCandidate);
        SemanticCompressionDelta scopedDelta =
            SemanticCompressionDelta.between(scopedSource, scopedCandidate);

        List<String> introducedVariables = difference(
            wholeCandidate.variableSymbols(), wholeSource.variableSymbols());
        List<String> introducedFunctions = difference(
            wholeCandidate.functionSymbols(), wholeSource.functionSymbols());
        String compressionStatus = compressionStatus(
            scopedDelta, wholeDelta, introducedVariables, introducedFunctions);
        boolean materialCompression =
            COMPRESSION_MATERIAL_MULTI_DIMENSIONAL.equals(compressionStatus);

        List<KnownStructureMatch> sourceMatches = matcher.match(sourceRoot);
        List<KnownStructureMatch> candidateMatches = matcher.match(candidateRoot);
        List<KnownStructureMatch> newMatches = newlyExposed(
            sourceMatches, candidateMatches);
        List<KnownStructureConsequenceUnlock> unlocks = unlocks(
            sourceMatches, newMatches, proposal.assumptions());
        List<String> types = candidateTypes(
            wholeExpression, materialCompression, newMatches, unlocks);
        boolean materialGain = materialCompression || !unlocks.isEmpty();
        boolean claimEligible = materialGain && proposal.validationStatus().atLeast(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED);

        return new RepresentationCandidateAssessment(
            proposal,
            matcher.catalogHash(),
            wholeSource,
            wholeCandidate,
            scopedSource,
            scopedCandidate,
            wholeDelta,
            scopedDelta,
            compressionStatus,
            sourceMatches,
            candidateMatches,
            newMatches,
            unlocks,
            types,
            introducedVariables,
            introducedFunctions,
            warnings(
                proposal,
                compressionStatus,
                introducedVariables,
                introducedFunctions,
                newMatches,
                unlocks
            ),
            materialGain,
            claimEligible
        );
    }

    private static String compressionStatus(
        SemanticCompressionDelta scoped,
        SemanticCompressionDelta whole,
        List<String> introducedVariables,
        List<String> introducedFunctions
    ) {
        if (scoped.improvedDimensions().size() < MINIMUM_IMPROVED_DIMENSIONS) {
            return COMPRESSION_NON_MATERIAL;
        }
        if (!introducedVariables.isEmpty() || !introducedFunctions.isEmpty()) {
            return COMPRESSION_BLOCKED_BY_INTRODUCED_SYMBOLS;
        }
        return scoped.hasStructuralRegression() || whole.hasStructuralRegression()
            ? COMPRESSION_BLOCKED_BY_STRUCTURAL_REGRESSION
            : COMPRESSION_MATERIAL_MULTI_DIMENSIONAL;
    }

    private static List<String> candidateTypes(
        boolean wholeExpression,
        boolean materialCompression,
        List<KnownStructureMatch> newMatches,
        List<KnownStructureConsequenceUnlock> unlocks
    ) {
        TreeSet<String> types = new TreeSet<>();
        if (materialCompression) {
            types.add(wholeExpression
                ? TYPE_WHOLE_EXPRESSION_COMPRESSION
                : TYPE_SUBEXPRESSION_COMPRESSION);
        }
        if (newMatches.stream().anyMatch(KnownStructureMatch::wholeExpression)) {
            types.add(TYPE_KNOWN_WHOLE_FORM_BRIDGE);
        }
        if (newMatches.stream().anyMatch(match -> !match.wholeExpression())) {
            types.add(TYPE_KNOWN_SUBFORM_BRIDGE);
        }
        if (!unlocks.isEmpty()) {
            types.add(TYPE_DOWNSTREAM_CAPABILITY_BRIDGE);
        }
        if (types.isEmpty()) {
            types.add(TYPE_NO_MATERIAL_REPRESENTATION_GAIN);
        }
        return List.copyOf(types);
    }

    private static List<KnownStructureMatch> newlyExposed(
        List<KnownStructureMatch> sourceMatches,
        List<KnownStructureMatch> candidateMatches
    ) {
        Set<String> existing = new HashSet<>();
        sourceMatches.forEach(match -> existing.add(match.identity()));
        return candidateMatches.stream()
            .filter(match -> !existing.contains(match.identity()))
            .sorted(Comparator
                .comparing(KnownStructureMatch::structureId)
                .thenComparing(KnownStructureMatch::occurrencePath)
                .thenComparing(KnownStructureMatch::identity))
            .toList();
    }

    private static List<KnownStructureConsequenceUnlock> unlocks(
        List<KnownStructureMatch> sourceMatches,
        List<KnownStructureMatch> newMatches,
        List<String> assumptions
    ) {
        Set<String> activeAssumptions = Set.copyOf(assumptions);
        Set<String> existing = new HashSet<>();
        sourceMatches.stream()
            .filter(match -> assumptionsSatisfied(match, activeAssumptions))
            .forEach(match -> match.consequenceIds().forEach(consequence ->
                existing.add(opportunityIdentity(match, consequence))));

        TreeSet<KnownStructureConsequenceUnlock> unlocks = new TreeSet<>();
        newMatches.stream()
            .filter(match -> assumptionsSatisfied(match, activeAssumptions))
            .forEach(match -> match.consequenceIds().forEach(consequence -> {
                KnownStructureConsequenceUnlock unlock =
                    new KnownStructureConsequenceUnlock(
                        consequence,
                        match.structureId(),
                        match.occurrencePath(),
                        match.identity()
                    );
                if (!existing.contains(unlock.opportunityIdentity())) {
                    unlocks.add(unlock);
                }
            }));
        return List.copyOf(unlocks);
    }

    private static String opportunityIdentity(
        KnownStructureMatch match,
        String consequence
    ) {
        return consequence + "|" + match.identity();
    }

    private static boolean assumptionsSatisfied(
        KnownStructureMatch match,
        Set<String> activeAssumptions
    ) {
        return activeAssumptions.containsAll(match.requiredAssumptions());
    }

    private static List<String> warnings(
        RepresentationCandidateProposal proposal,
        String compressionStatus,
        List<String> introducedVariables,
        List<String> introducedFunctions,
        List<KnownStructureMatch> newMatches,
        List<KnownStructureConsequenceUnlock> unlocks
    ) {
        TreeSet<String> warnings = new TreeSet<>();
        if (!introducedVariables.isEmpty()) {
            warnings.add(WARNING_INTRODUCED_VARIABLE_SYMBOLS);
        }
        if (!introducedFunctions.isEmpty()) {
            warnings.add(WARNING_INTRODUCED_FUNCTION_SYMBOLS);
        }
        if (COMPRESSION_BLOCKED_BY_STRUCTURAL_REGRESSION.equals(
                compressionStatus)) {
            warnings.add(WARNING_STRUCTURAL_COMPRESSION_REGRESSION);
        }
        Set<String> assumptions = Set.copyOf(proposal.assumptions());
        if (newMatches.stream().anyMatch(
                match -> !assumptionsSatisfied(match, assumptions))) {
            warnings.add(WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS);
        }
        if (!newMatches.isEmpty() && unlocks.isEmpty()) {
            warnings.add(WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY);
        }
        if (!proposal.validationStatus().atLeast(
                CandidateProofStatus.SYMBOLICALLY_VERIFIED)) {
            warnings.add(WARNING_VALIDATION_BELOW_SYMBOLIC_CONFIRMATION);
        }
        return List.copyOf(warnings);
    }

    private static List<String> difference(List<String> left, List<String> right) {
        TreeSet<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return List.copyOf(result);
    }

    private static Expr resolve(Expr root, ExpressionOccurrencePath path) {
        Expr current = root;
        for (int childIndex : path.childIndexes()) {
            List<Expr> children = children(current);
            if (childIndex >= children.size()) {
                throw invalidPath(path, childIndex);
            }
            current = children.get(childIndex);
        }
        return current;
    }

    private static boolean sameContextOutsideOccurrence(
        Expr source,
        Expr candidate,
        ExpressionOccurrencePath path,
        int pathIndex
    ) {
        if (pathIndex == path.childIndexes().size()) {
            return true;
        }
        if (!sameContainer(source, candidate)) {
            return false;
        }
        List<Expr> sourceChildren = children(source);
        List<Expr> candidateChildren = children(candidate);
        int selected = path.childIndexes().get(pathIndex);
        if (selected >= sourceChildren.size()) {
            return false;
        }
        for (int index = 0; index < sourceChildren.size(); index++) {
            if (index != selected
                    && !sourceChildren.get(index).equals(candidateChildren.get(index))) {
                return false;
            }
        }
        return sameContextOutsideOccurrence(
            sourceChildren.get(selected),
            candidateChildren.get(selected),
            path,
            pathIndex + 1
        );
    }

    private static boolean sameContainer(Expr source, Expr candidate) {
        if (source instanceof BinaryExpr left && candidate instanceof BinaryExpr right) {
            return left.operator() == right.operator();
        }
        if (source instanceof FunctionExpr left
                && candidate instanceof FunctionExpr right) {
            return left.name().equals(right.name())
                && left.arguments().size() == right.arguments().size();
        }
        return false;
    }

    private static List<Expr> children(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return List.of(binary.left(), binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments();
        }
        return List.of();
    }

    private static IllegalArgumentException invalidPath(
        ExpressionOccurrencePath path,
        int childIndex
    ) {
        return new IllegalArgumentException(
            "invalid child " + childIndex + " in occurrence path " + path);
    }
}
