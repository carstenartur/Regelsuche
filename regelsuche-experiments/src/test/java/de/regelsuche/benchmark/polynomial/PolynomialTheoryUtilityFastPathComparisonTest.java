package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCaseCorpus.FormationCase;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.polynomial.ExactFactorizationReplaySession;
import de.regelsuche.polynomial.ExactFactorizationReplaySession.Disposition;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Compares the new algorithms under the original public inputs and work limits. */
class PolynomialTheoryUtilityFastPathComparisonTest {
    private static final String HEADER = "checkpoint\tcase\tpath\tstatus\tdetail\tprimitive_limit\tmechanical_limit"
        + "\tfactorization_limit\tprimitive_work\tmechanical_work\tfactorization_work\tcertificate\treplacement\n";

    @Test
    void runsFastPathUnderTheOriginalPublicBudgets() throws IOException {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs();
        var cases = cases();
        var engine = engine();
        var report = new StringBuilder(HEADER);
        int evaluated = 0;
        int transitions = 0;
        var outcomes = new TreeMap<String, Integer>();
        for (var input : inputs) {
            if (!PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.PROFILE_ID.equals(input.profileId())) continue;
            var formation = cases.get(input.caseId());
            var authority = new PolynomialTheoryUtilityWorkAuthority(input);
            var parsed = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.parse(formation.sourceExpression(), authority);
            evaluated++;
            for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
                var outcome = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeOccurrence(
                    parsed, path, authority, () -> engine, true);
                outcomes.merge(outcome.detailCode(), 1, Integer::sum);
                boolean completed = outcome.status().equals("TRANSFORMED");
                if (completed) transitions++;
                record(report, input, path, outcome.status(), outcome.detailCode(), authority, outcome.pipeline(), completed);
            }
            authority.projection();
        }
        assertEquals(120, evaluated);
        assertTrue(transitions > 0, () -> "no fast-path transition under the frozen work limits: " + outcomes);
        export("direct.tsv", report);
    }

    @Test
    void measuresCompactCacheUnderTheOriginalPublicBudgets() throws IOException {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs();
        var cases = cases();
        var engine = engine();
        var report = new StringBuilder(HEADER);
        var sessions = new TreeMap<String, ExactFactorizationReplaySession>();
        var outcomes = new TreeMap<String, Integer>();
        int evaluated = 0;
        int inserts = 0;
        int replays = 0;
        for (var input : inputs) {
            if (!PolynomialTheoryUtilityDerivedCacheAdapter.PROFILE_ID.equals(input.profileId())) continue;
            evaluated++;
            var formation = cases.get(input.caseId());
            var session = sessions.computeIfAbsent(input.runId(), run -> new ExactFactorizationReplaySession(
                engine, PolynomialTheoryUtilityExecutionPlan.CACHE_CAPACITY));
            var authority = new PolynomialTheoryUtilityWorkAuthority(input);
            var parsed = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.parse(formation.sourceExpression(), authority);
            for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
                try {
                    if (parsed == null || authority.remainingPrimitiveWork() < 7) throw new PolynomialWorkAuthority.LimitReached();
                    authority.consume("study.evidence.occurrence-records", 1);
                    TreePosition position = position(parsed, path, authority);
                    long factorizationBefore = authority.work().factorizationWork();
                    var execution = session.transform(parsed, position, authority);
                    var result = execution.result();
                    outcomes.merge(result.detailCode(), 1, Integer::sum);
                    if (result.transformed()) authority.consumePrimitive(7);
                    if (execution.disposition() == Disposition.INSERTED) inserts++;
                    if (execution.disposition() == Disposition.REPLAYED) {
                        replays++;
                        assertEquals(factorizationBefore, authority.work().factorizationWork());
                        assertTrue(result.totalWork().units("transform.source-evidence-text-validation") > 0);
                    }
                    record(report, input, path, execution.disposition().name(), result.detailCode(), authority,
                        result, result.transformed());
                } catch (PolynomialWorkAuthority.LimitReached exhausted) {
                    outcomes.merge(exhausted.getMessage(), 1, Integer::sum);
                    record(report, input, path, "BUDGET_INCONCLUSIVE", exhausted.getMessage(), authority, null, false);
                }
            }
            authority.projection();
        }
        assertEquals(120, evaluated);
        assertEquals(6, sessions.size());
        assertTrue(inserts > 0, () -> "no insertion under the frozen work limits: " + outcomes);
        assertTrue(replays > 0, () -> "no replay under the frozen work limits: " + outcomes);
        export("cache.tsv", report);
    }

    @Test
    void aCachedSpellingCannotAuthorizeAStaleOccurrence() {
        var input = rootCacheInput();
        var firstWork = new PolynomialTheoryUtilityWorkAuthority(input);
        var parsed = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.parse(
            cases().get(input.caseId()).sourceExpression(), firstWork);
        var session = new ExactFactorizationReplaySession(engine(), 2);
        var original = session.transform(parsed, position(parsed, List.of(), firstWork), firstWork);
        assertEquals(Disposition.INSERTED, original.disposition());

        var staleWork = new PolynomialTheoryUtilityWorkAuthority(input);
        var stale = session.transform(parsed, new TreePosition(List.of(), "x ^ 2 - 2"), staleWork);
        assertEquals(Disposition.REPLAY_REJECTED, stale.disposition());
        assertEquals(ExactNestedFactorizationTransformationPipeline.Status.POSITION_STALE, stale.result().status());
        assertEquals(0, staleWork.work().factorizationWork());
    }

    @Test
    void deniedCacheInsertionCanBeRetriedWithoutAPartialEntry() {
        var input = rootCacheInput();
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        var parsed = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.parse(
            cases().get(input.caseId()).sourceExpression(), authority);
        var position = position(parsed, List.of(), authority);
        var session = new ExactFactorizationReplaySession(engine(), 2);
        var denyInsertion = new PolynomialWorkAuthority() {
            @Override public void consume(PolynomialWorkLedger stage) {
                if (stage.units("cache.insertion.compact-entry-writes") > 0) throw new LimitReached();
                authority.consume(stage);
            }
            @Override public long remainingOpaqueWorkUnits() { return authority.remainingOpaqueWorkUnits(); }
            @Override public PolynomialWorkLedger opaqueInvocationOverhead() { return authority.opaqueInvocationOverhead(); }
        };
        assertThrows(PolynomialWorkAuthority.LimitReached.class, () -> session.transform(parsed, position, denyInsertion));
        assertTrue(authority.work().factorizationWork() > 0);
        assertEquals(0, authority.ledger().units("cache.insertion.compact-entry-writes"));
        var retry = session.transform(parsed, position, new PolynomialTheoryUtilityWorkAuthority(input));
        assertEquals(Disposition.INSERTED, retry.disposition());
    }

    private static PolynomialTheoryUtilityExecutionInput rootCacheInput() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> PolynomialTheoryUtilityDerivedCacheAdapter.PROFILE_ID.equals(value.profileId())
                && value.checkpointId().equals("CP06_FULL") && value.caseId().equals("z02-difference-of-squares"))
            .findFirst().orElseThrow();
    }

    private static TreePosition position(ExactParsedTerm parsed, List<Integer> path, PolynomialTheoryUtilityWorkAuthority work) {
        work.consume("projection.study-path-navigation", path.size() + 1L);
        var selected = new TreePosition(path, "pending").subtreeAt(parsed.expression()).orElseThrow();
        return new TreePosition(path, ExpressionFormatter.formatMeasured(selected,
            units -> work.consume("projection.study-position-format-code-units", units)));
    }

    private static Map<String, FormationCase> cases() {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .collect(Collectors.toMap(FormationCase::caseId, formation -> formation));
    }

    private static FactorizationEngine<ExactRational> engine() {
        return NativeUnivariateFactorizationEngine.rationalsWithFastPath(NativeUnivariateFactorizationPolicy.boundedDefaults());
    }

    private static void record(StringBuilder report, PolynomialTheoryUtilityExecutionInput input, List<Integer> path,
            String status, String detail, PolynomialTheoryUtilityWorkAuthority authority,
            ExactNestedFactorizationTransformationPipeline.Result result, boolean completed) {
        var work = authority.work();
        String certificate = result == null ? "-" : result.certificateHash();
        String replacement = completed
            ? result.transformation().orElseThrow().transformedExpression().orElseThrow() : "-";
        report.append(input.checkpointId()).append('\t').append(input.caseId()).append('\t').append(path).append('\t')
            .append(status).append('\t').append(detail).append('\t').append(input.admittedPrimitiveWork()).append('\t')
            .append(input.totalMechanicalWork()).append('\t').append(input.factorizationWork()).append('\t')
            .append(work.primitiveWork()).append('\t').append(work.mechanicalWork()).append('\t')
            .append(work.factorizationWork()).append('\t').append(certificate).append('\t').append(replacement).append('\n');
    }

    private static void export(String filename, StringBuilder report) throws IOException {
        String directory = System.getProperty("polynomialFastPathComparisonDirectory");
        if (directory == null) return;
        Path output = Files.createDirectories(Path.of(directory));
        Files.writeString(output.resolve(filename), report.toString());
    }

}
