package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.Assessment;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.PrimaryStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalRediscoveryReportPipelineTest {

    @Test
    void commandAndSiblingPathBoundariesFailClosed(@TempDir Path directory) {
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryReportPipeline.main(new String[0])
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryReportPipeline.main(
                new String[] {"unknown"}
            )
        );

        Path output = directory.resolve("historical-rediscovery");
        assertEquals(
            output.toAbsolutePath().resolveSibling(
                "historical-rediscovery-witness-pruning"
            ),
            invoke(
                "siblingOutput",
                new Class<?>[] {Path.class, String.class},
                output,
                "-witness-pruning"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> invoke(
                "siblingOutput",
                new Class<?>[] {Path.class, String.class},
                Path.of("/"),
                "-invalid"
            )
        );
    }

    @Test
    @Timeout(240)
    void retainedClaimChecksAcceptBoundEvidenceAndRejectForgedAssessment() {
        AtlasReport policySignal = atlas(
            "distribution-fitness-valley-control"
        );
        assertEquals(
            PrimaryStatus.REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND,
            policySignal.cases().getFirst().status()
        );
        invoke(
            "verifyRetainedClaims",
            new Class<?>[] {AtlasReport.class},
            policySignal
        );

        Assessment original = policySignal.assessment();
        Assessment forged = new Assessment(
            original.decision(),
            original.representationLayerWorks(),
            original.equivalenceLayerDiscriminates(),
            original.productionPositiveControlWorks(),
            original.missingInventoryLayerIdentified(),
            false,
            original.genericBridgeDifferenceIdentified(),
            original.negativeControlPassed(),
            original.distinctPrimaryStatuses(),
            original.statusCounts(),
            original.reasons()
        );
        AtlasReport inconsistent = new AtlasReport(
            policySignal.schema(),
            policySignal.corpusSchema(),
            policySignal.corpusSha256(),
            policySignal.inventoryRevision(),
            policySignal.claimBoundary(),
            policySignal.cases(),
            policySignal.directionality(),
            forged
        );
        IllegalStateException mismatch = assertThrows(
            IllegalStateException.class,
            () -> invoke(
                "verifyRetainedClaims",
                new Class<?>[] {AtlasReport.class},
                inconsistent
            )
        );
        assertTrue(mismatch.getMessage().contains("search-policy claim"));

        AtlasReport genericBridge = atlas("sophie-germain");
        assertEquals(
            PrimaryStatus.GENERIC_BRIDGE_REQUIRED_AND_FOUND,
            genericBridge.cases().getFirst().status()
        );
        invoke(
            "verifyRetainedClaims",
            new Class<?>[] {AtlasReport.class},
            genericBridge
        );
    }

    @Test
    @Timeout(240)
    void witnessChecksRequireBalancedMatchingAndPolicyConsistentCases() {
        Corpus corpus = singleCase("distribution-fitness-valley-control");
        AtlasReport atlas = new HistoricalRediscoveryAtlas().run(corpus);
        HistoricalWitnessPruningDiagnostic diagnostic =
            new HistoricalWitnessPruningDiagnostic();
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> retained =
            diagnostic.run(corpus, atlas);

        invoke(
            "verifyWitnessPruning",
            new Class<?>[] {AtlasReport.class, List.class},
            atlas,
            retained
        );

        IllegalStateException unbalanced = assertThrows(
            IllegalStateException.class,
            () -> invoke(
                "verifyWitnessPruning",
                new Class<?>[] {AtlasReport.class, List.class},
                atlas,
                List.of()
            )
        );
        assertTrue(unbalanced.getMessage().contains("balance every atlas case"));

        HistoricalWitnessPruningDiagnostic.CaseDiagnostic foreign =
            HistoricalWitnessPruningDiagnostic.CaseDiagnostic.withoutLoss(
                "foreign-case",
                HistoricalWitnessPruningDiagnostic.ORACLE_NOT_EVALUATED,
                "NOT_EVALUATED",
                0,
                0,
                "NOT_EVALUATED",
                0,
                0,
                0,
                "foreign diagnostic"
            );
        IllegalStateException missing = assertThrows(
            IllegalStateException.class,
            () -> invoke(
                "verifyWitnessPruning",
                new Class<?>[] {AtlasReport.class, List.class},
                atlas,
                List.of(foreign)
            )
        );
        assertTrue(missing.getMessage().contains("missing witness-pruning case"));

        HistoricalWitnessPruningDiagnostic.CaseDiagnostic original =
            retained.getFirst();
        HistoricalWitnessPruningDiagnostic.CaseDiagnostic forged =
            HistoricalWitnessPruningDiagnostic.CaseDiagnostic.withoutLoss(
                original.id(),
                HistoricalWitnessPruningDiagnostic.WITNESS_COMPLETELY_EXPLORED,
                original.oracleStatus(),
                original.witnessStepCount(),
                original.witnessStepCount(),
                original.searchTerminalStatus(),
                original.searchExploredStates(),
                original.engineCalls(),
                original.generatedTransformations(),
                "forged complete witness"
            );
        IllegalStateException policyMismatch = assertThrows(
            IllegalStateException.class,
            () -> invoke(
                "verifyWitnessPruning",
                new Class<?>[] {AtlasReport.class, List.class},
                atlas,
                List.of(forged)
            )
        );
        assertTrue(policyMismatch.getMessage().contains(
            "scalar witness-prefix loss"
        ));
    }

    private static AtlasReport atlas(String id) {
        Corpus corpus = singleCase(id);
        return new HistoricalRediscoveryAtlas().run(corpus);
    }

    private static Corpus singleCase(String id) {
        Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow();
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            List.of(selected)
        );
    }

    private static Object invoke(
        String name,
        Class<?>[] parameterTypes,
        Object... arguments
    ) {
        try {
            Method method = HistoricalRediscoveryReportPipeline.class
                .getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
