package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.representation.RepresentationCorrespondenceClassifier;
import de.regelsuche.discovery.representation.RepresentationCorrespondenceClassifier.Correspondence;
import de.regelsuche.discovery.representation.TargetFreeIntrinsicCandidateValidator;
import de.regelsuche.discovery.representation.TargetFreeIntrinsicCandidateValidator.IntrinsicValidation;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.validation.OracleValidator.OracleValidation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Public calibration only. Candidates are committed before historical correspondence is assessed. */
public final class HistoricalIntrinsicCalibration {
    private HistoricalIntrinsicCalibration() { }

    enum Policy { SCALAR, STRUCTURAL_DIVERSITY }

    record Occurrence(String expression, int depth, List<String> rules, List<String> assumptions,
                      List<Boolean> preserving) {
        Occurrence {
            rules = List.copyOf(rules);
            assumptions = List.copyOf(assumptions);
            preserving = List.copyOf(preserving);
            if (depth != rules.size() || depth != preserving.size()) {
                throw new IllegalArgumentException("incomplete occurrence lineage");
            }
        }

        static Occurrence from(SearchState state) {
            return new Occurrence(state.expression(), state.depth(), state.appliedRuleIds(),
                state.assumptions(), state.equivalencePreservingFlags());
        }
    }

    record Row(int caseIndex, Policy policy, String source, List<Occurrence> occurrences) {
        Row {
            occurrences = List.copyOf(occurrences);
            if (occurrences.stream().filter(o -> o.depth() == 0).count() != 1
                    || occurrences.stream().noneMatch(o -> o.depth() == 0 && o.expression().equals(source))) {
                throw new IllegalArgumentException("frozen occurrence set lacks its unique source");
            }
        }
    }

    record Assessment(IntrinsicValidation validation, boolean exactMatch, boolean acMatch, boolean credited) { }

    static List<Occurrence> form(String source, SearchHeuristic limits, Policy policy) {
        var primitives = AstRewriteTransformationEngines.production(
            AstRewriteTransformationEngine.defaultRules(), 128, Math.max(200, limits.maxCandidatesPerState() * 2));
        var problem = new SearchProblem(source, primitives, new ExpressionScorer(), new ExpressionCanonicalizer(), limits);
        List<SearchState> states = switch (policy) {
            case SCALAR -> new BestFirstSearchStrategy().searchWithDiagnostics(problem).states();
            case STRUCTURAL_DIVERSITY -> new StructuralDiversitySearchStrategy().search(problem);
        };
        return states.stream().map(Occurrence::from).toList();
    }

    static IntrinsicValidation validate(String source, Occurrence occurrence) {
        return TargetFreeIntrinsicCandidateValidator.validate(source, occurrence.expression(), occurrence.assumptions(),
            occurrence.preserving().stream().allMatch(Boolean::booleanValue), HistoricalIntrinsicCalibration::comparePolynomials);
    }

    private static OracleValidation comparePolynomials(String source, String expression) {
        var exact = new ExactPolynomialAnalysis();
        try {
            boolean same = exact.normalForm(source).equals(exact.normalForm(expression));
            return same ? OracleValidation.agrees("Exact rational-polynomial normal forms agree")
                : OracleValidation.disagrees("Exact rational-polynomial normal forms differ");
        } catch (IllegalArgumentException unsupported) {
            return OracleValidation.unavailable("Outside bounded exact rational-polynomial projection");
        }
    }

    static List<Assessment> assess(String source, List<Occurrence> occurrences, String target, TargetRelation relation) {
        var classifier = new RepresentationCorrespondenceClassifier();
        String formattedTarget = format(target);
        boolean useExact = relation == TargetRelation.SYNTAX_EXACT;
        boolean sourceMatches = useExact ? format(source).equals(formattedTarget)
            : classifier.classify(source, target) == Correspondence.SAME_REPRESENTATION_CLASS;
        return occurrences.stream().map(o -> {
            IntrinsicValidation validation = validate(source, o);
            boolean exact = format(o.expression()).equals(formattedTarget);
            boolean ac = classifier.classify(o.expression(), target) == Correspondence.SAME_REPRESENTATION_CLASS;
            boolean credited = !sourceMatches && o.depth() > 0 && validation.intrinsicallyVerified() && (useExact ? exact : ac);
            return new Assessment(validation, exact, ac, credited);
        }).toList();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("expected new output directory");
        Path output = Files.createDirectory(Path.of(args[0]));
        var corpus = HistoricalRediscoveryCorpus.load();
        var rows = new ArrayList<Row>();
        for (int index = 0; index < corpus.cases().size(); index++) {
            Case entry = corpus.cases().get(index);
            String source = format(entry.source());
            var limits = new SearchHeuristic(entry.searchMaxDepth(), entry.searchMaxVisitedStates(), 1,
                entry.maxExpandingSteps(), entry.maxCandidatesPerState(), entry.beamWidth());
            for (Policy policy : Policy.values()) {
                rows.add(new Row(index, policy, source, form(source, limits, policy)));
            }
        }
        JsonWriter freeze = new JsonWriter().beginObject().property("schema", "historical-intrinsic-freeze/v1")
            .property("corpusSha256", corpus.contentSha256()).property("inventory", corpus.inventoryRevision());
        freeze.array("rows", array -> rows.forEach(row -> array.objectValue(value -> writeFreezeRow(value, row)))).endObject();
        Path frozenFile = Files.writeString(output.resolve("freeze.json"), freeze + "\n", StandardCharsets.UTF_8);
        String freezeHash = sha256(Files.readAllBytes(frozenFile));

        JsonWriter evidence = new JsonWriter().beginObject().property("schema", "historical-intrinsic-assessment/v1")
            .property("freezeSha256", freezeHash).property("oracle", ExactPolynomialAnalysis.REVISION);
        evidence.array("rows", array -> rows.forEach(row -> array.objectValue(value ->
            writeAssessmentRow(value, row, corpus.cases().get(row.caseIndex()))))).endObject();
        Files.writeString(output.resolve("assessment.json"), evidence + "\n", StandardCharsets.UTF_8);
    }

    private static void writeFreezeRow(JsonWriter out, Row row) {
        out.property("caseIndex", row.caseIndex()).property("policy", row.policy().name()).property("source", row.source());
        out.array("occurrences", array -> row.occurrences().forEach(o -> array.objectValue(item ->
            item.property("expression", o.expression()).property("depth", o.depth()).stringArray("rules", o.rules())
                .stringArray("assumptions", o.assumptions()).booleanArray("equivalencePreserving", o.preserving()))));
    }

    private static void writeAssessmentRow(JsonWriter out, Row row, Case entry) {
        List<Assessment> assessed = assess(row.source(), row.occurrences(), entry.target(), entry.targetRelation());
        out.property("caseIndex", row.caseIndex()).property("caseId", entry.id()).property("role", entry.role().name())
            .property("policy", row.policy().name()).property("target", entry.target()).property("criterion", entry.targetRelation().name());
        out.array("occurrences", array -> assessed.forEach(a -> array.objectValue(item ->
            item.property("oracleStatus", a.validation().oracleStatus()).property("oracleEvidence", a.validation().oracleEvidence())
                .property("proofStatus", a.validation().status().name()).property("scope", a.validation().scope().name())
                .property("exactMatch", a.exactMatch()).property("acMatch", a.acMatch()).property("credited", a.credited()))));
    }

    private static String format(String expression) {
        return ExpressionFormatter.format(new ExpressionParser().parseTerm(expression));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
