package de.regelsuche.system;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposer;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition;
import de.regelsuche.math.algorithms.linalg.LinearSystemRepresentationBridge;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Product-facing entry point for exact equation-system representation.
 *
 * <p>The historical search path treated {@link InputType#SYSTEM} as unrelated
 * equation roots. This service intentionally replaces that behavior for the
 * normal product path: it first retains the system as one mathematical object,
 * constructs its exact {@code A*x=b} representation and then exposes certified
 * independent blocks when they exist.</p>
 */
public final class EquationSystemRepresentationService {
    public static final int DEFAULT_REPRESENTATION_WORK = 20_000;
    public static final int DEFAULT_DECOMPOSITION_WORK = 20_000;

    private final ExpressionParser parser;
    private final LinearSystemRepresentationBridge representationBridge;
    private final ExactLinearSystemBlockDecomposer blockDecomposer;
    private final RepresentationBridge.Budget representationBudget;
    private final RepresentationBridge.Budget decompositionBudget;

    public EquationSystemRepresentationService() {
        this(
            new ExpressionParser(),
            new LinearSystemRepresentationBridge(),
            new ExactLinearSystemBlockDecomposer(),
            new RepresentationBridge.Budget(DEFAULT_REPRESENTATION_WORK),
            new RepresentationBridge.Budget(DEFAULT_DECOMPOSITION_WORK));
    }

    public EquationSystemRepresentationService(
        ExpressionParser parser,
        LinearSystemRepresentationBridge representationBridge,
        ExactLinearSystemBlockDecomposer blockDecomposer,
        RepresentationBridge.Budget representationBudget,
        RepresentationBridge.Budget decompositionBudget
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.representationBridge = Objects.requireNonNull(
            representationBridge,
            "representationBridge");
        this.blockDecomposer = Objects.requireNonNull(
            blockDecomposer,
            "blockDecomposer");
        this.representationBudget = Objects.requireNonNull(
            representationBudget,
            "representationBudget");
        this.decompositionBudget = Objects.requireNonNull(
            decompositionBudget,
            "decompositionBudget");
    }

    public Analysis analyze(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                "equation-system input must not be blank");
        }
        List<Equation> equations = parser.parse(
            new InputRequest(InputType.SYSTEM, input)).equations();
        if (equations.isEmpty()) {
            throw new IllegalArgumentException(
                "equation-system input must contain at least one equation");
        }
        RepresentationBridge.Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> representation =
                representationBridge.analyze(
                    equations,
                    representationBudget);

        Optional<RepresentationBridge.Result<
            ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate>> decomposition =
                representation.representation()
                    .map(system -> blockDecomposer.analyze(
                        system,
                        decompositionBudget));
        return new Analysis(input.trim(), equations, representation, decomposition);
    }

    public record Analysis(
        String source,
        List<Equation> equations,
        RepresentationBridge.Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> representation,
        Optional<RepresentationBridge.Result<
            ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate>> decomposition
    ) {
        public Analysis {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("source must not be blank");
            }
            source = source.trim();
            equations = List.copyOf(Objects.requireNonNull(
                equations,
                "equations"));
            representation = Objects.requireNonNull(
                representation,
                "representation");
            decomposition = Objects.requireNonNull(
                decomposition,
                "decomposition");
            if (equations.isEmpty()) {
                throw new IllegalArgumentException(
                    "analysis must retain at least one equation");
            }
            if (representation.represented() != decomposition.isPresent()) {
                throw new IllegalArgumentException(
                    "decomposition analysis must follow represented systems only");
            }
        }

        public boolean represented() {
            return representation.represented();
        }

        public Optional<ExactLinearSystem> exactSystem() {
            return representation.representation();
        }

        public Optional<ExactLinearSystemBlockDecomposition> blocks() {
            return decomposition.flatMap(
                RepresentationBridge.Result::representation);
        }

        public List<String> unlockedCapabilities() {
            return blocks()
                .map(ExactLinearSystemBlockDecomposition::unlockedCapabilities)
                .orElseGet(List::of);
        }

        public String renderSummary() {
            if (!represented()) {
                return String.join("\n",
                    "Exact matrix representation: "
                        + representation.status(),
                    "Detail: " + representation.detailCode(),
                    renderWork("Representation work", representation.work()));
            }

            ExactLinearSystem system = exactSystem().orElseThrow();
            List<String> lines = new ArrayList<>();
            lines.add("Recognized exact matrix representation");
            lines.add("Relation: "
                + representation.relation().orElseThrow());
            lines.add("A = " + renderMatrix(system));
            lines.add("x = " + renderVariableVector(system.variables()));
            lines.add("b = " + renderColumnVector(
                system.rightHandSide().values()));
            lines.add("Classification: " + system.solutionClassification());
            lines.add("rank(A) = " + system.coefficientRank());
            lines.add("rank([A|b]) = " + system.augmentedRank());
            lines.add(renderWork(
                "Representation work",
                representation.work()));

            RepresentationBridge.Result<
                ExactLinearSystemBlockDecomposition,
                ExactLinearSystemBlockDecomposer.Certificate> blockAttempt =
                    decomposition.orElseThrow();
            if (blockAttempt.represented()) {
                ExactLinearSystemBlockDecomposition blockResult =
                    blockAttempt.representation().orElseThrow();
                lines.add("Independent components: "
                    + blockResult.components().size());
                for (int index = 0;
                        index < blockResult.components().size();
                        index++) {
                    ExactLinearSystemBlockDecomposition.Component component =
                        blockResult.components().get(index);
                    lines.add("  " + index
                        + ": " + component.kind()
                        + ", rows=" + component.sourceRowIndices()
                        + ", variables=" + component.variableNames()
                        + (component.contradictoryConstantConstraint()
                            ? ", contradiction=true"
                            : ""));
                }
                lines.add("Capabilities: "
                    + String.join(", ", blockResult.unlockedCapabilities()));
            } else {
                lines.add("Independent components: none ("
                    + blockAttempt.status() + ")");
            }
            lines.add(renderWork(
                "Decomposition work",
                blockAttempt.work()));
            return String.join("\n", lines);
        }

        private static String renderMatrix(ExactLinearSystem system) {
            return system.coefficients().rows().stream()
                .map(Analysis::renderRow)
                .collect(java.util.stream.Collectors.joining(
                    ", ",
                    "[",
                    "]"));
        }

        private static String renderVariableVector(List<String> variables) {
            return "[" + String.join(", ", variables) + "]^T";
        }

        private static String renderColumnVector(List<Rational> values) {
            return renderRow(values) + "^T";
        }

        private static String renderRow(List<Rational> values) {
            return values.stream()
                .map(Rational::toString)
                .collect(java.util.stream.Collectors.joining(
                    ", ",
                    "[",
                    "]"));
        }

        private static String renderWork(
            String label,
            RepresentationBridge.WorkLedger work
        ) {
            return label + ": "
                + work.consumedWorkUnits()
                + "/"
                + work.configuredWorkUnits();
        }
    }
}
