package de.regelsuche.release;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.Rational;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.RecurrenceModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Replays the preregistered fixed-rule recurrence lane using only TRAIN prefix observations for formation. */
public final class TrackCRecurrenceComparison {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final String PLAN = "sha256:f7475599a958f1ffe174ee936e00c82f267e59df03588454ab1c0f9957526ad4";
    private static final String PLAN_COMMIT = "95c1eefada1d2e989182e4bc92369e11c4840fc4";
    private static final String CHALLENGE = "finite-difference-recurrences";
    private static final String DIRECTORY = "track-c-recurrence";
    private enum Arm { PRODUCTION_EXACT_FIT, ENUMERATE_VALID_GRAMMAR, SEEDED_RANDOM_VALID_GRAMMAR }

    private TrackCRecurrenceComparison() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <frozen-corpus-directory> <output.json>");
        Path data = Path.of(args[0]);
        String planBytes = Files.readString(data.resolve(DIRECTORY).resolve("comparison-plan.json"));
        require(hash(planBytes).equals(PLAN), "comparison plan changed");
        JsonNode plan = JSON.readTree(planBytes);
        JsonNode corpus = frozen(data, "case-corpus", plan.required("caseCorpusContentHash").textValue());
        frozen(data, "corpus-freeze-receipt", plan.required("freezeReceiptContentHash").textValue());
        frozen(data, "finite-sequence-candidate-forms", plan.required("formationProfileContentHash").textValue());

        List<JsonNode> cases = new ArrayList<>();
        List<Prefix> inputs = new ArrayList<>();
        for (JsonNode item : corpus.required("cases")) {
            if (!CHALLENGE.equals(item.required("challengeId").textValue())) continue;
            cases.add(item);
            if ("TRAIN".equals(item.required("split").textValue())) {
                JsonNode allowed = item.required("formationInput");
                inputs.add(new Prefix(terms(allowed.required("observedPrefix")), allowed.required("maximumOrder").intValue()));
            }
        }
        require(cases.size() == 6 && inputs.size() == 2, "incorrect frozen comparison surface");
        List<Frozen> frozen = new ArrayList<>();
        for (int campaign = 1; campaign <= plan.required("campaignCount").intValue(); campaign++) {
            String seed = campaignSeed(corpus.required("benchmarkId").textValue(), campaign);
            for (Arm arm : Arm.values()) frozen.add(form(arm, campaign, seed, inputs, plan));
        }
        List<Map<String, Object>> campaigns = new ArrayList<>();
        for (Frozen run : frozen) {
            var evaluations = cases.stream().map(item -> evaluate(run.models(), item)).toList();
            campaigns.add(Map.of("campaign", run.index(), "arm", run.arm(), "seed", run.seed(),
                "formation", run.evidence(), "formationHash", run.hash(), "evaluations", evaluations));
        }
        var report = Map.of("schema", "regelsuche.track-c-recurrence-comparison/v1",
            "planCommit", PLAN_COMMIT, "planByteHash", PLAN, "corpusContentHash", corpus.required("contentHash"),
            "campaigns", campaigns);
        Files.writeString(Path.of(args[1]), JSON.writeValueAsString(report) + "\n");
    }

    private record Prefix(List<Long> terms, int maximumOrder) { }
    private record Frozen(int index, Arm arm, String seed, List<RecurrenceModel> models,
                          Map<String, Object> evidence, String hash) { }

    private static Frozen form(Arm arm, int index, String seed, List<Prefix> prefixes, JsonNode plan) throws Exception {
        var rng = new Random(Long.parseUnsignedLong(seed.substring(7, 23), 16));
        var models = new LinkedHashSet<RecurrenceModel>();
        var inputs = new ArrayList<Map<String, Object>>();
        int lower = plan.required("minimumCoefficient").intValue();
        int upper = plan.required("maximumCoefficient").intValue();
        int cap = plan.required("proposalsPerTrainPrefix").intValue();
        for (Prefix prefix : prefixes) {
            List<RecurrenceModel> proposals = proposals(arm, prefix, rng, lower, upper, cap);
            var seen = new LinkedHashSet<RecurrenceModel>();
            var attempts = new ArrayList<Map<String, Object>>();
            for (RecurrenceModel model : proposals) {
                List<Boolean> checks = checkPrefix(model, prefix.terms());
                boolean valid = allowed(model, lower, upper);
                boolean supported = valid && checks.stream().allMatch(Boolean::booleanValue);
                boolean duplicate = !seen.add(model) || models.contains(model);
                if (supported) models.add(model);
                String disposition = !valid ? "OUTSIDE_GRAMMAR" : supported ? "SUPPORTED" : "REJECTED";
                attempts.add(Map.of("coefficients", coefficients(model), "prefixChecks", checks,
                    "disposition", disposition, "duplicate", duplicate));
            }
            inputs.add(Map.of("prefix", prefix.terms(), "maximumOrder", prefix.maximumOrder(), "attempts", attempts));
        }
        var evidence = Map.<String, Object>of("inputs", inputs, "models", models.stream()
            .map(TrackCRecurrenceComparison::coefficients).toList());
        return new Frozen(index, arm, seed, List.copyOf(models), evidence, hash(JSON.writeValueAsString(evidence)));
    }

    private static List<RecurrenceModel> proposals(Arm arm, Prefix input, Random random,
                                                   int low, int high, int cap) {
        if (arm == Arm.PRODUCTION_EXACT_FIT) {
            return LinearRecurrenceSequenceDomain.inferUniqueRecurrence(input.terms(), input.maximumOrder())
                .stream().toList();
        }
        int maximum = Math.min(input.maximumOrder(), input.terms().size() / 2);
        var grammar = new ArrayList<RecurrenceModel>();
        for (int order = 1; order <= maximum; order++) enumerate(grammar, List.of(), order, low, high);
        if (arm == Arm.ENUMERATE_VALID_GRAMMAR) return grammar.stream().limit(cap).toList();
        if (grammar.isEmpty()) return List.of();
        var draws = new ArrayList<RecurrenceModel>(cap);
        for (int draw = 0; draw < cap; draw++) draws.add(grammar.get(random.nextInt(grammar.size())));
        return draws;
    }

    private static void enumerate(List<RecurrenceModel> grammar, List<Rational> prefix, int order, int low, int high) {
        for (int value = low; value <= high; value++) {
            var next = new ArrayList<>(prefix);
            next.add(Rational.of(value));
            if (next.size() < order) enumerate(grammar, next, order, low, high);
            else if (value != 0) grammar.add(new RecurrenceModel(order, next));
        }
    }

    private static boolean allowed(RecurrenceModel model, int low, int high) {
        return !model.coefficients().getLast().isZero() && model.coefficients().stream()
            .allMatch(c -> c.isInteger() && c.numerator().compareTo(java.math.BigInteger.valueOf(low)) >= 0
                && c.numerator().compareTo(java.math.BigInteger.valueOf(high)) <= 0);
    }

    private static Map<String, Object> evaluate(List<RecurrenceModel> models, JsonNode item) {
        JsonNode eval = item.required("evaluationInput");
        List<Long> observed = terms(eval.required("observedPrefix"));
        List<Long> actual = terms(eval.required("holdoutContinuation"));
        var outcomes = new ArrayList<Map<String, Object>>();
        String status = models.isEmpty() ? "NO_OUTPUT" : "PREFIX_MISMATCH";
        for (RecurrenceModel model : models) {
            var prefixChecks = checkPrefix(model, observed);
            var history = new ArrayList<>(observed);
            var predictions = new ArrayList<String>();
            boolean continuationMatches = true;
            for (long term : actual) {
                Rational next = nextTerm(model, history);
                predictions.add(next.canonical());
                continuationMatches &= next.equals(Rational.of(term));
                history.add(term);
            }
            boolean fits = prefixChecks.stream().allMatch(Boolean::booleanValue);
            String outcome = !fits ? "PREFIX_MISMATCH" : continuationMatches ? "CONFIRMED" : "REFUTED";
            if ("CONFIRMED".equals(outcome) || ("REFUTED".equals(outcome) && !"CONFIRMED".equals(status))) status = outcome;
            outcomes.add(Map.of("coefficients", coefficients(model), "prefixChecks", prefixChecks,
                "predictions", predictions, "outcome", outcome));
        }
        return Map.of("caseId", item.required("caseId"), "split", item.required("split"), "outcome", status,
            "observed", observed, "continuation", actual, "candidates", outcomes);
    }

    private static List<Boolean> checkPrefix(RecurrenceModel model, List<Long> observed) {
        var checks = new ArrayList<Boolean>();
        for (int i = model.order(); i < observed.size(); i++) {
            checks.add(nextTerm(model, observed.subList(0, i)).equals(Rational.of(observed.get(i))));
        }
        return checks;
    }

    private static Rational nextTerm(RecurrenceModel model, List<Long> history) {
        Rational value = Rational.of(0);
        for (int lag = 0; lag < model.order(); lag++) {
            value = value.add(model.coefficients().get(lag).multiply(Rational.of(history.get(history.size() - lag - 1))));
        }
        return value;
    }

    private static List<String> coefficients(RecurrenceModel model) {
        return model.coefficients().stream().map(Rational::canonical).toList();
    }

    private static List<Long> terms(JsonNode array) {
        var terms = new ArrayList<Long>();
        for (JsonNode value : array) {
            require(value.isIntegralNumber() && value.canConvertToLong(), "invalid frozen sequence term");
            terms.add(value.longValue());
        }
        return List.copyOf(terms);
    }

    private static ObjectNode frozen(Path data, String name, String expected) throws Exception {
        ObjectNode doc = (ObjectNode) JSON.readTree(data.resolve(name + ".json").toFile());
        ObjectNode content = doc.deepCopy();
        String declared = content.remove("contentHash").textValue();
        String canonical = JSON.writeValueAsString(JSON.treeToValue(content, Map.class));
        require(expected.equals(declared) && expected.equals(hash(canonical)), "frozen hash mismatch: " + name);
        return doc;
    }

    private static String campaignSeed(String benchmark, int index) throws Exception {
        String campaignId = CHALLENGE + "-campaign-" + String.format(java.util.Locale.ROOT, "%02d", index);
        return hash(JSON.writeValueAsString(Map.of("benchmarkId", benchmark, "campaignId", campaignId,
            "challengeId", CHALLENGE, "index", index)));
    }

    private static String hash(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
