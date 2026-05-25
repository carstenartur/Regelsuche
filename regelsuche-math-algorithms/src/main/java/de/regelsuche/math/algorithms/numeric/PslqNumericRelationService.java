package de.regelsuche.math.algorithms.numeric;

import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import de.regelsuche.validation.NumericRelationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PslqNumericRelationService implements NumericRelationService {
    private final MathematicalAlgorithmRegistry registry;

    public PslqNumericRelationService(MathematicalAlgorithmRegistry registry) {
        this.registry = registry;
    }

    @Override
    public NumericRelationResult findIntegerRelation(List<Double> values) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH)
            || !registry.isEnabled(MathematicalAlgorithmRegistry.PSLQ)) {
            return new NumericRelationResult(List.of(), Double.NaN,
                MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                    "numericRelationSearch and pslq must be enabled"));
        }
        if (values == null || values.size() < 2) {
            return new NumericRelationResult(List.of(), Double.NaN,
                MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown("at least two values are required"));
        }

        MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.PSLQ)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .orElse(MathematicalAlgorithmRegistry.AlgorithmBudget.unbounded());

        SearchState state = new SearchState(values, budget.maxCoefficient(), budget.maxStates(), budget.tolerance());
        state.search(new int[values.size()], 0);

        if (state.bestCoefficients != null) {
            return new NumericRelationResult(
                state.bestCoefficients,
                state.bestResidual,
                new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                    MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS,
                    MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS,
                    "PSLQ-style numerical relation hypothesis (not a proof)",
                    Map.of("evaluatedCandidates", state.evaluatedCandidates)
                )
            );
        }

        MathematicalAlgorithmRegistry.ExecutionStatus status = state.evaluatedCandidates >= budget.maxStates()
            ? MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED
            : MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN;
        return new NumericRelationResult(List.of(), Double.NaN,
            new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                status,
                MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
                status == MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED
                    ? "search budget exhausted"
                    : "no integer relation found (unknown)",
                Map.of("evaluatedCandidates", state.evaluatedCandidates)
            )
        );
    }

    private static final class SearchState {
        private final List<Double> values;
        private final int maxCoefficient;
        private final int maxStates;
        private final double tolerance;
        private int evaluatedCandidates;
        private List<Integer> bestCoefficients;
        private double bestResidual = Double.POSITIVE_INFINITY;

        private SearchState(List<Double> values, int maxCoefficient, int maxStates, double tolerance) {
            this.values = values;
            this.maxCoefficient = Math.max(1, maxCoefficient);
            this.maxStates = Math.max(1, maxStates);
            this.tolerance = Math.max(1e-15, tolerance);
        }

        private void search(int[] coefficients, int index) {
            if (evaluatedCandidates >= maxStates) {
                return;
            }
            if (index == coefficients.length) {
                evaluate(coefficients);
                return;
            }
            for (int coefficient = -maxCoefficient; coefficient <= maxCoefficient; coefficient++) {
                coefficients[index] = coefficient;
                search(coefficients, index + 1);
                if (evaluatedCandidates >= maxStates) {
                    return;
                }
            }
        }

        private void evaluate(int[] coefficients) {
            evaluatedCandidates++;
            boolean allZero = true;
            for (int coefficient : coefficients) {
                if (coefficient != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return;
            }

            double residual = 0.0;
            for (int i = 0; i < coefficients.length; i++) {
                residual += coefficients[i] * values.get(i);
            }
            double absoluteResidual = Math.abs(residual);
            if (absoluteResidual <= tolerance && isBetter(coefficients, absoluteResidual)) {
                bestResidual = absoluteResidual;
                bestCoefficients = normalize(coefficients);
            }
        }

        private boolean isBetter(int[] coefficients, double residual) {
            if (bestCoefficients == null || residual < bestResidual - 1e-15) {
                return true;
            }
            if (Math.abs(residual - bestResidual) > 1e-15) {
                return false;
            }
            int score = complexity(coefficients);
            int bestScore = bestCoefficients.stream().mapToInt(Math::abs).sum();
            return score < bestScore;
        }

        private int complexity(int[] coefficients) {
            int result = 0;
            for (int coefficient : coefficients) {
                result += Math.abs(coefficient);
            }
            return result;
        }

        private List<Integer> normalize(int[] coefficients) {
            int gcd = 0;
            for (int coefficient : coefficients) {
                gcd = gcd(gcd, Math.abs(coefficient));
            }
            List<Integer> normalized = new ArrayList<>(coefficients.length);
            for (int coefficient : coefficients) {
                normalized.add(gcd == 0 ? coefficient : coefficient / gcd);
            }
            for (int value : normalized) {
                if (value < 0) {
                    for (int i = 0; i < normalized.size(); i++) {
                        normalized.set(i, -normalized.get(i));
                    }
                    break;
                }
                if (value > 0) {
                    break;
                }
            }
            return List.copyOf(normalized);
        }

        private int gcd(int a, int b) {
            if (b == 0) {
                return a;
            }
            return gcd(b, a % b);
        }
    }
}
