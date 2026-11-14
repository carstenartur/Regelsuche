package de.regelsuche.release;

import static de.regelsuche.release.TrackCRecurrenceComparison.*;

import com.fasterxml.jackson.databind.JsonNode;

import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateContext;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.SequenceState;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Bounded prospective Newton grammar comparison on the public frozen sequence panel. */
public final class TrackCFiniteDifferenceComparison {
    private static final String PLAN_HASH =
            "sha256:de9d3735d5e986bdf03087e8d5568a1cb30c1df3e8ebc5c7b87ecb84343d493c";
    private static final String PLAN_COMMIT = "85c2150586c68aa3436f6b17f84cb54bf5ff7eb9";

    private enum Arm {
        PRODUCTION_DIFFERENCE_DOMAIN,
        ENUMERATE_VALID_GRAMMAR,
        SEEDED_RANDOM_VALID_GRAMMAR
    }

    private record Prefix(List<Long> terms, int maximumOrder) {
        int degreeLimit() {
            return Math.min(8, Math.min(maximumOrder, terms.size() - 2));
        }
    }

    private record Frozen(
            int campaign,
            Arm arm,
            String seed,
            List<List<Long>> models,
            Map<String, Object> evidence,
            String hash) {}

    private TrackCFiniteDifferenceComparison() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "usage: <frozen corpus directory> <output.json>");
        Path data = Path.of(args[0]);
        String planBytes =
                Files.readString(data.resolve("track-c-finite-differences/comparison-plan.json"));
        require(PLAN_HASH.equals(hash(planBytes)), "changed comparison plan");
        JsonNode plan = JSON.readTree(planBytes);
        JsonNode corpus =
                frozen(data, "case-corpus", plan.required("caseCorpusContentHash").textValue());
        frozen(
                data,
                "corpus-freeze-receipt",
                plan.required("freezeReceiptContentHash").textValue());
        frozen(
                data,
                "finite-sequence-candidate-forms",
                plan.required("formationProfileContentHash").textValue());

        var cases = new ArrayList<JsonNode>();
        var inputs = new ArrayList<Prefix>();
        for (JsonNode row : corpus.required("cases")) {
            if (!plan.required("challengeId").equals(row.required("challengeId"))) continue;
            cases.add(row);
            if ("TRAIN".equals(row.required("split").textValue())) {
                JsonNode visible = row.required("formationInput");
                inputs.add(
                        new Prefix(
                                terms(visible.required("observedPrefix")),
                                visible.required("maximumOrder").intValue()));
            }
        }
        require(cases.size() == 6 && inputs.size() == 2, "unexpected frozen panel");
        var runs = new ArrayList<Frozen>();
        for (int campaign = 1; campaign <= plan.required("campaignCount").intValue(); campaign++) {
            String seed = campaignSeed(corpus.required("benchmarkId").textValue(), campaign);
            for (Arm arm : Arm.values()) runs.add(form(campaign, arm, seed, inputs, plan));
        }
        var report = new ArrayList<Map<String, Object>>();
        for (Frozen run : runs) {
            var rows = cases.stream().map(row -> evaluate(run.models(), row)).toList();
            report.add(
                    Map.of(
                            "campaign",
                            run.campaign(),
                            "arm",
                            run.arm(),
                            "seed",
                            run.seed(),
                            "formation",
                            run.evidence(),
                            "formationHash",
                            run.hash(),
                            "evaluations",
                            rows));
        }
        Files.writeString(
                Path.of(args[1]),
                JSON.writeValueAsString(
                                Map.of(
                                        "schema",
                                        "regelsuche.track-c-finite-difference-comparison/v1",
                                        "planCommit",
                                        PLAN_COMMIT,
                                        "planByteHash",
                                        PLAN_HASH,
                                        "corpusContentHash",
                                        corpus.required("contentHash"),
                                        "campaigns",
                                        report))
                        + "\n");
    }

    private static Frozen form(
            int campaign, Arm arm, String seed, List<Prefix> inputs, JsonNode plan)
            throws Exception {
        Random random = new Random(Long.parseUnsignedLong(seed.substring(7, 23), 16));
        int low = plan.required("minimumCoefficient").intValue(),
                high = plan.required("maximumCoefficient").intValue();
        int cap = plan.required("proposalsPerTrainPrefix").intValue();
        var models = new LinkedHashSet<List<Long>>();
        var evidence = new ArrayList<Map<String, Object>>();
        for (Prefix input : inputs) {
            var attempts = new ArrayList<Map<String, Object>>();
            var degrees = new ArrayList<Integer>();
            var proposed = new ArrayList<List<Long>>();
            int grammarSize = grammarSize(input.degreeLimit(), high - low + 1);
            if (arm == Arm.PRODUCTION_DIFFERENCE_DOMAIN) {
                proposed.addAll(production(input, degrees));
            } else {
                int count =
                        arm == Arm.ENUMERATE_VALID_GRAMMAR
                                ? Math.min(grammarSize, cap)
                                : (grammarSize == 0 ? 0 : cap);
                for (int i = 0; i < count; i++) {
                    int rank = arm == Arm.ENUMERATE_VALID_GRAMMAR ? i : random.nextInt(grammarSize);
                    proposed.add(unrank(input.terms().getFirst(), rank, low, high));
                }
            }
            var seen = new LinkedHashSet<List<Long>>();
            for (var model : proposed) {
                boolean valid =
                        model.size() >= 2
                                && model.size() - 1 <= input.degreeLimit()
                                && model.getLast() != 0
                                && model.getFirst().equals(input.terms().getFirst())
                                && model.subList(1, model.size()).stream()
                                        .allMatch(a -> low <= a && a <= high);
                var predicted = predictions(model, input.terms().size());
                boolean duplicate = !seen.add(model) || models.contains(model);
                boolean matches = matches(predicted, input.terms());
                if (valid && matches) models.add(model);
                attempts.add(
                        Map.of(
                                "coefficients",
                                model,
                                "predictions",
                                predicted,
                                "duplicate",
                                duplicate,
                                "disposition",
                                !valid ? "OUTSIDE_GRAMMAR" : matches ? "SUPPORTED" : "REJECTED"));
            }
            int subtractions = degrees.stream().mapToInt(d -> input.terms().size() - d).sum();
            evidence.add(
                    Map.of(
                            "prefix",
                            input.terms(),
                            "maximumOrder",
                            input.maximumOrder(),
                            "grammarSize",
                            grammarSize,
                            "domainLevels",
                            degrees,
                            "domainSubtractions",
                            subtractions,
                            "attempts",
                            attempts,
                            "unusedSlots",
                            cap - attempts.size(),
                            "prefixChecks",
                            attempts.size() * input.terms().size()));
        }
        var frozen = Map.<String, Object>of("inputs", evidence, "models", List.copyOf(models));
        return new Frozen(
                campaign,
                arm,
                seed,
                List.copyOf(models),
                frozen,
                hash(JSON.writeValueAsString(frozen)));
    }

    private static List<List<Long>> production(Prefix input, List<Integer> degrees) {
        var domain = new FiniteDifferenceSequenceDomain();
        var initial =
                new SequenceState(
                        input.terms(), List.of(input.terms().getFirst()), List.of(input.terms()));
        var current = initial;
        for (int degree = 1; degree <= input.degreeLimit(); degree++) {
            var next = domain.operators().getFirst().apply(current);
            if (next.isEmpty()) return List.of();
            current = next.getFirst().state();
            degrees.add(degree);
            if (domain.objective().assess(current).candidateReady()) {
                var candidate =
                        domain.candidateExtractor()
                                .extract(new CandidateContext<>(initial, current, List.of()));
                return List.of(candidate.initialDifferences());
            }
        }
        return List.of();
    }

    private static int grammarSize(int degree, int radix) {
        int total = 0;
        for (int i = 0; i < degree; i++)
            total = Math.addExact(Math.multiplyExact(total, radix), radix - 1);
        return total;
    }

    /** Decode increasing degree, signed lexicographic order; the final digit skips zero. */
    private static List<Long> unrank(long first, int index, int low, int high) {
        int radix = high - low + 1, block = radix - 1, degree = 1;
        while (index >= block) {
            index -= block;
            block = Math.multiplyExact(block, radix);
            degree++;
        }
        var coefficients = new Long[degree + 1];
        coefficients[0] = first;
        int last = low + index % (radix - 1);
        coefficients[degree] = (long) (last < 0 ? last : last + 1);
        index /= radix - 1;
        for (int i = degree - 1; i >= 1; i--) {
            coefficients[i] = (long) (low + index % radix);
            index /= radix;
        }
        return List.of(coefficients);
    }

    private static List<String> predictions(List<Long> model, int length) {
        var result = new ArrayList<String>();
        for (int n = 0; n < length; n++) {
            BigInteger binomial = BigInteger.ONE, value = BigInteger.ZERO;
            for (int k = 0; k < model.size() && k <= n; k++) {
                value = value.add(binomial.multiply(BigInteger.valueOf(model.get(k))));
                binomial =
                        binomial.multiply(BigInteger.valueOf(n - k))
                                .divide(BigInteger.valueOf(k + 1));
            }
            result.add(value.toString());
        }
        return result;
    }

    private static boolean matches(List<String> predicted, List<Long> actual) {
        boolean match = true;
        for (int index = 0; index < actual.size(); index++) {
            match &= predicted.get(index).equals(String.valueOf(actual.get(index)));
        }
        return match;
    }

    private static Map<String, Object> evaluate(List<List<Long>> models, JsonNode row) {
        JsonNode eval = row.required("evaluationInput");
        List<Long> prefix = terms(eval.required("observedPrefix")),
                continuation = terms(eval.required("holdoutContinuation"));
        var outcomes = new ArrayList<Map<String, Object>>();
        int best = 0;
        var statuses = List.of("NO_OUTPUT", "PREFIX_MISMATCH", "REFUTED", "CONFIRMED");
        for (var model : models) {
            var expected = predictions(model, prefix.size() + continuation.size());
            boolean fitsPrefix = matches(expected.subList(0, prefix.size()), prefix);
            boolean fitsContinuation =
                    matches(expected.subList(prefix.size(), expected.size()), continuation);
            int status = !fitsPrefix ? 1 : fitsContinuation ? 3 : 2;
            best = Math.max(best, status);
            outcomes.add(
                    Map.of(
                            "coefficients",
                            model,
                            "predictions",
                            expected,
                            "outcome",
                            statuses.get(status)));
        }
        return Map.of(
                "caseId",
                row.required("caseId"),
                "split",
                row.required("split"),
                "observed",
                prefix,
                "continuation",
                continuation,
                "outcome",
                statuses.get(best),
                "candidates",
                outcomes,
                "prefixChecks",
                models.size() * prefix.size(),
                "continuationChecks",
                models.size() * continuation.size());
    }
}
