package de.regelsuche.system;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposer;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import de.regelsuche.math.algorithms.linalg.LinearSystemRepresentationBridge;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Product-facing entry point for exact equation-system representation.
 *
 * <p>The historical search path treated {@link InputType#SYSTEM} as unrelated
 * equation roots. This service intentionally replaces that behavior for the
 * normal product path: it first retains the system as one mathematical object,
 * constructs its exact {@code A*x=b} representation and then exposes certified
 * independent blocks and an exact RREF capability frontier.</p>
 */
public final class EquationSystemRepresentationService {
    public static final int DEFAULT_REPRESENTATION_WORK = 20_000;
    public static final int DEFAULT_DECOMPOSITION_WORK = 20_000;
    public static final int DEFAULT_RREF_WORK = 100_000;

    private final ExpressionParser parser;
    private final LinearSystemRepresentationBridge representationBridge;
    private final ExactLinearSystemBlockDecomposer blockDecomposer;
    private final ExactRrefSolver rrefSolver;
    private final RepresentationBridge.Budget representationBudget;
    private final RepresentationBridge.Budget decompositionBudget;
    private final RepresentationBridge.Budget rrefBudget;

    public EquationSystemRepresentationService() {
        this(
            new ExpressionParser(),
            new LinearSystemRepresentationBridge(),
            new ExactLinearSystemBlockDecomposer(),
            new ExactRrefSolver(),
            new RepresentationBridge.Budget(DEFAULT_REPRESENTATION_WORK),
            new RepresentationBridge.Budget(DEFAULT_DECOMPOSITION_WORK),
            new RepresentationBridge.Budget(DEFAULT_RREF_WORK));
    }

    public EquationSystemRepresentationService(
        ExpressionParser parser,
        LinearSystemRepresentationBridge representationBridge,
        ExactLinearSystemBlockDecomposer blockDecomposer,
        ExactRrefSolver rrefSolver,
        RepresentationBridge.Budget representationBudget,
        RepresentationBridge.Budget decompositionBudget,
        RepresentationBridge.Budget rrefBudget
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.representationBridge = Objects.requireNonNull(
            representationBridge,
            "representationBridge");
        this.blockDecomposer = Objects.requireNonNull(
            blockDecomposer,
            "blockDecomposer");
        this.rrefSolver = Objects.requireNonNull(rrefSolver, "rrefSolver");
        this.representationBudget = Objects.requireNonNull(
            representationBudget,
            "representationBudget");
        this.decompositionBudget = Objects.requireNonNull(
            decompositionBudget,
            "decompositionBudget");
        this.rrefBudget = Objects.requireNonNull(rrefBudget, "rrefBudget");
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
        Optional<ExactRrefSolver.Result> rowReduction =
            representation.representation()
                .map(system -> rrefSolver.solve(system, rrefBudget));
        return new Analysis(
            input.trim(),
            equations,
            representation,
            decomposition,
            rowReduction);
    }

    public record Analysis(
        String source,
        List<Equation> equations,
        RepresentationBridge.Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> representation,
        Optional<RepresentationBridge.Result<
            ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate>> decomposition,
        Optional<ExactRrefSolver.Result> rowReduction
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
            rowReduction = Objects.requireNonNull(
                rowReduction,
                "rowReduction");
            if (equations.isEmpty()) {
                throw new IllegalArgumentException(
                    "analysis must retain at least one equation");
            }
            if (representation.represented() != decomposition.isPresent()
                    || representation.represented()
                        != rowReduction.isPresent()) {
                throw new IllegalArgumentException(
                    "matrix capabilities must follow represented systems only");
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

        public Optional<ExactRrefReduction> rref() {
            return rowReduction.flatMap(ExactRrefSolver.Result::reduction);
        }

        public List<String> unlockedCapabilities() {
            Set<String> capabilities = new LinkedHashSet<>();
            blocks().ifPresent(blocks ->
                capabilities.addAll(blocks.unlockedCapabilities()));
            rref().ifPresent(reduction -> capabilities.addAll(
                reduction.capabilityFrontier().newlyUnlocked()));
            return List.copyOf(capabilities);
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

            renderBlockDecomposition(lines);
            renderRref(lines, system.variables());
            return String.join("\n", lines);
        }

        private void renderBlockDecomposition(List<String> lines) {
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
                lines.add("Block capabilities: "
                    + String.join(
                        ", ",
                        blockResult.unlockedCapabilities()));
            } else {
                lines.add("Independent components: none ("
                    + blockAttempt.status() + ")");
            }
            lines.add(renderWork(
                "Decomposition work",
                blockAttempt.work()));
        }

        private void renderRref(
            List<String> lines,
            List<String> variables
        ) {
            ExactRrefSolver.Result attempt = rowReduction.orElseThrow();
            if (attempt.status() != ExactRrefSolver.Status.SOLVED) {
                lines.add("Exact RREF: " + attempt.status()
                    + " (" + attempt.detailCode() + ")");
                lines.add(renderWork("RREF work", attempt.work()));
                return;
            }

            ExactRrefReduction reduction = attempt.reduction().orElseThrow();
            lines.add("RREF(A|b) = " + renderAugmentedRows(
                reduction.reducedAugmentedRows(),
                variables.size()));
            lines.add("Elementary row operations: "
                + reduction.rowOperations().size());
            lines.add("New RREF capabilities: "
                + String.join(
                    ", ",
                    reduction.capabilityFrontier().newlyUnlocked()));
            switch (reduction.solutionClassification()) {
                case UNIQUE -> lines.add("Exact solution: "
                    + renderNamedSolution(
                        variables,
                        reduction.particularSolution()
                            .orElseThrow()
                            .values()));
                case UNDERDETERMINED -> {
                    lines.add("Particular solution: " + renderColumnVector(
                        reduction.particularSolution()
                            .orElseThrow()
                            .values()));
                    lines.add("Nullspace basis: " + reduction.nullspaceBasis()
                        .stream()
                        .map(vector -> renderColumnVector(vector.values()))
                        .collect(java.util.stream.Collectors.joining(
                            ", ",
                            "[",
                            "]")));
                }
                case INCONSISTENT -> lines.add("Contradiction rows: "
                    + reduction.contradictionRows());
            }
            lines.add(renderWork("RREF work", attempt.work()));
        }

        private static String renderMatrix(ExactLinearSystem system) {
            return system.coefficients().rows().stream()
                .map(Analysis::renderRow)
                .collect(java.util.stream.Collectors.joining(
                    ", ",
                    "[",
                    "]"));
        }

        private static String renderAugmentedRows(
            List<List<Rational>> rows,
            int coefficientColumns
        ) {
            return rows.stream().map(row -> {
                String coefficients = renderRow(
                    row.subList(0, coefficientColumns));
                return coefficients.substring(
                    0,
                    coefficients.length() - 1)
                    + " | "
                    + row.get(coefficientColumns)
                    + "]";
            }).collect(java.util.stream.Collectors.joining(
                ", ",
                "[",
                "]"));
        }

        private static String renderNamedSolution(
            List<String> variables,
            List<Rational> values
        ) {
            List<String> assignments = new ArrayList<>(variables.size());
            for (int index = 0; index < variables.size(); index++) {
                assignments.add(variables.get(index) + "=" + values.get(index));
            }
            return "[" + String.join(", ", assignments) + "]";
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
