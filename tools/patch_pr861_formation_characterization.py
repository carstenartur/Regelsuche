from pathlib import Path

path = Path(
    "app/src/test/java/de/regelsuche/benchmark/polynomial/"
    "PolynomialTheoryUtilityOnDemandFormationCharacterizationTest.java"
)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text('''package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandFormationCharacterizationTest {
    @Test
    void recordsEveryVisibleFormationRowWithoutQualificationAccess()
            throws IOException {
        Map<String, PolynomialTheoryUtilityCaseCorpus.FormationCase> cases =
            new LinkedHashMap<>();
        for (var formationCase :
                PolynomialTheoryUtilityCaseCorpus.load().cases()) {
            cases.put(formationCase.caseId(), formationCase);
        }

        StringBuilder report = new StringBuilder();
        report.append(
            "checkpoint\\tcase\\tinputId\\tprimitiveAuthority"
                + "\\tmechanicalAuthority\\tfactorizationAuthority"
                + "\\tkind\\tterminalStatus\\tdetail"
                + "\\tprimitive\\tmatching\\tsourceValidation"
                + "\\tfactorization\\tverification\\trendering"
                + "\\treparse\\treconstruction\\treplacement"
                + "\\tevidence\\ttransitions\\tattempts\\terror\\n"
        );
        int rows = 0;
        int completed = 0;
        int rejected = 0;
        Map<String, Integer> statuses = new LinkedHashMap<>();

        for (var input :
                PolynomialTheoryUtilityExecutionInputs.freeze().inputs()) {
            if (!PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                    .PROFILE_ID.equals(input.profileId())) {
                continue;
            }
            rows++;
            var formationCase = cases.get(input.caseId());
            if (formationCase == null) {
                throw new IllegalStateException(
                    "missing visible formation case " + input.caseId()
                );
            }
            try {
                var measured =
                    PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                        .executeCase(input, formationCase);
                var result = measured.result();
                var work = result.work();
                completed++;
                statuses.merge(
                    result.terminalStatus().name(),
                    1,
                    Math::addExact
                );
                appendPrefix(report, input);
                report.append("RESULT\\t")
                    .append(result.terminalStatus()).append('\\t')
                    .append(sanitize(result.detailCode())).append('\\t')
                    .append(work.primitiveWork()).append('\\t')
                    .append(work.matchingWork()).append('\\t')
                    .append(work.sourceValidationWork()).append('\\t')
                    .append(work.factorizationWork()).append('\\t')
                    .append(work.verificationWork()).append('\\t')
                    .append(work.renderingWork()).append('\\t')
                    .append(work.reparseWork()).append('\\t')
                    .append(work.reconstructionWork()).append('\\t')
                    .append(work.occurrenceReplacementWork()).append('\\t')
                    .append(work.evidenceConstructionWork()).append('\\t')
                    .append(result.transitions().size()).append('\\t')
                    .append(measured.measurements()
                        .factorizationAttempts().size()).append('\\t')
                    .append("\\n");
            } catch (RuntimeException exception) {
                rejected++;
                String kind = exception.getClass().getSimpleName();
                statuses.merge("EXCEPTION_" + kind, 1, Math::addExact);
                appendPrefix(report, input);
                report.append("EXCEPTION\\t\\t\\t")
                    .append("0\\t0\\t0\\t0\\t0\\t0\\t0\\t0\\t0\\t0")
                    .append("\\t0\\t0\\t")
                    .append(sanitize(kind + ": " + exception.getMessage()))
                    .append('\\n');
            }
        }

        Path output = Path.of(
            "..",
            "build",
            "reports",
            "pr861-formation-characterization.tsv"
        ).normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println(
            "PR861_FORMATION_MAP rows=" + rows
                + " completed=" + completed
                + " rejected=" + rejected
                + " statuses=" + statuses
                + " output=" + output.toAbsolutePath()
        );
        assertEquals(120, rows);
        assertEquals(rows, completed + rejected);
    }

    private static void appendPrefix(
        StringBuilder report,
        PolynomialTheoryUtilityExecutionInput input
    ) {
        report.append(input.checkpointId()).append('\\t')
            .append(input.caseId()).append('\\t')
            .append(input.inputId()).append('\\t')
            .append(input.admittedPrimitiveWork()).append('\\t')
            .append(input.totalMechanicalWork()).append('\\t')
            .append(input.factorizationWork()).append('\\t');
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\\t', ' ')
            .replace('\\r', ' ')
            .replace('\\n', ' ');
    }
}
''', encoding="utf-8")
