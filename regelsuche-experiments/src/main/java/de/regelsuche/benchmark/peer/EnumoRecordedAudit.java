package de.regelsuche.benchmark.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Rechecks retained source-only responses without requiring the original tool. */
public final class EnumoRecordedAudit {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> OUTCOMES = Set.of("VERIFIED", "INVALID", "OUTSIDE_SHARED_GRAMMAR",
        "WORKER_ERROR", "SOURCE_BINDING_REJECTED", "NATIVE_BUDGET_REJECTED", "TIMEOUT", "TECHNICAL_FAILURE",
        "TIMEOUT_AFTER_JUDGE", "BLOCKED_WORKER_UNAVAILABLE");
    private EnumoRecordedAudit() { }

    /** Args: checkout root and retained evidence directory. No native process is executed. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected checkout and evidence directory");
        Path evidence = Path.of(args[1]);
        int verified = verify(Path.of(args[0]), JSON.readTree(readGzip(evidence.resolve("results.json.gz"))),
            readGzip(evidence.resolve("responses.jsonl.gz")), Files.readAllBytes(evidence.resolve("learned.rules")));
        System.out.println(JSON.writeValueAsString(Map.of("status", "VERIFIED", "candidateCount", verified)));
    }

    static int verify(Path checkout, JsonNode report, byte[] raw, byte[] rules) throws Exception {
        String schema = report.path("schema").asText();
        boolean transfer = schema.equals(EnumoPublicComparison.HORNER_TRANSFER);
        boolean horner = transfer || schema.equals(EnumoPublicComparison.HORNER_V2);
        require(horner || schema.equals(EnumoPublicComparison.V1), "report schema");
        String protocolHash = transfer ? EnumoPublicComparison.TRANSFER_PROTOCOL_SHA
            : horner ? EnumoPublicComparison.HORNER_PROTOCOL_SHA : EnumoPublicComparison.PROTOCOL_SHA;
        String corpusHash = transfer ? EnumoPublicComparison.TRANSFER_CORPUS_SHA : EnumoPublicComparison.CORPUS_SHA;
        require(report.path("semantics").asText().equals("EXACT_RATIONAL_PROJECTION_NOT_IEEE_EQUIVALENCE"), "semantics changed");
        require(report.path("corpusSha256").asText().equals(corpusHash), "corpus binding");
        require(report.path("protocolSha256").asText().equals(protocolHash), "protocol binding");
        require(hash(raw).equals(report.path("rawResponsesSha256").asText()), "raw response hash");
        require(hash(rules).equals(report.path("acquiredRulesSha256").asText()), "acquired rule hash");
        verifyProvenance(checkout, report, rules, horner);
        var entries = EnumoPublicComparison.inventory(checkout, horner, transfer);
        require(JSON.valueToTree(entries).equals(report.path("inventory")), "foreign inventory binding");
        var profiles = EnumoPublicComparison.profiles(horner).stream().map(Enum::name).toList();
        require(report.path("rows").isArray() && report.path("rows").size() == 3 * entries.size() * profiles.size(),
            "complete comparison matrix");
        Map<String, JsonNode> responses = responses(raw);
        Map<String, JsonNode> setups = verifySetups(report.path("setups"), report.path("acquisition"), profiles);
        var seen = new HashSet<String>();
        int index = 0, verified = 0;
        for (int repetition = 1; repetition <= 3; repetition++) for (String profile : profiles) {
            boolean workerUnavailable = false;
            for (var entry : entries) {
                JsonNode row = report.path("rows").get(index++);
                require(row.path("profile").asText().equals(profile) && row.path("repetition").asInt() == repetition
                    && row.path("ordinal").asInt() == entry.ordinal(), "row identity/order");
                require(row.path("name").asText().equals(entry.name()) && row.path("source").asText().equals(entry.source()), "row source binding");
                String key = key(row); seen.add(key);
                JsonNode response = responses.get(key);
                if (entry.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED) {
                    require(row.path("status").asText().equals("UNSUPPORTED_INPUT")
                        && row.path("detail").asText().equals(entry.detail()) && response == null, "unsupported input was hidden or relabelled");
                } else {
                    workerUnavailable = verifySupportedRow(row, response, entry.source(), profile,
                        setups.get(profile + ":" + repetition), workerUnavailable);
                    if (row.path("status").asText().equals("VERIFIED")) verified++;
                }
            }
        }
        require(seen.containsAll(responses.keySet()), "unexpected raw response");
        return verified;
    }

    private static boolean verifySupportedRow(JsonNode row, JsonNode response, String source, String profile,
            JsonNode setup, boolean workerUnavailable) throws Exception {
        String outcome = row.path("status").asText();
        require(OUTCOMES.contains(outcome), "unknown outcome");
        if (workerUnavailable) {
            require(outcome.equals("BLOCKED_WORKER_UNAVAILABLE") && response == null
                && !row.has("requestAndJudgeNanos"), "response after worker closure");
            requireNoCandidate(row);
            return true;
        }
        if (response != null) {
            verifyResponse(row, response, source, profile, setup);
            return false;
        }
        require(outcome.equals("TIMEOUT") || outcome.equals("TECHNICAL_FAILURE"), "derived outcome without raw evidence");
        requireNoCandidate(row);
        long elapsed = nonnegative(row, "requestAndJudgeNanos");
        require(elapsed > 0, "failed request interval");
        if (outcome.equals("TIMEOUT")) require(elapsed >= 2_000_000_000L, "timeout before request allowance");
        else require(!row.path("detail").asText().isBlank(), "missing technical failure detail");
        return true;
    }

    private static void requireNoCandidate(JsonNode row) {
        for (String field : List.of("output", "inputCost", "outputCost", "nativeWork"))
            require(!row.has(field), "candidate data on an unanswered request");
    }

    private static void verifyResponse(JsonNode row, JsonNode retained, String source, String profile, JsonNode setup) throws Exception {
        require(retained.path("row").equals(row), "raw and summarized rows disagree");
        boolean peer = profile.equals("ENUMO_LEARNED");
        JsonNode request = retained.path("request"), response = retained.path("response");
        String wire = peer ? FpcorePolynomialCorpus.toPrefix(source) : source;
        String nativeProfile = EnumoPublicComparison.Profile.valueOf(profile).nativeProfile;
        require(request.size() == (peer ? 2 : 3) && request.path("op").asText().equals("run")
            && request.path("source").asText().equals(wire), "source-only request binding");
        require(peer || request.path("profile").asText().equals(nativeProfile), "native request profile");
        long elapsed = nonnegative(row, "requestAndJudgeNanos");
        String outcome = "WORKER_ERROR";
        if (response.path("status").asText().equals("CANDIDATE")) {
            long internal = nonnegative(response, peer ? "nativeNanos" : "nativeWallNanos");
            require(elapsed >= internal, "worker interval exceeds common request interval");
            if (!peer) verifyNativeResponse(response, setup, nativeProfile);
            outcome = candidateResponseOutcome(row, response, source, wire, peer);
            if (elapsed > 2_000_000_000L && outcome.equals("VERIFIED")) outcome = "TIMEOUT_AFTER_JUDGE";
        }
        require(outcome.equals(row.path("status").asText()), "raw response contradicts retained outcome");
    }

    private static String candidateResponseOutcome(JsonNode row, JsonNode response, String source, String wire, boolean peer) {
        if (!response.path(peer ? "source" : "input").asText().equals(wire)) {
            requireNoCandidate(row);
            return "SOURCE_BINDING_REJECTED";
        }
        String outcome = candidateOutcome(row, response, source, peer);
        if (!peer) {
            require(nonnegative(row, "nativeWork") == nonnegative(response, "totalWork"), "native row work binding");
            if (!response.path("internalWorkWithinBudget").asBoolean(false)) return "NATIVE_BUDGET_REJECTED";
        }
        return outcome;
    }

    private static String candidateOutcome(JsonNode row, JsonNode response, String source, boolean peer) {
        String candidate;
        try { candidate = peer ? FpcorePolynomialCorpus.fromPrefix(response.path("output").asText()) : response.path("output").asText(); }
        catch (IllegalArgumentException unsupported) { return "OUTSIDE_SHARED_GRAMMAR"; }
        require(candidate.equals(row.path("output").asText()), "response output binding");
        String outcome = EnumoPublicComparison.judge(source, candidate);
        if (outcome.equals("VERIFIED") || outcome.equals("INVALID")) {
            require(row.path("inputCost").asInt(-1) == EnumoPublicComparison.cost(source)
                && row.path("outputCost").asInt(-1) == EnumoPublicComparison.cost(candidate), "surface costs");
        }
        return outcome;
    }

    private static void verifyNativeResponse(JsonNode response, JsonNode setup, String profile) throws Exception {
        require(response.path("profile").asText().equals(profile), "native response profile");
        require(response.path("modelHash").equals(setup.path("modelHash")), "native frozen model binding");
        require(response.path("target").asText("missing").isEmpty() && !response.path("targetReached").asBoolean(true), "native source-only result");
        JsonNode search = JSON.readTree(response.path("search").asText());
        require(search != null, "retained native search ledger");
        JsonNode metrics = search.path("metrics");
        long searchWork = Math.addExact(Math.addExact(nonnegative(metrics, "primitiveWork"),
            nonnegative(metrics, "searchWork")), nonnegative(metrics, "verificationWork"));
        require(searchWork == nonnegative(search, "totalWork"), "native search work sum");
        long total = Math.addExact(Math.addExact(searchWork, nonnegative(response, "selectionWork")),
            nonnegative(response, "selectedReplayWork"));
        require(total == nonnegative(response, "totalWork"), "native total work sum");
        require(response.path("internalWorkWithinBudget").isBoolean(), "native budget decision");
        boolean withinBudget = accountingComplete(search) && total <= nonnegative(setup.path("settings"), "workBudget");
        require(response.path("internalWorkWithinBudget").asBoolean() == withinBudget, "native work budget");
        if (profile.equals("POLYNOMIAL_HORNER_V1")) verifyHornerPath(response, setup);
    }

    private static void verifyHornerPath(JsonNode response, JsonNode setup) throws Exception {
        String current = response.path("typedInput").asText();
        var codec = new CompiledAstReplayCodec();
        var expression = new ExpressionParser().parseTerm(response.path("input").asText());
        require(codec.encodeExpression(expression).equals(current), "Horner typed input binding");
        JsonNode witness = response.path("witness");
        require(witness.isArray(), "Horner witness shape");
        long replay = 0, theory = 0;
        for (JsonNode edge : witness) {
            JsonNode move = edge.path("move"), checked = edge.path("verification");
            String target = edge.path("target").asText();
            require(edge.path("source").asText().equals(current) && !target.isBlank()
                && move.path("transformation").path("transformedExpression").asText().equals(target), "Horner witness path binding");
            JsonNode binding = move.path("provenance").path("evidence").path("binding");
            if (move.path("ruleId").asText().equals("regelsuche.structural-construction/v1:POLYNOMIAL_HORNER")) {
                require(binding.isObject() && binding.path("sourceExpression").asText().equals(current)
                    && binding.path("transformedExpression").asText().equals(target), "Horner theory binding");
                String data = binding.path("canonicalEvidenceJson").asText();
                require(binding.path("evidenceHash").asText().equals("sha256:" + hash(data.getBytes(StandardCharsets.UTF_8))),
                    "Horner evidence hash");
                long evidenceWork = nonnegative(binding, "canonicalWorkUnits");
                JsonNode proof = JSON.readTree(data);
                require(proof != null && proof.isObject() && evidenceWork > 0 && proof.path("source").asText().equals(current)
                    && proof.path("target").asText().equals(target)
                    && proof.path("procedure").equals(move.path("ruleId"))
                    && nonnegative(proof, "work") == evidenceWork, "Horner evidence body");
                theory = Math.addExact(theory, evidenceWork);
            } else require(binding.isMissingNode(), "undeclared Horner theory step");
            require(checked.path("accepted").isBoolean() && checked.path("accepted").asBoolean()
                && checked.path("receipts").isArray() && !checked.path("receipts").isEmpty(), "Horner checked witness");
            replay = Math.addExact(replay, nonnegative(checked, "work"));
            current = target;
        }
        require(response.path("typedOutput").asText().equals(current)
            && ExpressionFormatter.format(codec.decodeExpression(current)).equals(response.path("output").asText()),
            "Horner typed output binding");
        require(replay == nonnegative(response, "selectedReplayWork"), "Horner final replay ledger");
        require(theory <= nonnegative(setup.path("settings"), "maxTheoryWork"), "Horner theory path budget");
    }

    private static boolean accountingComplete(JsonNode search) {
        JsonNode staged = search.path("stagedIncrementalExecution");
        if (staged.isMissingNode() || staged.isNull()) return true;
        require(staged.isObject() && staged.path("expansions").isArray(), "staged execution ledger");
        boolean complete = true;
        for (JsonNode expansion : staged.path("expansions")) {
            require(expansion.isObject() && expansion.path("lanes").isArray(), "staged expansion ledger");
            for (JsonNode lane : expansion.path("lanes")) {
                require(lane.isObject() && lane.has("cursor"), "staged lane ledger");
                JsonNode cursor = lane.path("cursor");
                if (cursor.isNull()) continue;
                require(cursor.isObject() && cursor.path("accountingComplete").isBoolean(), "staged cursor accounting");
                complete &= cursor.path("accountingComplete").booleanValue();
            }
        }
        return complete;
    }

    private static void verifyProvenance(Path checkout, JsonNode report, byte[] rules, boolean horner) throws Exception {
        JsonNode acquisition = report.path("acquisition");
        long ruleCount = new String(rules, StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).count();
        require(acquisition.path("status").asText().equals("ACQUIRED") && ruleCount > 0
            && acquisition.path("rules").asLong() == ruleCount, "actual acquisition receipt");
        require(acquisition.path("recipe").asText().equals("original::rational_best::best_enumo_recipe")
            && nonnegative(acquisition, "acquisitionNanos") > 0, "original acquisition identity");
        Path artifacts = checkout.resolve(horner ? EnumoPublicComparison.HORNER_ARTIFACTS : "research/benchmarks/enumo-public-v1");
        JsonNode provenance = JSON.readTree(Files.readString(artifacts.resolve("provenance.json")));
        require(report.path("enumoBinarySha256").asText().matches("[a-f0-9]{64}")
            && report.path("enumoBinarySha256").equals(provenance.path("upstream").path("binarySha256")), "original executable binding");
        require(report.path("nativeWorkerClassSha256").asText().matches("[a-f0-9]{64}")
            && report.path("nativeWorkerClassSha256").equals(provenance.path("native").path("workerClassSha256")), "native executable binding");
        require(acquisition.equals(provenance.path("acquisition").path("receipt")), "acquisition provenance binding");
        require(report.path("acquiredRulesSha256").equals(provenance.path("acquisition").path("rulesSha256")),
            "acquired rules provenance binding");
    }

    private static Map<String, JsonNode> responses(byte[] raw) throws Exception {
        require(raw.length <= 20_000_000, "raw evidence size bound");
        var result = new HashMap<String, JsonNode>();
        for (String line : new String(raw, StandardCharsets.UTF_8).lines().toList()) {
            JsonNode value = JSON.readTree(line);
            require(value != null && value.isObject() && value.path("row").isObject(), "raw response shape");
            require(value.path("profile").equals(value.path("row").path("profile")), "raw profile binding");
            require(result.putIfAbsent(key(value.path("row")), value) == null, "duplicate raw response");
        }
        return result;
    }

    private static Map<String, JsonNode> verifySetups(JsonNode setups, JsonNode acquisition, List<String> profiles) throws Exception {
        require(setups.isArray() && setups.size() == 3 * profiles.size(), "complete setup inventory");
        var identities = new HashMap<String, JsonNode>();
        for (JsonNode setup : setups) {
            String profile = setup.path("profile").asText();
            int repetition = setup.path("repetition").asInt();
            JsonNode initialized = setup.path("initialization");
            require(profiles.contains(profile) && repetition >= 1 && repetition <= 3
                && identities.putIfAbsent(profile + ":" + repetition, initialized) == null, "setup identity");
            long elapsed = setup.path("startupAndInitializationNanos").asLong(-1);
            require(elapsed > 0 && elapsed <= 60_000_000_000L, "startup and initialization budget");
            String expected = profile.equals("ENUMO_LEARNED") ? "READY" : "INITIALIZED";
            require(initialized.path("status").asText().equals(expected), "setup outcome");
            if (profile.equals("ENUMO_LEARNED")) require(initialized.path("rules").equals(acquisition.path("rules")), "loaded original rule count");
            else verifyNativeSetup(initialized, profile, elapsed);
        }
        return identities;
    }

    private static void verifyNativeSetup(JsonNode initialized, String profile, long elapsed) throws Exception {
        String expected = EnumoPublicComparison.Profile.valueOf(profile).nativeProfile;
        require(initialized.path("profile").asText().equals(expected), "native setup profile");
        String model = initialized.path("model").asText();
        require(initialized.path("modelHash").asText().equals("sha256:" + hash(model.getBytes(StandardCharsets.UTF_8))), "native setup model hash");
        JsonNode parsed = JSON.readTree(model);
        require(parsed != null && parsed.path("schema").asText().equals("regelsuche.typed-external-frozen-profile/v1")
            && parsed.path("profile").asText().equals(expected), "native frozen model identity");
        var limits = new HashMap<>(Map.of("workBudget", 16384, "primitiveDepth", 9,
            "searchDepth", 9, "maxStates", 256, "complexityDebt", 24));
        if (profile.equals("REGELSUCHE_POLYNOMIAL_HORNER")) {
            limits.put("maxTheoryWork", 16384);
            require(parsed.path("constructionProfile").asText().equals(expected), "native construction profile");
            require(parsed.path("objective").asText().equals("SHARED_EXACT_ARITHMETIC_SURFACE"), "native shared surface objective");
        }
        JsonNode settings = JSON.valueToTree(limits);
        require(settings.equals(initialized.path("settings")) && settings.equals(parsed.path("settings")), "native frozen limits");
        require(nonnegative(initialized, "nativeWallNanos") <= elapsed, "native initialization interval");
        long training = 0;
        for (String component : List.of("formation", "historyMemory", "historySearch", "policyTrials")) {
            training = Math.addExact(training, nonnegative(initialized.path("trainingWorkComponents"), component));
        }
        require(training == nonnegative(initialized, "trainingWork"), "native acquisition ledger");
    }

    private static long nonnegative(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0, "invalid nonnegative " + field);
        return value.longValue();
    }

    private static String key(JsonNode row) {
        return row.path("profile").asText() + ":" + row.path("repetition").asInt() + ":" + row.path("ordinal").asInt();
    }
    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalArgumentException(detail);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] readGzip(Path file) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] bytes = input.readNBytes(20_000_001);
            require(bytes.length <= 20_000_000, "evidence decompression bound");
            return bytes;
        }
    }
}
