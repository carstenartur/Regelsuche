package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Produces compression and known-structure evidence without conflating either
 * signal with mathematical validation.
 */
public final class RepresentationCandidateAssessor {
    private static final int MINIMUM_IMPROVED_DIMENSIONS = 2;

    private final ExpressionParser parser;
    private final SemanticDescriptionMeasurer measurer;
    private final KnownStructureMatcher knownStructureMatcher;

    public RepresentationCandidateAssessor(KnownStructureCatalog catalog) {
        this(
            new ExpressionParser(),
            new SemanticDescriptionMeasurer(),
            new KnownStructureMatcher(catalog)
        );
    }

    RepresentationCandidateAssessor(
        ExpressionParser parser,
        SemanticDescriptionMeasurer measurer,
        KnownStructureMatcher knownStructureMatcher
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.measurer = Objects.requireNonNull(measurer, "measurer");
        this.knownStructureMatcher = Objects.requireNonNull(
            knownStructureMatcher, "knownStructureMatcher");
    }

    public RepresentationCandidateAssessment assess(
        RepresentationCandidateProposal proposal
    ) {
        Objects.requireNonNull(proposal, "proposal");
        Expr sourceRoot = parser.parseTerm(proposal.sourceExpression());
        Expr candidateRoot = parser.parseTerm(proposal.candidateExpression());

        if (proposal.scope() == RepresentationScope.SUBEXPRESSION
                && !sameContextOutsideOccurrence(
                    sourceRoot,
                    candidateRoot,
                    proposal.occurrencePath(),
                    0
                )) {
            throw new IllegalArgumentException(
                "source and candidate differ outside the declared occurrence "
                    + proposal.occurrencePath().canonical());
        }

        Expr scopedSource = resolve(sourceRoot, proposal.occurrencePath());
        Expr scopedCandidate = resolve(candidateRoot, proposal.occurrencePath());

        SemanticDescriptionMetrics wholeSource = measurer.measure(sourceRoot);
        SemanticDescriptionMetrics wholeCandidate = measurer.measure(candidateRoot);
        SemanticDescriptionMetrics localSource = proposal.scope()
            == RepresentationScope.WHOLE_EXPRESSION
            ? wholeSource
            : measurer.measure(scopedSource);
        SemanticDescriptionMetrics localCandidate = proposal.scope()
            == RepresentationScope.WHOLE_EXPRESSION
            ? wholeCandidate
            : measurer.measure(scopedCandidate);

        SemanticCompressionDelta wholeDelta =
            SemanticCompressionDelta.between(wholeSource, wholeCandidate);
        SemanticCompressionDelta scopedDelta =
            SemanticCompressionDelta.between(localSource, localCandidate);

        List<String> introducedVariables = difference(
            wholeCandidate.variableSymbols(),
            wholeSource.variableSymbols());
        List<String> introducedFunctions = difference(
            wholeCandidate.functionSymbols(),
            wholeSource.functionSymbols());

        SemanticCompressionStatus compressionStatus = compressionStatus(
            scopedDelta,
            wholeDelta,
            introducedVariables,
            introducedFunctions
        );
        boolean materialCompression =
            compressionStatus
                == SemanticCompressionStatus.MATERIAL_MULTI_DIMENSIONAL;

        List<KnownStructureMatch> sourceMatches =
            knownStructureMatcher.match(sourceRoot);
        List<KnownStructureMatch> candidateMatches =
            knownStructureMatcher.match(candidateRoot);
        List<KnownStructureMatch> newMatches =
            newlyExposed(sourceMatches, candidateMatches);
        List<KnownStructureConsequenceUnlock> newlyUnlockedConsequences =
            newlyUnlockedConsequences(
                sourceMatches,
                newMatches,
                proposal.assumptions()
            );

        EnumSet<RepresentationCandidateType> types =
            EnumSet.noneOf(RepresentationCandidateType.class);
        if (materialCompression) {
            types.add(proposal.scope() == RepresentationScope.WHOLE_EXPRESSION
                ? RepresentationCandidateType.WHOLE_EXPRESSION_COMPRESSION
                : RepresentationCandidateType.SUBEXPRESSION_COMPRESSION);
        }
        if (newMatches.stream().anyMatch(
                KnownStructureMatch::wholeExpression)) {
            types.add(RepresentationCandidateType.KNOWN_WHOLE_FORM_BRIDGE);
        }
        if (newMatches.stream().anyMatch(
                match -> !match.wholeExpression())) {
            types.add(RepresentationCandidateType.KNOWN_SUBFORM_BRIDGE);
        }
        if (!newlyUnlockedConsequences.isEmpty()) {
            types.add(
                RepresentationCandidateType.DOWNSTREAM_CAPABILITY_BRIDGE);
        }
        if (types.isEmpty()) {
            types.add(
                RepresentationCandidateType.NO_MATERIAL_REPRESENTATION_GAIN);
        }

        boolean materialGain =
            materialCompression || !newlyUnlockedConsequences.isEmpty();
        boolean claimEligible = materialGain
            && proposal.validationStatus().atLeast(
                CandidateProofStatus.SYMBOLICALLY_VERIFIED);

        List<RepresentationAssessmentWarning> warnings = warnings(
            proposal,
            compressionStatus,
            introducedVariables,
            introducedFunctions,
            newMatches,
            newlyUnlockedConsequences,
            proposal.assumptions()
        );

        return new RepresentationCandidateAssessment(
            RepresentationCandidateAssessment.SCHEMA_VERSION,
            proposal,
            knownStructureMatcher.catalogHash(),
            wholeSource,
            wholeCandidate,
            localSource,
            localCandidate,
            wholeDelta,
            scopedDelta,
            compressionStatus,
            sourceMatches,
            candidateMatches,
            newMatches,
            newlyUnlockedConsequences,
            List.copyOf(types),
            introducedVariables,
            introducedFunctions,
            warnings,
            materialGain,
            claimEligible
        );
    }

    private static SemanticCompressionStatus compressionStatus(
        SemanticCompressionDelta scopedDelta,
        SemanticCompressionDelta wholeDelta,
        List<String> introducedVariables,
        List<String> introducedFunctions
    ) {
        if (scopedDelta.improvedDimensions().size()
                < MINIMUM_IMPROVED_DIMENSIONS) {
            return SemanticCompressionStatus.NON_MATERIAL;
        }
        if (!introducedVariables.isEmpty() || !introducedFunctions.isEmpty()) {
            return SemanticCompressionStatus.BLOCKED_BY_INTRODUCED_SYMBOLS;
        }
        if (scopedDelta.hasStructuralRegression()
                || wholeDelta.hasStructuralRegression()) {
            return SemanticCompressionStatus.BLOCKED_BY_STRUCTURAL_REGRESSION;
        }
        return SemanticCompressionStatus.MATERIAL_MULTI_DIMENSIONAL;
    }

    private static List<KnownStructureMatch> newlyExposed(
        List<KnownStructureMatch> sourceMatches,
        List<KnownStructureMatch> candidateMatches
    ) {
        Set<String> sourceIdentities = new HashSet<>();
        for (KnownStructureMatch sourceMatch : sourceMatches) {
            sourceIdentities.add(sourceMatch.identity());
        }
        return candidateMatches.stream()
            .filter(match -> !sourceIdentities.contains(match.identity()))
            .sorted(Comparator
                .comparing(KnownStructureMatch::structureId)
                .thenComparing(KnownStructureMatch::occurrencePath)
                .thenComparing(KnownStructureMatch::identity))
            .toList();
    }

    private static List<KnownStructureConsequenceUnlock> newlyUnlockedConsequences(
        List<KnownStructureMatch> sourceMatches,
        List<KnownStructureMatch> newlyExposedMatches,
        List<String> activeAssumptions
    ) {
        Set<String> availableBefore = new HashSet<>();
        for (KnownStructureMatch sourceMatch : sourceMatches) {
            if (!assumptionsSatisfied(sourceMatch, activeAssumptions)) {
                continue;
            }
            for (String consequence : sourceMatch.consequenceIds()) {
                availableBefore.add(opportunityIdentity(sourceMatch, consequence));
            }
        }

        TreeSet<KnownStructureConsequenceUnlock> unlocked = new TreeSet<>();
        for (KnownStructureMatch match : newlyExposedMatches) {
            if (!assumptionsSatisfied(match, activeAssumptions)) {
                continue;
            }
            for (String consequence : match.consequenceIds()) {
                KnownStructureConsequenceUnlock opportunity =
                    new KnownStructureConsequenceUnlock(
                        consequence,
                        match.structureId(),
                        match.occurrencePath(),
                        match.identity()
                    );
                if (!availableBefore.contains(
                        opportunity.opportunityIdentity())) {
                    unlocked.add(opportunity);
                }
            }
        }
        return List.copyOf(unlocked);
    }

    private static String opportunityIdentity(
        KnownStructureMatch match,
        String consequence
    ) {
        return consequence + "|" + match.identity();
    }

    private static boolean assumptionsSatisfied(
        KnownStructureMatch match,
        List<String> activeAssumptions
    ) {
        return new HashSet<>(activeAssumptions).containsAll(
            match.requiredAssumptions());
    }

    private static List<RepresentationAssessmentWarning> warnings(
        RepresentationCandidateProposal proposal,
        SemanticCompressionStatus compressionStatus,
        List<String> introducedVariables,
        List<String> introducedFunctions,
        List<KnownStructureMatch> newlyExposedMatches,
        List<KnownStructureConsequenceUnlock> newlyUnlockedConsequences,
        List<String> activeAssumptions
    ) {
        EnumSet<RepresentationAssessmentWarning> warnings =
            EnumSet.noneOf(RepresentationAssessmentWarning.class);
        if (!introducedVariables.isEmpty()) {
            warnings.add(
                RepresentationAssessmentWarning.INTRODUCED_VARIABLE_SYMBOLS);
        }
        if (!introducedFunctions.isEmpty()) {
            warnings.add(
                RepresentationAssessmentWarning.INTRODUCED_FUNCTION_SYMBOLS);
        }
        if (compressionStatus
                == SemanticCompressionStatus.BLOCKED_BY_STRUCTURAL_REGRESSION) {
            warnings.add(
                RepresentationAssessmentWarning
                    .STRUCTURAL_COMPRESSION_REGRESSION);
        }
        if (newlyExposedMatches.stream().anyMatch(
                match -> !assumptionsSatisfied(
                    match,
                    activeAssumptions))) {
            warnings.add(
                RepresentationAssessmentWarning
                    .UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS);
        }
        if (!newlyExposedMatches.isEmpty()
                && newlyUnlockedConsequences.isEmpty()) {
            warnings.add(
                RepresentationAssessmentWarning
                    .KNOWN_FORM_WITHOUT_NEW_CAPABILITY);
        }
        if (!proposal.validationStatus().atLeast(
                CandidateProofStatus.SYMBOLICALLY_VERIFIED)) {
            warnings.add(
                RepresentationAssessmentWarning
                    .VALIDATION_BELOW_SYMBOLIC_CONFIRMATION);
        }
        return List.copyOf(warnings);
    }

    private static List<String> difference(
        List<String> candidate,
        List<String> source
    ) {
        TreeSet<String> difference = new TreeSet<>(candidate);
        difference.removeAll(source);
        return List.copyOf(difference);
    }

    private static Expr resolve(
        Expr root,
        ExpressionOccurrencePath path
    ) {
        Expr current = root;
        for (Integer childIndex : path.childIndexes()) {
            if (current instanceof BinaryExpr binary) {
                if (childIndex == 0) {
                    current = binary.left();
                } else if (childIndex == 1) {
                    current = binary.right();
                } else {
                    throw invalidPath(path, childIndex);
                }
            } else if (current instanceof FunctionExpr function) {
                if (childIndex >= function.arguments().size()) {
                    throw invalidPath(path, childIndex);
                }
                current = function.arguments().get(childIndex);
            } else {
                throw invalidPath(path, childIndex);
            }
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
        int selectedChild = path.childIndexes().get(pathIndex);
        if (source instanceof BinaryExpr sourceBinary
                && candidate instanceof BinaryExpr candidateBinary) {
            if (sourceBinary.operator() != candidateBinary.operator()
                    || selectedChild < 0 || selectedChild > 1) {
                return false;
            }
            if (selectedChild == 0) {
                return sourceBinary.right().equals(candidateBinary.right())
                    && sameContextOutsideOccurrence(
                        sourceBinary.left(),
                        candidateBinary.left(),
                        path,
                        pathIndex + 1
                    );
            }
            return sourceBinary.left().equals(candidateBinary.left())
                && sameContextOutsideOccurrence(
                    sourceBinary.right(),
                    candidateBinary.right(),
                    path,
                    pathIndex + 1
                );
        }
        if (source instanceof FunctionExpr sourceFunction
                && candidate instanceof FunctionExpr candidateFunction) {
            if (!sourceFunction.name().equals(candidateFunction.name())
                    || sourceFunction.arguments().size()
                        != candidateFunction.arguments().size()
                    || selectedChild < 0
                    || selectedChild >= sourceFunction.arguments().size()) {
                return false;
            }
            for (int index = 0;
                    index < sourceFunction.arguments().size();
                    index++) {
                if (index != selectedChild
                        && !sourceFunction.arguments().get(index).equals(
                            candidateFunction.arguments().get(index))) {
                    return false;
                }
            }
            return sameContextOutsideOccurrence(
                sourceFunction.arguments().get(selectedChild),
                candidateFunction.arguments().get(selectedChild),
                path,
                pathIndex + 1
            );
        }
        return false;
    }

    private static IllegalArgumentException invalidPath(
        ExpressionOccurrencePath path,
        int childIndex
    ) {
        return new IllegalArgumentException(
            "invalid child " + childIndex + " in occurrence path "
                + path.canonical());
    }
}
