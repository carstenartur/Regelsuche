package de.regelsuche.benchmark.polynomial;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandWorkCharacterizationTest {
    @Test
    void characterizeVisiblePreFactorizationLowerBound() {
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .collect(java.util.stream.Collectors.toMap(
                PolynomialTheoryUtilityCaseCorpus.FormationCase::caseId,
                value -> value
            ));
        var rows = new ArrayList<Row>();
        var parser = new ExpressionParser();
        var projector = new ExactParsedSubtermProjector();
        var view = new ExactParsedUnivariatePolynomialView();

        PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(input ->
                PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                    .PROFILE_ID.equals(input.profileId())
            )
            .filter(input -> "CP06_FULL".equals(input.checkpointId()))
            .sorted(Comparator.comparing(
                PolynomialTheoryUtilityExecutionInput::caseId
            ))
            .forEach(input -> {
                var formationCase = cases.get(input.caseId());
                var source = parser.parseExactTerm(
                    formationCase.sourceExpression()
                );
                var plan = PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
                    input,
                    formationCase
                );
                for (var occurrence : plan.occurrences()) {
                    var provisional = new TreePosition(
                        occurrence.path(),
                        "pending"
                    );
                    Expr selected = provisional.subtreeAt(source.expression())
                        .orElseThrow();
                    var projection = projector.project(
                        source,
                        occurrence.path(),
                        ExpressionFormatter.format(selected)
                    );
                    if (!projection.successful()) {
                        throw new AssertionError(
                            input.caseId() + ':' + occurrence.pathKey() + ':'
                                + projection.status() + ':'
                                + projection.detailCode()
                        );
                    }
                    var extraction = view.analyze(
                        projection.projected().orElseThrow()
                    );
                    long matching = canonicalMatching(
                        projection.work().stages()
                    );
                    long sourceValidation =
                        extraction.work().totalWorkUnits();
                    rows.add(new Row(
                        input.caseId(),
                        occurrence.pathKey(),
                        countNodes(source.expression()),
                        matching,
                        sourceValidation,
                        Math.addExact(matching, sourceValidation),
                        occurrence.totalMechanicalWork(),
                        occurrence.factorizationWork(),
                        extraction.status().name()
                    ));
                }
            });

        long minimum = rows.stream().mapToLong(Row::prefixMechanical).min()
            .orElseThrow();
        long maximumAuthority = rows.stream()
            .mapToLong(Row::mechanicalAuthority).max().orElseThrow();
        StringBuilder report = new StringBuilder()
            .append("visible-prefix-min=").append(minimum)
            .append("; maximum-occurrence-authority=")
            .append(maximumAuthority).append('\n');
        rows.forEach(row -> report.append(row).append('\n'));
        throw new AssertionError(report.toString());
    }

    private static long canonicalMatching(Map<String, Long> stages) {
        long literalCount = stages.getOrDefault(
            "projection.revalidation-literal-bindings",
            0L
        );
        long result = 0L;
        for (var entry : stages.entrySet()) {
            String stage = entry.getKey();
            long raw = entry.getValue();
            long units;
            if ("projection.revalidation-literal-code-units".equals(stage)) {
                long literalWork = Math.multiplyExact(512L, literalCount);
                long lexemeWork = raw - literalWork;
                if (lexemeWork < 0L || lexemeWork % 4L != 0L) {
                    throw new AssertionError(
                        "invalid literal projection work: " + stages
                    );
                }
                units = Math.addExact(literalCount, lexemeWork / 4L);
            } else if ("projection.root-source-hash-code-units".equals(stage)
                    || "projection.range-commitment-code-units".equals(stage)) {
                units = divideRoundUp(raw, 4L);
            } else {
                units = raw;
            }
            result = Math.addExact(result, units);
        }
        return result;
    }

    private static long divideRoundUp(long value, long divisor) {
        return value == 0L ? 0L : 1L + (value - 1L) / divisor;
    }

    private static int countNodes(Expr root) {
        if (root instanceof BinaryExpr binary) {
            return 1 + countNodes(binary.left()) + countNodes(binary.right());
        }
        if (root instanceof FunctionExpr function) {
            return 1 + function.arguments().stream()
                .mapToInt(PolynomialTheoryUtilityOnDemandWorkCharacterizationTest
                    ::countNodes)
                .sum();
        }
        return 1;
    }

    private record Row(
        String caseId,
        String path,
        int rootNodes,
        long matching,
        long sourceValidation,
        long prefixMechanical,
        long mechanicalAuthority,
        long factorizationAuthority,
        String extractionStatus
    ) {
    }
}
