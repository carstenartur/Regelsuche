package de.regelsuche.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.OrderedReductionSearch;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded source-only adapter for the frozen original-compiler comparison. */
public final class ExternalPolyhedralComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PROTOCOL_HASH = "d87c7ada9ccf9e5c2b1b270ba6a4c1f987feb45c8572b5540ca30d00e24cd46c";
    private static final Pattern PREFIX = Pattern.compile(
        "affine([A-Za-z][A-Za-z0-9_]*)\\[N\\]->\\{:0<N\\}"
        + "inputsX:\\{\\[i\\]:0<=i<=N\\}outputsY:\\{\\[i\\]:0<=i<=N\\}"
        + "letY\\[i\\]=reduce\\(\\+,\\[([a-z][a-z0-9]*(?:,[a-z][a-z0-9]*)*)\\],"
        + "\\{:(.*?)\\}:(.*?)\\);\\.");
    private static final Pattern SOURCE_REFERENCE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\[[^\\]]*\\])?");
    private ExternalPolyhedralComparison() {}
    record Imported(String name, int arity, Expr term, OrderedReduction source) {}

    static Imported parse(String source) {
        if (source.length() > 4096) throw new IllegalArgumentException("SOURCE_SIZE_LIMIT");
        var match = PREFIX.matcher(source.replaceAll("//[^\\n]*", "").replaceAll("\\s+", ""));
        if (!match.matches()) throw new IllegalArgumentException("ALPHA_PREFIX_FRAGMENT_UNSUPPORTED");
        var indices = List.of(match.group(2).split(","));
        if (indices.size() > OrderedReduction.MAX_ARITY || indices.contains("i")
                || new HashSet<>(indices).size() != indices.size()
                || !match.group(3).equals("0<=" + String.join("<", indices) + "<=i")) {
            throw new IllegalArgumentException("ORDERED_PREFIX_DOMAIN_UNSUPPORTED");
        }
        // Alpha leaves an empty reduction undefined. The native mathematical
        // empty-sum identity must not silently define the first k-1 outputs.
        if (indices.size() > 1) throw new IllegalArgumentException("ALPHA_EMPTY_PREFIX_REDUCTION_UNSUPPORTED");
        String expression = match.group(4);
        var allowedReferences = new HashSet<String>();
        for (String index : indices) allowedReferences.add("X[" + index + "]");
        // Validate source references before introducing internal names: a bare
        // x0 in Alpha is undeclared, even though x0 is used after translation.
        for (var references = SOURCE_REFERENCE.matcher(expression); references.find();)
            if (!allowedReferences.contains(references.group()))
                throw new IllegalArgumentException("ALPHA_SOURCE_REFERENCE_UNSUPPORTED");
        var scope = new SymbolScope();
        var slots = new ArrayList<List<SymbolId>>();
        for (int i = 0; i < indices.size(); i++) {
            expression = expression.replace("X[" + indices.get(i) + "]", "x" + i);
            slots.add(List.of(scope.declare("x" + i)));
        }
        var term = new ExpressionParser().parseTerm(expression);
        bound(term, indices.size()); // Reject unsupported arithmetic before proposing any plan.
        var restrictions = new ArrayList<OrderedReduction.Restriction>();
        for (int i = 1; i < indices.size(); i++) restrictions.add(OrderedReduction.All.ROWS);
        return new Imported(match.group(1), indices.size(), term,
            new OrderedReduction(slots, restrictions, SymbolicExpression.parse(expression, scope).expression()));
    }

    /** Absolute bounds for the source arithmetic, without a rewrite or native plan. */
    private static BigInteger bound(Expr expression, int arity) {
        BigInteger result;
        if (expression instanceof NumberExpr number && number.value().denominator().equals(BigInteger.ONE)) {
            result = number.value().numerator().abs();
        } else if (expression instanceof VariableExpr variable
                && variable.name().matches("x[0-7]") && Integer.parseInt(variable.name().substring(1)) < arity) {
            result = BigInteger.valueOf(9);
        } else if (expression instanceof BinaryExpr binary) {
            var left = bound(binary.left(), arity);
            result = switch (binary.operator()) {
                case ADD, SUB -> left.add(bound(binary.right(), arity));
                case MUL -> left.multiply(bound(binary.right(), arity));
                case POW -> left.pow(exponent(binary.right()));
                default -> throw new IllegalArgumentException("INTEGER_POLYNOMIAL_FRAGMENT_UNSUPPORTED");
            };
        } else throw new IllegalArgumentException("INTEGER_POLYNOMIAL_FRAGMENT_UNSUPPORTED");
        if (result.bitLength() > 60) throw new IllegalArgumentException("SIGNED_LONG_SOURCE_BOUND");
        return result;
    }

    private static int exponent(Expr expression) {
        if (expression instanceof NumberExpr number && number.value().denominator().equals(BigInteger.ONE)
                && number.value().numerator().signum() >= 0 && number.value().numerator().compareTo(BigInteger.valueOf(8)) <= 0) {
            return number.value().numerator().intValueExact();
        }
        throw new IllegalArgumentException("EXPONENT_UNSUPPORTED");
    }

    /** Exhaustive tuple evaluation shares only the source AST, not the native algebra or schedule. */
    static List<BigInteger> oracle(Imported input, int count, int seed) {
        if (count < 2 || count > 20 || seed < 0 || seed > 2) throw new IllegalArgumentException("FIXTURE_OR_PARAMETER_DOMAIN_LIMIT");
        if (bound(input.term(), input.arity()).multiply(BigInteger.valueOf(count).pow(input.arity())).bitLength() > 60)
            throw new IllegalArgumentException("SIGNED_LONG_SUM_BOUND");
        var output = new ArrayList<BigInteger>();
        for (int prefix = 1; prefix <= count; prefix++)
            output.add(enumerate(input.term(), new BigInteger[input.arity()], 0, 0, prefix, seed));
        return List.copyOf(output);
    }

    private static BigInteger enumerate(Expr term, BigInteger[] tuple, int slot, int start, int end, int seed) {
        if (slot == tuple.length) return interpret(term, tuple);
        BigInteger total = BigInteger.ZERO;
        for (int i = start; i < end; i++) {
            tuple[slot] = value(i, seed);
            total = total.add(enumerate(term, tuple, slot + 1, i + 1, end, seed));
        }
        return total;
    }

    private static BigInteger interpret(Expr expression, BigInteger[] tuple) {
        if (expression instanceof NumberExpr number) return number.value().numerator();
        if (expression instanceof VariableExpr variable) return tuple[Integer.parseInt(variable.name().substring(1))];
        var binary = (BinaryExpr) expression;
        var left = interpret(binary.left(), tuple);
        return switch (binary.operator()) {
            case ADD -> left.add(interpret(binary.right(), tuple));
            case SUB -> left.subtract(interpret(binary.right(), tuple));
            case MUL -> left.multiply(interpret(binary.right(), tuple));
            case POW -> left.pow(exponent(binary.right()));
            default -> throw new IllegalArgumentException("UNSUPPORTED_ORACLE_OPERATOR");
        };
    }

    private static BigInteger value(int index, int seed) {
        return BigInteger.valueOf((index * 17L + seed * 7L) % 19L - 9L);
    }

    static JsonNode protocol(Path checkout) throws Exception {
        var bytes = Files.readAllBytes(checkout.resolve("config/benchmarks/external-polyhedral-v1.json"));
        if (!hash(bytes).equals(PROTOCOL_HASH)) throw new IllegalArgumentException("PROTOCOL_CHANGED");
        return JSON.readTree(bytes);
    }

    /** The subprocess accepts source and budgets, never expected outputs or case labels. */
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--native")) {
            var request = JSON.readTree(Path.of(args[2]).toFile());
            write(Path.of(args[3]), nativeRun(Files.readString(Path.of(args[1])), request));
        } else if (args.length == 3 && args[0].equals("--verify")) {
            audit(Path.of(args[1]), Path.of(args[2]));
        } else if (args.length == 3) {
            run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        } else throw new IllegalArgumentException("checkout upstream output, or --native source request output");
    }

    private static Map<String, Object> nativeRun(String source, JsonNode request) {
        long started = System.nanoTime();
        var response = new LinkedHashMap<String, Object>();
        Imported input;
        try { input = parse(source); }
        catch (IllegalArgumentException unsupported) {
            response.put("status", "UNSUPPORTED"); response.put("reason", unsupported.getMessage());
            response.put("nativeWallNanos", System.nanoTime() - started); return response;
        }
        var budget = request.path("limits");
        long maximum = budget.path("nativeWork").longValue();
        var limits = new IterationLimits(maximum, budget.path("nativeTerms").intValue(),
            budget.path("synthesisSeconds").longValue() * 1000);
        try {
            var search = OrderedReductionSearch.search(input.source(), limits, budget.path("maxStates").intValue(), () -> false);
            long work = search.metrics().totalWork();
            response.put("searchWork", work); response.put("searchReason", search.reason());
            if (search.verified().isEmpty()) { response.put("status", "NO_VERIFIED_PLAN"); return response; }
            var plan = search.verified().orElseThrow().plan();
            var replay = OrderedReductionSearch.verify(input.source(), plan, false,
                limits.withMaximumWork(maximum - work), () -> false);
            work += replay.work(); response.put("replayWork", replay.work());
            if (replay.verified().isEmpty()) throw new IllegalStateException("PLAN_REPLAY_FAILED");
            response.put("planTerms", plan.terms());
            var outputs = new ArrayList<Map<String, Object>>();
            long beforeEvaluation = System.nanoTime(), evaluationWork = 0;
            for (var count : request.path("rowCounts")) for (var seed : request.path("seeds")) {
                var rows = new ArrayList<List<BigInteger>>();
                for (int i = 0; i < count.intValue(); i++) rows.add(List.of(value(i, seed.intValue())));
                var evaluated = replay.verified().orElseThrow().evaluatePrefixes(rows,
                    limits.withMaximumWork(maximum - work), () -> false);
                work += evaluated.work(); evaluationWork += evaluated.work();
                outputs.add(Map.of("rows", count.intValue(), "seed", seed.intValue(),
                    "values", evaluated.sums().stream().map(BigInteger::toString).toList()));
            }
            response.put("evaluationNanos", System.nanoTime() - beforeEvaluation);
            response.put("evaluationWork", evaluationWork); response.put("totalNativeWork", work);
            response.put("outputs", outputs); response.put("status", "CANDIDATE");
        } catch (RuntimeException failure) {
            response.put("status", "ERROR"); response.put("reason", failure.toString());
        } finally { response.put("nativeWallNanos", System.nanoTime() - started); }
        return response;
    }

    public static Map<String, Object> run(Path checkout, Path upstream, Path output) throws Exception {
        checkout = checkout.toAbsolutePath(); upstream = upstream.toAbsolutePath(); output = output.toAbsolutePath();
        var panel = protocol(checkout);
        if (Files.exists(output)) throw new IllegalArgumentException("OUTPUT_ALREADY_EXISTS");
        Files.createDirectories(output);
        long setupStarted = System.nanoTime();
        verifyUpstream(panel, upstream, output);
        long setupNanos = System.nanoTime() - setupStarted;
        var request = JSON.createObjectNode();
        for (String key : List.of("limits", "rowCounts", "seeds")) request.set(key, panel.get(key));
        write(output.resolve("request.json"), request);
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", panel.path("schema").asText()); report.put("protocolSha256", PROTOCOL_HASH);
        report.put("upstreamRevision", panel.path("revision").asText()); report.put("java", System.getProperty("java.runtime.version"));
        report.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch") + " " + System.getProperty("os.version"));
        report.put("processors", Runtime.getRuntime().availableProcessors()); report.put("limits", request.path("limits"));
        report.put("sourceVerificationSetupNanos", setupNanos);
        report.put("acquisitionCost", "NOT_MEASURED: pinned original prebuilt artifact; checkout/download excluded from query phases.");
        report.put("memoryBoundary", "512 MiB Java heap per subprocess; native/JNI/process RSS is not a hard-capped comparable work measure.");
        report.put("nativeRevision", commandText(List.of("git", "rev-parse", "HEAD"), checkout, output.resolve("native-revision")));
        report.put("gcc", commandText(List.of("gcc", "--version"), checkout, output.resolve("gcc-version")));
        report.put("timingInterpretation", "One cold run; phase diagnostics only, no relative speed ranking or common instruction-count claim.");
        var rows = new ArrayList<Map<String, Object>>();
        for (var entry : panel.path("cases")) {
            Path directory = output.resolve(entry.path("id").asText()); Files.createDirectories(directory);
            Path source = directory.resolve("input.alpha"); Files.writeString(source, entry.path("source").asText());
            if (!hash(Files.readAllBytes(source)).equals(entry.path("sha256").asText())) throw new IllegalStateException("SOURCE_HASH");
            Optional<Imported> imported;
            try { imported = Optional.of(parse(Files.readString(source))); }
            catch (IllegalArgumentException unsupported) { imported = Optional.empty(); }
            long judgeStarted = System.nanoTime();
            var expected = new ArrayList<Map<String, Object>>();
            if (imported.isPresent()) for (var count : panel.path("rowCounts")) for (var seed : panel.path("seeds"))
                expected.add(Map.of("rows", count.intValue(), "seed", seed.intValue(), "values",
                    oracle(imported.orElseThrow(), count.intValue(), seed.intValue()).stream().map(BigInteger::toString).toList()));
            long oracleNanos = System.nanoTime() - judgeStarted;
            write(directory.resolve("oracle.json"), expected);
            rows.add(nativeRow(entry, panel, checkout, output, directory, source, expected, oracleNanos));
            rows.add(externalRow(entry, panel, upstream, directory, source, imported, expected, oracleNanos));
            report.put("rows", rows); write(output.resolve("results.json"), report);
        }
        if (rows.size() != panel.path("cases").size() * panel.path("arms").size()) throw new IllegalStateException("INCOMPLETE_MATRIX");
        var hashes = new LinkedHashMap<String, String>();
        try (var files = Files.walk(output)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList())
                hashes.put(output.relativize(path).toString(), hash(Files.readAllBytes(path)));
        }
        write(output.resolve("manifest.json"), hashes);
        audit(checkout, output);
        return report;
    }

    /** Rechecks retained identities and the full matrix without invoking either optimizer. */
    public static void audit(Path checkout, Path output) throws Exception {
        output = output.toAbsolutePath().normalize();
        var panel = protocol(checkout);
        var manifest = JSON.readTree(output.resolve("manifest.json").toFile());
        auditManifest(output, manifest);
        var report = JSON.readTree(output.resolve("results.json").toFile());
        if (!report.path("protocolSha256").asText().equals(PROTOCOL_HASH)) throw new IllegalArgumentException("REPORT_PROTOCOL");
        var expectedKeys = new HashSet<String>(); var observedKeys = new HashSet<String>();
        var cases = retainedCases(output, panel, expectedKeys);
        for (var row : report.path("rows")) auditRow(output, panel, manifest, cases, observedKeys, row);
        if (!expectedKeys.equals(observedKeys)) throw new IllegalArgumentException("INCOMPLETE_RETAINED_MATRIX");
    }

    private static void auditManifest(Path output, JsonNode manifest) throws Exception {
        for (var fields = manifest.fields(); fields.hasNext();) {
            var entry = fields.next();
            Path file = output.resolve(entry.getKey()).normalize();
            if (!file.startsWith(output.normalize()) || !hash(Files.readAllBytes(file)).equals(entry.getValue().asText()))
                throw new IllegalArgumentException("EVIDENCE_FILE_CHANGED");
        }
    }

    private static Map<String, JsonNode> retainedCases(Path output, JsonNode panel,
            HashSet<String> expectedKeys) throws Exception {
        var cases = new LinkedHashMap<String, JsonNode>();
        for (var entry : panel.path("cases")) {
            cases.put(entry.path("id").asText(), entry);
            if (!hash(Files.readAllBytes(output.resolve(entry.path("id").asText()).resolve("input.alpha")))
                    .equals(entry.path("sha256").asText())) throw new IllegalArgumentException("RETAINED_SOURCE_CHANGED");
            for (var arm : panel.path("arms")) expectedKeys.add(entry.path("id").asText() + "@" + arm.asText());
        }
        return cases;
    }

    private static void auditRow(Path output, JsonNode panel, JsonNode manifest,
            Map<String, JsonNode> cases, HashSet<String> observedKeys, JsonNode row) throws Exception {
        String id = row.path("case").asText(), arm = row.path("arm").asText();
        if (!observedKeys.add(id + "@" + arm) || !cases.containsKey(id)
                || !cases.get(id).path("sha256").asText().equals(row.path("sourceSha256").asText()))
            throw new IllegalArgumentException("ROW_SOURCE_OR_DUPLICATE");
        boolean nativeArm = arm.equals("REGELSUCHE_ORDERED_PREFIX");
        JsonNode nativeResponse = null;
        if (nativeArm && row.has("response")) {
            nativeResponse = JSON.readTree(requiredEvidence(output, manifest, id + "/native.json").toFile());
            if (!row.path("response").equals(nativeResponse))
                throw new IllegalArgumentException("RETAINED_NATIVE_RESPONSE_MISMATCH");
        }
        if (!row.path("judgment").asText().equals("VERIFIED_FINITE_OUTPUTS")) return;
        JsonNode actual = nativeArm ? retainedNativeOutputs(panel, row, nativeResponse)
            : retainedOriginalOutputs(output, panel, manifest, row, id);
        var input = parse(cases.get(id).path("source").asText());
        var expected = new ArrayList<Map<String, Object>>();
        for (var count : panel.path("rowCounts")) for (var seed : panel.path("seeds"))
            expected.add(Map.of("rows", count.intValue(), "seed", seed.intValue(), "values",
                oracle(input, count.intValue(), seed.intValue()).stream().map(BigInteger::toString).toList()));
        if (!JSON.valueToTree(expected).equals(actual)) throw new IllegalArgumentException("RETAINED_OUTPUT_MISMATCH");
    }

    private static JsonNode retainedNativeOutputs(JsonNode panel, JsonNode row, JsonNode nativeResponse) {
        auditProcess(row.path("process"), panel.at("/limits/synthesisSeconds").intValue(), true);
        var work = row.path("response").path("totalNativeWork");
        if (!work.isIntegralNumber() || work.longValue() < 0 || work.longValue() > panel.at("/limits/nativeWork").longValue())
            throw new IllegalArgumentException("RETAINED_WORK_LIMIT");
        if (nativeResponse == null) throw new IllegalArgumentException("MISSING_NATIVE_RESPONSE");
        return nativeResponse.path("outputs");
    }

    private static JsonNode retainedOriginalOutputs(Path output, JsonNode panel, JsonNode manifest,
            JsonNode row, String id) throws Exception {
        auditProcess(row.path("synthesisProcess"), panel.at("/limits/synthesisSeconds").intValue(), false);
        auditProcess(row.path("compileProcess"), panel.at("/limits/compileSeconds").intValue(), true);
        if (row.path("executions").size() != panel.path("rowCounts").size() * panel.path("seeds").size())
            throw new IllegalArgumentException("RETAINED_EXECUTION_MATRIX");
        for (var execution : row.path("executions"))
            auditProcess(execution, panel.at("/limits/executionSeconds").intValue(), true);
        var retained = new ArrayList<Map<String, Object>>();
        for (var count : panel.path("rowCounts")) for (var seed : panel.path("seeds")) {
            String relative = id + "/run-" + count.intValue() + "-" + seed.intValue() + ".stdout";
            String text = Files.readString(requiredEvidence(output, manifest, relative)).trim();
            var values = text.isEmpty() ? List.<String>of() : List.of(text.split("\\s+"));
            retained.add(Map.of("rows", count.intValue(), "seed", seed.intValue(), "values", values));
        }
        JsonNode actual = JSON.valueToTree(retained);
        if (!actual.equals(row.path("outputs"))) throw new IllegalArgumentException("RETAINED_STDOUT_MISMATCH");
        return actual;
    }

    private static Path requiredEvidence(Path output, JsonNode manifest, String relative) {
        if (!manifest.path(relative).isTextual()) throw new IllegalArgumentException("MISSING_EVIDENCE_MANIFEST_ENTRY");
        return output.resolve(relative);
    }

    private static void auditProcess(JsonNode process, int seconds, boolean zeroExit) {
        if (!process.path("exit").isIntegralNumber() || !process.path("timedOut").isBoolean()
                || !process.path("wallNanos").isIntegralNumber() || process.path("timedOut").booleanValue()
                || process.path("wallNanos").longValue() < 0
                || process.path("wallNanos").longValue() > TimeUnit.SECONDS.toNanos(seconds)
                || (zeroExit && process.path("exit").intValue() != 0))
            throw new IllegalArgumentException("RETAINED_PROCESS_LIMIT");
    }

    private static Map<String, Object> nativeRow(JsonNode entry, JsonNode panel, Path checkout, Path output,
            Path directory, Path source, List<Map<String, Object>> expected, long oracleNanos) throws Exception {
        var row = row(entry, "REGELSUCHE_ORDERED_PREFIX", oracleNanos);
        var command = List.of(java(), "-Xmx" + panel.at("/limits/javaHeapMiB").intValue() + "m", "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ExternalPolyhedralComparison.class.getName(), "--native", source.toString(),
            output.resolve("request.json").toString(), directory.resolve("native.json").toString());
        var process = process(command, checkout, Map.of(), directory.resolve("native"), panel.at("/limits/synthesisSeconds").intValue());
        row.put("process", process); row.put("command", command);
        if (process.exceeded(panel.at("/limits/synthesisSeconds").intValue())) row.put("judgment", "TIMEOUT");
        else if (process.exit() != 0 || !Files.exists(directory.resolve("native.json"))) row.put("judgment", "PROCESS_FAILURE");
        else {
            var response = JSON.readTree(directory.resolve("native.json").toFile()); row.put("response", response);
            row.put("judgment", response.path("status").asText().equals("CANDIDATE")
                ? JSON.valueToTree(expected).equals(response.path("outputs")) ? "VERIFIED_FINITE_OUTPUTS" : "INVALID_OUTPUT"
                : response.path("status").asText());
        }
        return row;
    }

    private static Map<String, Object> externalRow(JsonNode entry, JsonNode panel, Path upstream, Path directory,
            Path source, Optional<Imported> imported, List<Map<String, Object>> expected, long oracleNanos) throws Exception {
        var row = row(entry, "ORIGINAL_ALPHA_COMPILER", oracleNanos);
        var command = new ArrayList<String>(); command.add(upstream.resolve("acc").toString());
        panel.path("originalOptions").forEach(option -> command.add(option.asText()));
        command.add("-o"); command.add(directory.resolve("generated").toString()); command.add(source.toString());
        var environment = Map.of("JAVA_TOOL_OPTIONS", "-Xmx" + panel.at("/limits/javaHeapMiB").intValue() + "m",
            "PATH", Path.of(java()).getParent() + ":" + System.getenv("PATH"));
        var generated = process(command, upstream, environment, directory.resolve("original"), panel.at("/limits/synthesisSeconds").intValue());
        row.put("synthesisProcess", generated); row.put("command", command);
        row.put("synthesisStatus", generated.exceeded(panel.at("/limits/synthesisSeconds").intValue()) ? "TIMEOUT"
            : generated.exit() == 0 ? "COMPLETED" : "EXIT_NONZERO");
        var generatedFiles = new ArrayList<String>();
        if (Files.exists(directory.resolve("generated"))) try (var paths = Files.walk(directory.resolve("generated"))) {
            generatedFiles.addAll(paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".c")
                || path.toString().endsWith(".alpha")).map(directory::relativize).map(Path::toString).sorted().toList());
        }
        row.put("generatedSources", generatedFiles);
        if (imported.isEmpty()) { row.put("judgment", "OUTSIDE_COMMON_FRAGMENT"); return row; }
        if (generated.exceeded(panel.at("/limits/synthesisSeconds").intValue())) { row.put("judgment", "TIMEOUT"); return row; }
        if (generated.exit() == 125) { row.put("judgment", "OUTPUT_LIMIT"); return row; }
        String name = imported.orElseThrow().name(); Path base = directory.resolve("generated").resolve(name);
        Path simplified = base.resolve("simplifications/v0/" + name + ".c");
        Path selected = Files.exists(simplified) ? simplified : base.resolve(name + ".c");
        row.put("candidateKind", !Files.exists(selected) ? "NONE" : Files.exists(simplified) ? "SIMPLIFIED" : "BASELINE_FALLBACK");
        if (!Files.exists(selected)) { row.put("judgment", "NO_GENERATED_CANDIDATE"); return row; }
        row.put("candidateSha256", hash(Files.readAllBytes(selected)));
        Path driver = directory.resolve("driver.c"), binary = directory.resolve("candidate");
        Files.writeString(driver, driver(name));
        var compile = new ArrayList<String>(); compile.add("gcc"); panel.path("compilerOptions").forEach(o -> compile.add(o.asText()));
        Collections.addAll(compile, selected.toString(), driver.toString(), "-lm", "-o", binary.toString());
        var compiled = process(compile, directory, Map.of(), directory.resolve("compile"), panel.at("/limits/compileSeconds").intValue());
        row.put("compileProcess", compiled); row.put("compileCommand", compile);
        if (compiled.exceeded(panel.at("/limits/compileSeconds").intValue())) { row.put("judgment", "COMPILATION_TIMEOUT"); return row; }
        if (compiled.exit() != 0) { row.put("judgment", "COMPILATION_FAILED"); return row; }
        var actual = new ArrayList<Map<String, Object>>(); var executions = new ArrayList<Execution>();
        for (var count : panel.path("rowCounts")) for (var seed : panel.path("seeds")) {
            Path log = directory.resolve("run-" + count.intValue() + "-" + seed.intValue());
            var executed = process(List.of(binary.toString(), count.asText(), seed.asText()), directory, Map.of(), log,
                panel.at("/limits/executionSeconds").intValue()); executions.add(executed);
            if (executed.exit() != 0 || executed.exceeded(panel.at("/limits/executionSeconds").intValue())) {
                row.put("judgment", "EXECUTION_FAILED"); row.put("executions", executions); return row;
            }
            String text = Files.readString(Path.of(log + ".stdout")).trim();
            var values = text.isEmpty() ? List.<String>of() : List.of(text.split("\\s+"));
            actual.add(Map.of("rows", count.intValue(), "seed", seed.intValue(), "values", values));
        }
        row.put("executions", executions); row.put("outputs", actual);
        row.put("judgment", expected.equals(actual) ? "VERIFIED_FINITE_OUTPUTS" : "INVALID_OUTPUT");
        return row;
    }

    private static String driver(String name) {
        return "#include <stdio.h>\n#include <stdlib.h>\nvoid " + name + "(long,long*,long*);\n"
            + "int main(int argc,char**argv){if(argc!=3)return 2;long n=strtol(argv[1],0,10),s=strtol(argv[2],0,10);"
            + "if(n<2||n>20||s<0||s>2)return 3;if(sizeof(long)!=8)return 4;long x[20],y[20]={0};"
            + "for(long i=0;i<n;i++)x[i]=(i*17+s*7)%19-9;" + name + "(n-1,x,y);"
            + "for(long i=0;i<n;i++)printf(\"%ld\\n\",y[i]);return 0;}\n";
    }

    private static Map<String, Object> row(JsonNode entry, String arm, long oracleNanos) {
        var row = new LinkedHashMap<String, Object>();
        row.put("case", entry.path("id").asText()); row.put("origin", entry.path("origin").asText());
        row.put("sourceSha256", entry.path("sha256").asText()); row.put("arm", arm); row.put("oracleNanos", oracleNanos);
        return row;
    }

    private static void verifyUpstream(JsonNode panel, Path upstream, Path output) throws Exception {
        if (!commandText(List.of("git", "rev-parse", "HEAD"), upstream, output.resolve("upstream-revision"))
                .equals(panel.path("revision").asText())) throw new IllegalArgumentException("UPSTREAM_REVISION");
        commandText(List.of("git", "diff", "--exit-code", "HEAD", "--", "acc", "lib", "inputs"), upstream,
            output.resolve("upstream-clean"));
        for (var fields = panel.path("toolHashes").fields(); fields.hasNext();) {
            var entry = fields.next();
            if (!hash(Files.readAllBytes(upstream.resolve("lib").resolve(entry.getKey()))).equals(entry.getValue().asText()))
                throw new IllegalArgumentException("ORIGINAL_JAR_CHANGED");
        }
        var inventory = new HashSet<String>();
        for (var entry : panel.path("cases")) if (!entry.path("path").isNull()) {
            String path = entry.path("path").asText(); inventory.add(path);
            if (!hash(Files.readAllBytes(upstream.resolve(path))).equals(entry.path("sha256").asText()))
                throw new IllegalArgumentException("UPSTREAM_SOURCE_CHANGED");
        }
        try (var files = Files.list(upstream.resolve("inputs"))) {
            var observed = files.filter(p -> p.toString().endsWith(".alpha")).map(p -> "inputs/" + p.getFileName()).toList();
            if (!inventory.equals(new HashSet<>(observed))) throw new IllegalArgumentException("UPSTREAM_INVENTORY_CHANGED");
        }
    }

    public record Execution(int exit, boolean timedOut, long wallNanos) {
        boolean exceeded(int seconds) { return timedOut || wallNanos > TimeUnit.SECONDS.toNanos(seconds); }
    }

    private static Execution process(List<String> command, Path directory, Map<String, String> environment,
            Path log, int seconds) throws Exception {
        var wrapped = new ArrayList<>(List.of("timeout", "--kill-after=2s", seconds + "s")); wrapped.addAll(command);
        var builder = new ProcessBuilder(wrapped).directory(directory.toFile()); builder.environment().putAll(environment);
        builder.redirectOutput(Path.of(log + ".stdout").toFile()); builder.redirectError(Path.of(log + ".stderr").toFile());
        long started = System.nanoTime(); var process = builder.start();
        if (!process.waitFor(seconds + 4L, TimeUnit.SECONDS)) {
            var descendants = process.descendants().toList(); process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly); process.waitFor();
        }
        int exit = process.exitValue();
        if (Files.size(Path.of(log + ".stdout")) > 1_048_576 || Files.size(Path.of(log + ".stderr")) > 1_048_576)
            return new Execution(125, false, System.nanoTime() - started);
        return new Execution(exit, exit == 124 || exit == 137, System.nanoTime() - started);
    }

    private static String commandText(List<String> command, Path directory, Path log) throws Exception {
        var result = process(command, directory, Map.of(), log, 10);
        if (result.exit() != 0) throw new IllegalStateException("COMMAND_FAILED " + command);
        return Files.readString(Path.of(log + ".stdout")).trim();
    }

    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static void write(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
    static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
