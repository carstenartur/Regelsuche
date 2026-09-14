#!/usr/bin/env python3
"""One-off source preparation on a detached, exact PR1006 head.

This helper and its temporary workflow are NOT included in the product candidate.
Every replacement checks its source anchor. CI verifies RED, then all core/search
JUnit cases, application compilation and export tests before publishing a candidate.
It never changes main, required checks, budgets, thresholds or retained studies.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

EXPECTED = 'ae1f5c1670aa0c8e51a5223bb4812cea0c00f5a1'
assert subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip() == EXPECTED
S = 'regelsuche-search/src/main/java/de/regelsuche/'
T = 'regelsuche-search/src/test/java/de/regelsuche/'
R = 'de.regelsuche.scoring.ScoreRevision'
changed = set()

def write(path, text):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')
    changed.add(path)

def replace(path, old, new, count=1):
    text = Path(path).read_text(encoding='utf-8')
    assert text.count(old) == count, (path, 'anchor count', text.count(old), old)
    write(path, text.replace(old, new))

def add_revision_record(path, name):
    text = Path(path).read_text(encoding='utf-8')
    match = re.search(r'\brecord ' + name + r'\((.*?)\n(\s*)\) \{', text, re.S)
    assert match, (path, 'record header')
    components = [v.strip() for v in match.group(1).split(',')]
    assert all('\n' not in v and v for v in components), components
    names = [v.rsplit(' ', 1)[1] for v in components]
    indent = match.group(2)
    body_indent = indent + '    '
    header = match.group(0).replace(match.group(1), match.group(1).rstrip() + ',\n' + body_indent + 'String scoringRevision')
    text = text[:match.start()] + header + text[match.end():]
    compact = 'public ' + name + ' {'
    normalize = '\n' + body_indent + '    scoringRevision = ' + R + '.normalize(scoringRevision);'
    if compact in text:
        assert text.count(compact) == 1
        text = text.replace(compact, compact + normalize)
    else:
        marker = header
        text = text.replace(marker, marker + '\n' + body_indent + compact + normalize + '\n' + body_indent + '}\n', 1)
    constructor = ('\n' + body_indent + '/** Raw historical or custom values do not identify their scoring producer. */\n'
        + body_indent + 'public ' + name + '(\n' + ',\n'.join(body_indent + '    ' + v for v in components)
        + '\n' + body_indent + ') {\n' + body_indent + '    this(\n'
        + ',\n'.join(body_indent + '        ' + v for v in names)
        + ',\n' + body_indent + '        ' + R + '.UNSPECIFIED);\n' + body_indent + '}\n')
    # Insert immediately after the record header; compact constructors remain intact.
    text = text.replace(header, header + constructor, 1)
    write(path, text)

def append_call_argument(text, prefix, argument):
    """Update pre-existing synthetic test fixtures, not production or retained data."""
    starts = [m.end() for m in re.finditer(re.escape(prefix), text)]
    for start in reversed(starts):
        depth, quote, escape = 1, None, False
        for end in range(start, len(text)):
            c = text[end]
            if quote:
                if escape: escape = False
                elif c == '\\': escape = True
                elif c == quote: quote = None
            elif c in ('"', "'"): quote = c
            elif c == '(': depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0: break
        else: raise AssertionError('unterminated constructor')
        text = text[:end] + ', ' + argument + text[end:]
    return text

RED_TEST = '''package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExpressionScoreRevisionTest {
    @Test void rawNumbersAreNotSilentlyRelabelledAsNewlyProducedScores() {
        assertNotEquals(new ExpressionScore(1, 1, 0, 0, 0), new ExpressionScorer().score("x"));
    }
    @Test void incompatibleProducerRevisionsCannotBeUsedToComputeAnImprovement() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExpressionScorer().score("x").improvementTo(new ExpressionScore(1, 1, 0, 0, 0)));
    }
}
'''
write(T + 'scoring/ExpressionScoreRevisionTest.java', RED_TEST)
if '--red' in sys.argv:
    sys.exit(0)
assert '--apply' in sys.argv

write(S + 'scoring/ScoreRevision.java', '''package de.regelsuche.scoring;

import de.regelsuche.json.JsonReader;
import java.util.List;
import java.util.Map;

/** Score semantics are metadata, never mathematical equivalence or proof authority. */
public final class ScoreRevision {
    /** Token-aware quadratic recognition and identity-independent scoped-symbol costs. */
    public static final String CURRENT = "regelsuche.expression-score/v2";
    public static final String UNSPECIFIED = "unspecified";

    private ScoreRevision() { }

    public static String normalize(String revision) {
        return revision == null || revision.isBlank() ? UNSPECIFIED : revision;
    }

    public static void requireCurrent(String revision) {
        if (!CURRENT.equals(revision)) {
            throw new IllegalArgumentException("incompatible scoring revision: " + normalize(revision)
                + "; regenerate scores from their original source with " + CURRENT);
        }
    }

    /** Check the contract before invoking a caller-provided replay/source supplier. */
    public static void requireReplay(String json, String schema, String expectedRevision) {
        Map<String, Object> header = new JsonReader(json).readObject();
        if (!schema.equals(header.get("schema"))) {
            throw new IllegalArgumentException("unsupported scoring replay schema: " + header.get("schema"));
        }
        if (UNSPECIFIED.equals(normalize(expectedRevision))
                || !expectedRevision.equals(header.get("scoringRevision"))) {
            throw new IllegalArgumentException("incompatible scoring revision in replay: " + header.get("scoringRevision"));
        }
        requireNestedScores(header, expectedRevision);
    }

    private static void requireNestedScores(Object value, String expectedRevision) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("score") instanceof Map<?, ?> score
                    && !expectedRevision.equals(score.get("scoringRevision"))) {
                throw new IllegalArgumentException("incompatible scoring revision in replay score");
            }
            map.values().forEach(child -> requireNestedScores(child, expectedRevision));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> requireNestedScores(child, expectedRevision));
        }
    }
}
''')

add_revision_record(S + 'scoring/ExpressionScore.java', 'ExpressionScore')
replace(S + 'scoring/ExpressionScore.java', '    public int improvementTo(ExpressionScore other) {',
    '    public int improvementTo(ExpressionScore other) {\n        if (!scoringRevision.equals(other.scoringRevision())) {\n            throw new IllegalArgumentException("cannot compare different scoring revisions");\n        }')
replace(S + 'scoring/ExpressionScorer.java', '            operators, maxNesting, bonus);',
    '            operators, maxNesting, bonus, ScoreRevision.CURRENT);')

add_revision_record(S + 'search/telemetry/SearchEvent.java', 'SearchEvent')
replace(S + 'search/strategy/SearchTelemetry.java', '            state.recordedExecution().orElse(null)\n',
    '            state.recordedExecution().orElse(null),\n            state.score().scoringRevision()\n')
replace(S + 'search/strategy/SearchTelemetry.java', '        String expression = transformation.transformedExpression();',
    '        String expression = transformation.transformedExpression();\n        var producedScore = scorer.score(expression);')
replace(S + 'search/strategy/SearchTelemetry.java', '            scorer.score(expression).weightedTotal(),',
    '            producedScore.weightedTotal(),')
replace(S + 'search/strategy/SearchTelemetry.java',
    '            de.regelsuche.transform.RecordedExecution.capture(state.expression(), java.util.List.of(transformation))\n',
    '            de.regelsuche.transform.RecordedExecution.capture(state.expression(), java.util.List.of(transformation)),\n            producedScore.scoringRevision()\n')
# This writer remains observational, and retains the event's own revision.
path = S + 'search/telemetry/SearchEventJson.java'
text = Path(path).read_text()
anchor = '.property("score", event.score())'
assert text.count(anchor) == 1
write(path, text.replace(anchor, anchor + '\n            .property("scoringRevision", event.scoringRevision())'))

add_revision_record(S + 'search/learning/SearchTrajectoryRecord.java', 'SearchTrajectoryRecord')
replace(S + 'search/learning/SearchTrajectoryRecord.java', 'regelsuche.search-trajectory/v2', 'regelsuche.search-trajectory/v3')
replace(S + 'search/learning/SearchTrajectoryRecord.java', '    public boolean decision() {',
    '    /** Old schemas cannot assert the current scoring contract by merely adding a label. */\n'
    '    public void requireCurrentScoring() {\n'
    '        if (!SCHEMA.equals(schema)) {\n'
    '            throw new IllegalArgumentException("unsupported scoring trajectory schema: " + schema);\n'
    '        }\n        ' + R + '.requireCurrent(scoringRevision);\n    }\n\n'
    '    public boolean decision() {')
replace(S + 'search/learning/SearchTrajectoryCollector.java',
    '        int parentScore = event.parentExpression().isBlank()\n            ? event.score()\n            : problem.scorer().score(event.parentExpression()).weightedTotal();',
    '        int parentScore = event.score();\n'
    '        if (!event.parentExpression().isBlank()) {\n'
    '            var producedParentScore = problem.scorer().score(event.parentExpression());\n'
    '            if (!event.scoringRevision().equals(producedParentScore.scoringRevision())) {\n'
    '                throw new IllegalArgumentException("parent and child use different scoring revisions");\n'
    '            }\n            parentScore = producedParentScore.weightedTotal();\n        }')
replace(S + 'search/learning/SearchTrajectoryCollector.java', '            result.status());',
    '            result.status(),\n            event.scoringRevision());')
replace(S + 'search/learning/SearchTrajectoryRun.java', '                record.terminalStatus()))',
    '                record.terminalStatus(),\n                record.scoringRevision()))')
replace(S + 'search/learning/SearchTrajectoryDataset.java', 'regelsuche.search-trajectory-dataset/v2', 'regelsuche.search-trajectory-dataset/v3')
replace(S + 'search/learning/SearchTrajectoryDataset.java',
    '        ExpressionFeatures features = record.features();',
    '        // Preserve the original schema and numerical meaning of legacy records.\n'
    '        if (SearchTrajectoryRecord.SCHEMA.equals(record.schema())) {\n'
    '            json.property("scoringRevision", record.scoringRevision());\n        }\n'
    '        ExpressionFeatures features = record.features();')
replace(S + 'search/learning/SearchTrajectoryDataset.java', '            .property("schema", SCHEMA)',
    '            .property("schema", SCHEMA)\n'
    '            .stringArray("scoringRevisions", runs.stream().flatMap(run -> run.records().stream())\n'
    '                .map(SearchTrajectoryRecord::scoringRevision).distinct().sorted().toList())')

add_revision_record(S + 'search/learning/SearchExperienceRepository.java', 'SearchExperience')
replace(S + 'search/learning/SearchExperienceRepository.java', '    default void store(SearchTrajectoryRun run) {',
    '    default void store(SearchTrajectoryRun run) {\n'
    '        // Preflight the whole batch before mutating an experience repository.\n'
    '        run.records().forEach(SearchTrajectoryRecord::requireCurrentScoring);')
replace(S + 'search/learning/SearchExperienceRepository.java', '        static SearchExperience from(SearchTrajectoryRecord record) {',
    '        static SearchExperience from(SearchTrajectoryRecord record) {\n            record.requireCurrentScoring();')
replace(S + 'search/learning/SearchExperienceRepository.java', '                record.pruningReason());',
    '                record.pruningReason(),\n                record.scoringRevision());')
replace(S + 'search/learning/InMemorySearchExperienceRepository.java', '        Objects.requireNonNull(experience, "experience");',
    '        Objects.requireNonNull(experience, "experience");\n        ' + R + '.requireCurrent(experience.scoringRevision());')

for name in ('SearchPolicyTrainer', 'DescriptorPolicyTrainer'):
    path = S + 'search/policy/' + name + '.java'
    old = '            .filter(run -> run.context().split() == DatasetSplit.TRAIN)\n            .toList();'
    replace(path, old, old + '\n        trainingRuns.forEach(run -> run.records().forEach(SearchTrajectoryRecord::requireCurrentScoring));')
replace(S + 'search/policy/SearchPolicyModel.java', 'regelsuche.search-policy-features/v1', 'regelsuche.search-policy-features/v2')
replace(S + 'search/policy/DescriptorPolicyModel.java', 'public static final String FEATURE_SCHEMA = TransformationDescriptor.SCHEMA;',
    'public static final String FEATURE_SCHEMA = TransformationDescriptor.SCHEMA\n        + ";score=" + ' + R + '.CURRENT;')

replace(S + 'search/strategy/SearchStateReplay.java', 'regelsuche.search-state-replay/v1', 'regelsuche.search-state-replay/v2')
replace(S + 'search/strategy/SearchStateReplay.java', '.beginObject().property("schema", SCHEMA)',
    '.beginObject().property("schema", SCHEMA)\n'
    '            .property("scoringRevision", state.score() == null ? ' + R + '.UNSPECIFIED : state.score().scoringRevision())')
replace(S + 'search/strategy/SearchStateReplay.java', '.object("score", score -> score.property("stringLength", state.score().stringLength())',
    '.object("score", score -> score.property("scoringRevision", state.score().scoringRevision())\n'
    '                .property("stringLength", state.score().stringLength())')
replace(S + 'search/strategy/SearchStateReplay.java',
    '        String expected = SearchReplayArtifact.load(file, reference);',
    '        return verifyArtifact(file, reference, ' + R + '.CURRENT, independentlyReplayedState);\n'
    '    }\n\n'
    '    public static SearchState verifyArtifact(Path file, SearchReplayArtifact.Reference reference,\n'
    '            String expectedScoringRevision, Supplier<SearchState> independentlyReplayedState) throws IOException {\n'
    '        String expected = SearchReplayArtifact.load(file, reference);\n'
    '        ' + R + '.requireReplay(expected, SCHEMA, expectedScoringRevision);')
replace(S + 'search/strategy/WorkSearchReplay.java', 'regelsuche.work-search-replay/v1', 'regelsuche.work-search-replay/v2')
replace(S + 'search/strategy/WorkSearchReplay.java',
    '        Objects.requireNonNull(expectedCanonicalJson, "expectedCanonicalJson");',
    '        return verify(expectedCanonicalJson, independentlyVerifiedProblem, ' + R + '.CURRENT);\n'
    '    }\n\n'
    '    public static Result verify(String expectedCanonicalJson, Problem independentlyVerifiedProblem,\n'
    '            String expectedScoringRevision) {\n'
    '        Objects.requireNonNull(expectedCanonicalJson, "expectedCanonicalJson");\n'
    '        ' + R + '.requireReplay(expectedCanonicalJson, SCHEMA, expectedScoringRevision);')
replace(S + 'search/strategy/WorkSearchReplay.java',
    '        String expected = SearchReplayArtifact.load(file, reference);\n'
    '        return verify(expected, Objects.requireNonNull(independentlyVerifiedProblem, "verified problem source").get());',
    '        return verifyArtifact(file, reference, ' + R + '.CURRENT, independentlyVerifiedProblem);\n'
    '    }\n\n'
    '    public static Result verifyArtifact(java.nio.file.Path file, SearchReplayArtifact.Reference reference,\n'
    '            String expectedScoringRevision, java.util.function.Supplier<Problem> independentlyVerifiedProblem) throws java.io.IOException {\n'
    '        String expected = SearchReplayArtifact.load(file, reference);\n'
    '        ' + R + '.requireReplay(expected, SCHEMA, expectedScoringRevision);\n'
    '        return verify(expected, Objects.requireNonNull(independentlyVerifiedProblem, "verified problem source").get(), expectedScoringRevision);')
replace(S + 'search/strategy/WorkSearchReplay.java', '.beginObject().property("schema", SCHEMA)',
    '.beginObject().property("schema", SCHEMA)\n            .property("scoringRevision", result.bestState().score().scoringRevision())')
replace(S + 'search/strategy/WorkSearchReplay.java', '.object("score", item -> item.property("stringLength", state.score().stringLength())',
    '.object("score", item -> item.property("scoringRevision", state.score().scoringRevision())\n'
    '                .property("stringLength", state.score().stringLength())')

path = 'app/src/main/java/de/regelsuche/export/DefaultTransformationExportService.java'
text = Path(path).read_text()
anchor = '.property("stringLength", score.stringLength())'
assert text.count(anchor) == 1
write(path, text.replace(anchor, '.property("scoringRevision", score.scoringRevision())\n            ' + anchor))
replace('app/src/main/java/de/regelsuche/export/DefaultTransformationImportService.java',
    '            intValue(values.get("recognizedPatternBonus"), 0)\n',
    '            intValue(values.get("recognizedPatternBonus"), 0),\n'
    '            stringValue(values.get("scoringRevision"), ' + R + '.UNSPECIFIED)\n')

# Existing synthetic test fixtures explicitly identify the semantics they exercise.
# Neither historical artifacts nor unknown/custom production values are relabelled.
path = T + 'scoring/ScopedSymbolScoringTest.java'
text = Path(path).read_text()
for values in ('4, 4, 1, 0, 0', '7, 7, 1, 0, 0', '6, 4, 0, 1, 0'):
    assert text.count('new ExpressionScore(' + values + ')') == 1
    text = text.replace('new ExpressionScore(' + values + ')', 'new ExpressionScore(' + values + ', ScoreRevision.CURRENT)')
write(path, text)
for path in Path(T).rglob('*.java'):
    text = path.read_text()
    if 'new SearchExperience(' in text:
        write(str(path), append_call_argument(text, 'new SearchExperience(', R + '.CURRENT'))

schema_path = 'docs/schemas/regelsuche-search-trajectory-v3.schema.json'
old_schema = Path('docs/schemas/regelsuche-search-trajectory-v2.schema.json').read_text()
schema = json.loads(old_schema.replace('search-trajectory-v2', 'search-trajectory-v3').replace('search-trajectory/v2', 'search-trajectory/v3'))
schema['properties']['scoringRevision'] = {'type':'string','minLength':1}
schema['required'].append('scoringRevision')
write(schema_path, json.dumps(schema, indent=2) + '\n')

write(T + 'scoring/ScoreRevisionAdmissionTest.java', '''package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.strategy.SearchReplayArtifact;
import de.regelsuche.search.strategy.SearchStateReplay;
import de.regelsuche.search.strategy.WorkSearchReplay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoreRevisionAdmissionTest {
    @TempDir Path directory;

    @Test void legacyStateArtifactsAreRejectedBeforeSourceReconstruction() throws Exception {
        assertRejectedBeforeSupplier("{\\"schema\\":\\"regelsuche.search-state-replay/v1\\"}", false);
    }
    @Test void legacyWorkArtifactsAreRejectedBeforeSourceReconstruction() throws Exception {
        assertRejectedBeforeSupplier("{\\"schema\\":\\"regelsuche.work-search-replay/v1\\"}", true);
    }
    @Test void missingAndForeignRevisionsAreRejectedBeforeSourceReconstruction() throws Exception {
        for (boolean work : new boolean[]{false,true}) {
            String schema = work ? WorkSearchReplay.SCHEMA : SearchStateReplay.SCHEMA;
            assertRejectedBeforeSupplier("{\\"schema\\":\\"" + schema + "\\"}", work);
            assertRejectedBeforeSupplier("{\\"schema\\":\\"" + schema + "\\",\\"scoringRevision\\":\\"custom/v1\\"}", work);
        }
    }
    @Test void nestedScoresCannotClaimAnotherRevision() {
        String json = "{\\"schema\\":\\"" + SearchStateReplay.SCHEMA + "\\",\\"scoringRevision\\":\\""
            + ScoreRevision.CURRENT + "\\",\\"score\\":{\\"scoringRevision\\":\\"old/v1\\"}}";
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireReplay(json, SearchStateReplay.SCHEMA, ScoreRevision.CURRENT));
    }
    @Test void explicitCustomContractsAreAllowedButUnknownContractsAreNot() {
        String json = "{\\"schema\\":\\"example/v2\\",\\"scoringRevision\\":\\"custom/v1\\"}";
        assertDoesNotThrow(() -> ScoreRevision.requireReplay(json, "example/v2", "custom/v1"));
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireReplay(json, "example/v2", null));
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireReplay(json, "example/v2", ScoreRevision.UNSPECIFIED));
    }
    private void assertRejectedBeforeSupplier(String json, boolean work) throws Exception {
        Path file = directory.resolve("artifact.json");
        Files.writeString(file, json);
        var reference = SearchReplayArtifact.describe(json);
        var called = new AtomicBoolean();
        if (work) assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verifyArtifact(file, reference, () -> {
            called.set(true); throw new AssertionError("must not reconstruct sources");
        }));
        else assertThrows(IllegalArgumentException.class, () -> SearchStateReplay.verifyArtifact(file, reference, () -> {
            called.set(true); throw new AssertionError("must not reconstruct sources");
        }));
        assertFalse(called.get());
    }
}
''')

write(T + 'search/learning/ScoringProvenanceTest.java', '''package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonReader;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.ScoreRevision;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.policy.DescriptorPolicyModel;
import de.regelsuche.search.policy.DescriptorPolicyTrainer;
import de.regelsuche.search.policy.SearchPolicyModel;
import de.regelsuche.search.policy.SearchPolicyTrainer;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchStateReplay;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringProvenanceTest {
    @Test void builtInProducerRevisionSurvivesEventsSplitJsonAndExperience() {
        var run = run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN);
        assertTrue(run.records().stream().allMatch(r -> ScoreRevision.CURRENT.equals(r.scoringRevision())));
        String json = new SearchTrajectoryDataset(List.of(run)).toJsonLines();
        assertTrue(json.lines().allMatch(line -> ScoreRevision.CURRENT.equals(new JsonReader(line).readObject().get("scoringRevision"))));
        var repository = new InMemorySearchExperienceRepository();
        repository.store(run);
        var decision = run.records().stream().filter(SearchTrajectoryRecord::decision).findFirst().orElseThrow();
        assertTrue(repository.findByShape("provenance", decision.parent().alphaShapeHash(), 10).stream()
            .allMatch(e -> ScoreRevision.CURRENT.equals(e.scoringRevision())));
        assertTrue(repository.summary().total() > 0);
    }
    @Test void bothTrainersAcceptFreshProducerBoundValues() {
        var dataset = new SearchTrajectoryDataset(List.of(run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN)));
        assertDoesNotThrow(() -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.values()[0], 1));
        assertDoesNotThrow(() -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
    }
    @Test void rawCustomScoresRemainUnknownAndCannotEnterEitherTrainer() {
        assertNotAdmitted(run(custom(ScoreRevision.UNSPECIFIED)), ScoreRevision.UNSPECIFIED);
    }
    @Test void explicitlyNamedCustomScoresArePreservedRatherThanRelabelled() {
        assertNotAdmitted(run(custom("custom/v1")), "custom/v1");
    }
    @Test void legacySchemasCannotBeAdmittedByAddingTheCurrentLabel() {
        var current = run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN);
        var legacy = new SearchTrajectoryRun(current.context(), current.root(), current.target(),
            current.taskValueFingerprint(), current.taskAlphaFingerprint(), current.terminalStatus(), current.success(),
            current.records().stream().map(r -> copy(r, "regelsuche.search-trajectory/v2", ScoreRevision.CURRENT)).toList());
        var dataset = new SearchTrajectoryDataset(List.of(legacy));
        assertThrows(IllegalArgumentException.class, () -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.values()[0], 1));
        assertThrows(IllegalArgumentException.class, () -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
        assertFalse(dataset.toJsonLines().contains("scoringRevision"), "legacy export must retain its old schema");
    }
    @Test void unknownRevisionIsNotLostWhenSplittingOrStoringABatch() {
        var current = run(new ExpressionScorer());
        var unknown = new SearchTrajectoryRun(current.context(), current.root(), current.target(),
            current.taskValueFingerprint(), current.taskAlphaFingerprint(), current.terminalStatus(), current.success(),
            current.records().stream().map(r -> copy(r, r.schema(), ScoreRevision.UNSPECIFIED)).toList());
        var split = unknown.withSplit(DatasetSplit.TRAIN);
        assertTrue(split.records().stream().allMatch(r -> ScoreRevision.UNSPECIFIED.equals(r.scoringRevision())));
        var repository = new InMemorySearchExperienceRepository();
        assertThrows(IllegalArgumentException.class, () -> repository.store(split));
        assertEquals(0, repository.summary().total());
    }
    @Test void replaySerializationUsesTheActualScorerContract() {
        var scorer = custom("custom/v1");
        var problem = problem(scorer, new SearchTrajectoryCollector());
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var json = new JsonReader(SearchStateReplay.toCanonicalJson(result.reachedState())).readObject();
        assertEquals("custom/v1", json.get("scoringRevision"));
    }
    private void assertNotAdmitted(SearchTrajectoryRun run, String revision) {
        var split = run.withSplit(DatasetSplit.TRAIN);
        assertTrue(split.records().stream().allMatch(r -> revision.equals(r.scoringRevision())));
        var dataset = new SearchTrajectoryDataset(List.of(split));
        assertTrue(dataset.toJsonLines().contains(revision));
        assertThrows(IllegalArgumentException.class, () -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.values()[0], 1));
        assertThrows(IllegalArgumentException.class, () -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
    }
    private ExpressionScorer custom(String revision) {
        return new ExpressionScorer() {
            @Override public ExpressionScore score(String expression) {
                var value = super.score(expression);
                return new ExpressionScore(value.stringLength(), value.astNodeCount(), value.operatorCount(),
                    value.nestingDepth(), value.recognizedPatternBonus(), revision);
            }
        };
    }
    private SearchTrajectoryRun run(ExpressionScorer scorer) {
        var collector = new SearchTrajectoryCollector();
        var problem = problem(scorer, collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached());
        return collector.finish(problem, result, new SearchTrajectoryContext("score-run", "provenance", "test-v1",
            List.of("remove-zero"), DatasetSplit.UNASSIGNED));
    }
    private SearchProblem problem(ExpressionScorer scorer, SearchTrajectoryCollector collector) {
        TransformationEngine engine = expression -> expression.equals("x + 0")
            ? List.of(new Transformation("remove-zero", "x", RewriteKind.NORMALIZE, false, 0, true, "remove-zero:x")) : List.of();
        return new SearchProblem("x + 0", engine, scorer, new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 8, 40, 20))
            .withTarget(SearchProblem.SearchTarget.syntaxExact("x")).withObserver(collector);
    }
    private SearchTrajectoryRecord copy(SearchTrajectoryRecord r, String schema, String revision) {
        return new SearchTrajectoryRecord(schema, r.producerVersion(), r.runId(), r.family(), r.split(), r.ruleInventoryHash(),
            r.sequence(), r.eventType(), r.expression(), r.parent(), r.target(), r.features(), r.transformationDescriptor(),
            r.depth(), r.score(), r.parentScore(), r.frontierSize(), r.visitedCount(), r.generatedCount(), r.ruleId(),
            r.rewriteKind(), r.applicableRuleIds(), r.assumptions(), r.pruningReason(), r.eventualSuccess(),
            r.selectedPath(), r.terminalStatus(), revision);
    }
}
''')

path = 'docs/scoped-symbol-search-integration.md'
text = Path(path).read_text()
text += '''\n\n## Producer-bound scoring provenance\n\n`ExpressionScore.scoringRevision` records the producer contract when the score is\ncreated. The built-in scorer uses `regelsuche.expression-score/v2`, covering both\nwhole-identifier quadratic recognition and scoped-symbol heuristic costs. The\nlegacy numeric constructor deliberately records `unspecified`; historical or\ncustom numbers are not relabelled as current built-in scores.\n\nTelemetry retains that contract, trajectory v3 carries it through split copies\nand JSONL, and both built-in trainers preflight TRAIN records before consuming\nscore deltas. The experience repository preflights complete batches before\nmutation. Old schemas and unknown/custom score contracts are not admitted to the\nbuilt-in training contract. Named custom scores remain usable in ordinary search\nand export; this change does not discard their numerical values. Policy feature\nversions distinguish old models from models learned with the new semantics.\n\nState/work replay formats are v2. Artifact size/digest, schema, producer revision\nand nested score revisions are checked before a supplied source reconstruction\nfunction executes. Explicit overloads allow a caller to agree a named custom\ncontract; the default contract is the current built-in scorer. Serialized data\nnever becomes proof authority. Import/export retain each score's own revision;\nmissing historical metadata stays `unspecified`. Old artifacts and frozen study\ninputs are not rewritten, nor are performance thresholds or work budgets changed.\n'''
write(path, text)
Path(os.environ['RUNNER_TEMP'], 'pr1006-changed-paths.txt').write_text('\n'.join(sorted(changed))+'\n')
print('Prepared source files:', len(changed))
for path in sorted(changed): print(path)
# Surface any additional live schema consumers for review; do not rewrite frozen evidence.
subprocess.run(['git','grep','-n','regelsuche-search-trajectory-v2.schema.json','--',':!docs/generated',':!paper'], check=False)
