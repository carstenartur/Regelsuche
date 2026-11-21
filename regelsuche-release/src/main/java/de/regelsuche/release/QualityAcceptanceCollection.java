package de.regelsuche.release;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Collects the original reports and their first-read bytes, including failed native outcomes. */
public final class QualityAcceptanceCollection {
    static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String QUALITY = "build/reports/quality/";
    private static final String HISTORY = QUALITY + "jmh-history/";
    private static final String VISUAL = "app/src/e2eTest/resources/screenshots/";
    private static final String INVENTORY = QUALITY + "supply-chain/";
    private static final String SCAN = INVENTORY + "vulnerability/";
    private static final String SCAN_POLICY = "config/quality/supply-chain-vulnerability-policy.json";
    private final OwnedReleaseEvidenceFiles source;
    private final JsonNode plan;
    private final ObjectNode sections;
    private final ArrayNode problems = JSON.createArrayNode();
    private final Map<String, byte[]> files = new TreeMap<>();

    private QualityAcceptanceCollection(OwnedReleaseEvidenceFiles source) throws IOException {
        this.source = source;
        try (var stream = QualityAcceptanceCollection.class.getResourceAsStream("/quality-collection/retention-map.json")) {
            if (stream == null) throw new IOException("missing quality retention map");
            plan = JSON.readTree(stream);
        }
        sections = plan.required("sections").deepCopy();
        for (var entry : sections.properties()) {
            ObjectNode section = (ObjectNode) entry.getValue();
            section.putArray("outcomes");
            section.putArray("currentValueBindings");
        }
    }

    public static ObjectNode collect(Path root, Path output, ObjectNode checkout) throws IOException {
        require(checkout.required("revision").asText().matches("[0-9a-f]{40}") && checkout.required("tree").asText().matches("[0-9a-f]{40}")
            && checkout.required("trackedChangesPresent").isBoolean(), "invalid checkout identity");
        require(output.startsWith("build/reports/quality") && output.getNameCount() > 3
            && output.equals(output.normalize()), "output must be a fresh child of build/reports/quality");
        try (var reader = new OwnedReleaseEvidenceFiles(root)) {
            var collection = new QualityAcceptanceCollection(reader);
            collection.read();
            return collection.publish(root, output, checkout);
        }
    }

    private void read() {
        for (var entry : sections.properties()) {
            String section = entry.getKey();
            for (var file : entry.getValue().required("policyFiles")) {
                String path = file.textValue();
                if (!path.endsWith(".json")) retain(section, path, null);
                else {
                    var policy = plan.required("policies").required(path);
                    document(section, path, policy.get(0).textValue(), null, null, null, policy.get(1));
                }
            }
            for (var file : entry.getValue().required("supportFiles")) retain(section, file.textValue(), null);
        }
        for (var group : plan.required("reports").properties()) {
            for (var report : group.getValue()) document(group.getKey(), report.get(0).textValue(),
                report.get(1).textValue(), report.get(2).textValue(), report.get(3), null, null);
        }
        special("history", this::history);
        special("visual", this::visual);
        special("http", this::http);
        special("vulnerability", this::vulnerability);
    }

    private interface Checked { void execute() throws Exception; }
    private void special(String section, Checked operation) {
        try { operation.execute(); }
        catch (Exception failure) { problem(section, section + " bindings", failure); }
    }

    private void problem(String section, String path, Exception failure) {
        problems.addObject().put("section", section).put("path", path)
            .put("reason", failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage());
    }

    private byte[] retain(String section, String path, JsonNode hash) {
        try {
            Path relative = Path.of(path);
            path = relative.toString();
            byte[] bytes = files.get(path);
            if (bytes == null) bytes = source.readBytes(relative);
            require(hash == null || sha256(bytes).equals(hash.textValue()), "byte hash binding differs");
            files.put(path, bytes);
            return bytes;
        } catch (IOException | IllegalArgumentException failure) {
            problem(section, path, failure);
            return null;
        }
    }

    private JsonNode document(String section, String path, String schema, String outcome, JsonNode fields,
                              JsonNode hash, JsonNode shape) {
        byte[] bytes = retain(section, path, hash);
        if (bytes == null) return null;
        try {
            var value = JSON.readTree(bytes);
            require(value != null && value.isContainerNode(), "report must be a JSON object or array");
            require(schema == null || schema.equals(value.path("schema").textValue()), "report schema differs: expected " + schema);
            if (shape != null) validateShape(value, shape, path);
            if (outcome != null) {
                String result = value.required(outcome).textValue();
                require(result != null && !result.isEmpty(), "missing native outcome: " + outcome);
                section(section).withArray("outcomes").addObject().put("sourcePath", path).put("field", outcome).put("value", result);
            }
            if (fields != null && !fields.isEmpty()) {
                var projection = JSON.createObjectNode();
                for (var key : fields) projection.set(key.textValue(), value.required(key.textValue()));
                bind(section, path, bytes, projection);
            }
            return value;
        } catch (IOException | IllegalArgumentException failure) {
            problem(section, path, failure);
            return null;
        }
    }

    private static void validateShape(JsonNode value, JsonNode shape, String label) {
        if (shape.isObject()) {
            require(value.isObject(), label + " expects an object");
            for (var entry : shape.properties()) validateShape(value.required(entry.getKey()), entry.getValue(), label + "." + entry.getKey());
        } else if (shape.isArray()) {
            boolean map = shape.get(0).asText().equals("object");
            require((map ? value.isObject() : value.isArray()) && (shape.get(2).asBoolean() || !value.isEmpty()),
                label + " has an invalid inventory");
            if (map) for (var key : value.propertyStream().map(Map.Entry::getKey).toList()) require(!key.isBlank(), label + " has a blank key");
            for (var member : value) validateShape(member, shape.get(1), label);
        } else {
            boolean valid = switch (shape.textValue()) {
                case "string" -> value.isTextual() && !value.textValue().isBlank();
                case "array" -> value.isArray();
                case "boolean" -> value.isBoolean();
                case "integer" -> value.isIntegralNumber();
                case "nullableInteger" -> value.isNull() || value.isIntegralNumber();
                case "number" -> value.isNumber() && (value.isIntegralNumber() || Double.isFinite(value.doubleValue()));
                default -> throw new IllegalArgumentException("unknown retention type " + shape);
            };
            require(valid, label + " has the wrong JSON type");
        }
    }

    private void bind(String section, String path, byte[] bytes, ObjectNode values) {
        section(section).withArray("currentValueBindings").addObject().put("sourcePath", path)
            .put("sha256", sha256(bytes)).set("fields", values);
    }
    private ObjectNode section(String section) { return (ObjectNode) sections.required(section); }
    private JsonNode document(String section, String path) { return document(section, path, null, null, null, null, null); }

    private void bound(String section, String prefix, JsonNode ref) {
        retain(section, prefix + text(ref, "path"), ref.required("sha256"));
    }
    private static String text(JsonNode node, String key) {
        var value = node.required(key);
        require(value.isTextual() && !value.textValue().isEmpty(), "missing text " + key);
        return value.textValue();
    }
    private static List<String> column(JsonNode rows, String field) {
        require(rows.isArray(), "expected row inventory");
        return rows.valueStream().map(row -> text(row, field)).toList();
    }
    private static void sameInventory(Set<String> expected, List<String> actual, String label) {
        require(!expected.isEmpty() && actual.size() == expected.size() && Set.copyOf(actual).equals(expected), label);
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
    private static boolean hasExactCount(JsonNode value, int count) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == count;
    }
    private static String sha256(byte[] data) { return "sha256:" + HexFormat.of().formatHex(digest("SHA-256").digest(data)); }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void history() {
        var policy = document("history", "config/quality/jmh-history-policy.json");
        if (policy != null) for (var reference : policy.required("snapshots")) bound("history", "", reference);
        var history = document("history", HISTORY + "history.json");
        if (history == null) return;
        var contract = document("history", "config/quality/jmh-regression-policy-v2.json");
        if (contract == null) throw new IllegalArgumentException("benchmark policy unavailable");
        var expected = Set.copyOf(column(contract.required("benchmarks"), "benchmark"));
        var rows = history.required("benchmarks");
        sameInventory(expected, column(rows, "benchmark"), "history benchmark inventory differs");
        var charts = column(rows, "chart");
        require(hasExactCount(history.required("benchmarkCount"), expected.size())
            && charts.size() == Set.copyOf(charts).size(), "history chart inventory differs");
        for (String chart : charts) {
            Path child = Path.of(chart);
            require(Path.of("charts").equals(child.getParent()) && chart.endsWith(".svg"), "invalid history chart child");
            retain("history", HISTORY + chart, null);
        }
    }

    private void visual() {
        var policy = document("visual", VISUAL + "visual-regression-policy.json");
        if (policy == null) return;
        for (var entry : policy.required("baselines").properties()) {
            String name = entry.getKey();
            require(Path.of(name).getFileName().toString().equals(name) && name.endsWith(".png"), "invalid visual child");
            String baseline = VISUAL + "baseline/" + name;
            byte[] data = retain("visual", baseline, null);
            if (data != null) {
                var hash = digest("SHA-1");
                hash.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
                if (!HexFormat.of().formatHex(hash.digest(data)).equals(text(entry.getValue(), "gitBlobSha"))
                        || !hasExactCount(entry.getValue().required("byteLength"), data.length))
                    problem("visual", baseline, new IllegalArgumentException("baseline policy binding differs"));
            }
            String stem = "build/reports/visual-regression/" + name.substring(0, name.length() - 4);
            String path = stem + ".comparison.json";
            var receipt = document("visual", path, "regelsuche.visual-comparison/v1", "status",
                fields("changedPixels","totalPixels","diffRatio","channelTolerance","maxDiffRatio","baselineSha256","actualSha256"), null, null);
            retain("visual", stem + ".actual.png", receipt == null ? null : receipt.required("actualSha256"));
            if (receipt == null) continue;
            retain("visual", baseline, receipt.required("baselineSha256"));
            for (String field : List.of("channelTolerance", "maxDiffRatio")) {
                var expected = policy.required("comparison").required(field);
                var actual = receipt.required(field);
                if (!actual.isNumber() || actual.decimalValue().compareTo(expected.decimalValue()) != 0)
                    problem("visual", path, new IllegalArgumentException("comparison policy binding differs"));
            }
            require(receipt.required("changedPixels").isNumber(), "changedPixels must be numeric");
            if (receipt.required("changedPixels").doubleValue() > 0) retain("visual", stem + ".diff.png", null);
        }
    }
    private static ArrayNode fields(String... fields) { return JSON.valueToTree(List.of(fields)); }

    private void http() throws Exception {
        String name = "de.regelsuche.web.WebWorkbenchServerRequestLimitTest";
        String path = "app/build/test-results/test/TEST-" + name + ".xml";
        byte[] bytes = retain("http", path, null);
        if (bytes == null) return;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var suite = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        require("testsuite".equals(suite.getTagName()) && name.equals(suite.getAttribute("name")), "HTTP request-limit suite identity differs");
        var values = JSON.createObjectNode();
        for (String field : List.of("tests", "failures", "errors", "skipped")) {
            int count = Integer.parseInt(suite.getAttribute(field));
            require(count >= 0 && (!field.equals("tests") || count > 0), "invalid HTTP test counts");
            values.put(field, count);
        }
        bind("http", path, bytes, values);
    }

    private JsonNode scanDocument(String path, String schema, JsonNode hash, String shape) {
        return document("vulnerability", path, schema, null, null, hash, plan.required("structures").required(shape));
    }

    private void vulnerability() {
        String section = "vulnerability";
        var latest = scanDocument(SCAN + "latest.json", null, null, "LATEST_SCAN");
        var policy = document(section, SCAN_POLICY);
        if (latest == null || policy == null) return;
        Path directory = Path.of(text(latest, "run"));
        require(Path.of(SCAN + "runs").equals(directory.getParent()) && directory.normalize().equals(directory),
            "latest scanner run is not a direct retained run child");
        String run = directory + "/";
        String evidencePath = run + "vulnerability-evidence.json";
        var evidence = document(section, evidencePath, "regelsuche.supply-chain-vulnerability-evidence/v1", "decision",
            fields("evaluatedAt", "databaseAsOf", "databaseHash", "scannerHash", "findings", "errors", "execution"),
            latest.required("evidenceHash"), null);
        if (evidence == null) return;
        if (!evidence.required("decision").equals(latest.required("decision")))
            problem(section, SCAN + "latest.json", new IllegalArgumentException("latest decision binding differs"));
        scannerAuthorities(policy, evidence, run);
        scannerInputs(evidence, run);
        scannerExecution(evidence, run);
        retain(section, run + "osv-scanner.toml", null);
    }

    private void scannerAuthorities(JsonNode policy, JsonNode evidence, String run) {
        String section = "vulnerability";
        String dbPath = text(policy.required("databaseManifest"), "path");
        String scannerPath = text(policy.required("scannerManifest"), "path");
        for (var member : List.of(List.of("bom.json", "rawBomHash", INVENTORY + "bom.json"),
                List.of("dependency-inventory.json", "inventoryHash", INVENTORY + "dependency-inventory.json"),
                List.of("inventory-evidence-v1.json", "inventoryEvidenceHash", INVENTORY + "supply-chain-evidence.json"),
                List.of("vulnerability-policy.json", "policyHash", SCAN_POLICY),
                List.of("database-manifest.json", "databaseManifestHash", dbPath),
                List.of("scanner-manifest.json", "scannerManifestHash", scannerPath))) {
            var hash = evidence.required(member.get(1));
            retain(section, run + member.get(0), hash);
            retain(section, member.get(2), hash);
        }
        var db = scanDocument(dbPath, "regelsuche.supply-chain-advisory-snapshot/v1",
            policy.required("databaseManifest").required("sha256"), "DATABASE_MANIFEST");
        var scanner = scanDocument(scannerPath, "regelsuche.supply-chain-scanner/v1",
            policy.required("scannerManifest").required("sha256"), "SCANNER_MANIFEST");
        if (db != null && scanner != null) {
            var refs = new ArrayList<JsonNode>();
            for (String field : List.of("archive", "metadata", "upstreamRetention")) refs.add(db.required(field));
            db.required("licenses").forEach(refs::add);
            for (String field : List.of("license", "release", "tag", "checksums", "provenance")) refs.add(scanner.required(field));
            for (var ref : refs) { bound(section, "", ref); bound(section, run + "authority/", ref); }
            for (String path : List.of(SCAN_POLICY, text(policy, "inventoryPolicy"), dbPath, scannerPath)) {
                byte[] bytes = retain(section, path, null);
                if (bytes != null) retain(section, run + "authority/" + path, JSON.getNodeFactory().textNode(sha256(bytes)));
            }
            retain(section, run + "cache/osv-scalibr/Maven/all.zip", db.required("archive").required("sha256"));
            if (!evidence.required("databaseHash").equals(db.required("archive").required("sha256"))
                    || !evidence.required("databaseAsOf").equals(db.required("createdAt"))
                    || !evidence.required("scannerHash").equals(scanner.required("sha256")))
                problem(section, run + "vulnerability-evidence.json", new IllegalArgumentException("scanner/database identity binding differs"));
        }
    }

    private void scannerInputs(JsonNode evidence, String run) {
        String section = "vulnerability";
        var inputs = scanDocument(run + "scanner-inputs.json", null, evidence.required("scannerInputsHash"), "SCANNER_INPUTS");
        if (inputs != null) {
            var inventory = document(section, INVENTORY + "dependency-inventory.json");
            require(inventory != null, "missing resolved inventory");
            var components = inputs.required("components");
            sameInventory(Set.copyOf(column(inventory.required("components"), "purl")), column(components, "purl"),
                "scanner inputs differ from the complete resolved PURL inventory");
            var paths = new HashSet<String>();
            for (var component : components) {
                String path = text(component, "source");
                Path child = Path.of(path);
                require(Path.of("inputs").equals(child.getParent()) && child.equals(child.normalize()) && paths.add(path),
                    "scanner input must be a unique direct input child");
                retain(section, run + path, component.required("sha256"));
            }
        }
    }

    private void scannerExecution(JsonNode evidence, String run) {
        String section = "vulnerability";
        var execution = scanDocument(run + "scanner-execution.json", null, null, "SCANNER_EXECUTION");
        if (execution != null) {
            if (!execution.equals(evidence.required("execution")))
                problem(section, run + "scanner-execution.json", new IllegalArgumentException("execution receipt binding differs"));
            for (String field : List.of("stdout", "stderr")) {
                String name = field.equals("stdout") ? "scanner.stdout.json" : "scanner.stderr.log";
                require(name.equals(text(execution, field + "Path")), "scanner stream path differs");
                retain(section, run + name, execution.required(field + "Hash"));
            }
        }
    }

    private ObjectNode publish(Path root, Path output, ObjectNode checkout) throws IOException {
        var result = JSON.createObjectNode().put("schema", "regelsuche.quality.acceptance-evidence-index/v1");
        result.set("checkout", checkout);
        result.set("claimBoundary", plan.required("claimBoundary"));
        result.put("completeness", problems.isEmpty() ? "COMPLETE" : "INCOMPLETE");
        result.set("sections", sections);
        result.set("problems", problems);
        var entries = result.putArray("files");
        Path parent = root;
        for (Path part : output.getParent()) {
            parent = parent.resolve(part);
            require(!Files.isSymbolicLink(parent), "symbolic output parent is forbidden");
            if (!Files.isDirectory(parent)) Files.createDirectory(parent);
        }
        Path dest = Files.createDirectory(root.resolve(output));
        for (var file : files.entrySet()) {
            String path = file.getKey();
            String retained = "files/" + path;
            entries.addObject().put("sourcePath", path).put("path", retained).put("bytes", file.getValue().length)
                .put("sha256", sha256(file.getValue()));
            Path to = dest.resolve(retained);
            Files.createDirectories(to.getParent());
            Files.write(to, file.getValue(), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        var lines = new ArrayList<>(List.of("# Quality acceptance evidence", "", "Completeness: **" + result.required("completeness").asText()
            + "**", "", "Collection revision: `" + text(checkout, "revision") + "`; tree: `" + text(checkout, "tree") + "`.", "",
            plan.required("claimBoundary").asText(), "", "[Policy, baseline, exception and measurement map](files/docs/quality-acceptance-evidence.md)",
            "", "| Section | Evidence availability | Reported outcomes |", "| --- | --- | --- |"));
        for (var entry : sections.properties()) {
            String id = entry.getKey();
            boolean missing = problems.valueStream().anyMatch(p -> id.equals(p.required("section").textValue()));
            String availability = missing ? "UNAVAILABLE" : "RETAINED";
            section(id).put("availability", availability);
            String outcomes = section(id).required("outcomes").valueStream().map(row ->
                "[" + text(row, "value") + "](files/" + text(row, "sourcePath") + ")").collect(Collectors.joining(", "));
            lines.add("| " + id + " | " + availability + " | " + (outcomes.isEmpty()
                ? "No native outcome field; see current value bindings in index.json" : outcomes) + " |");
        }
        lines.addAll(List.of("", "Exact policies, baselines, exception identities and measurements are retained under `files/`.",
            "`index.json` binds every retained file by SHA-256 and projects native current value fields.", ""));
        for (var problem : problems) lines.add("- " + text(problem, "section") + ": `" + text(problem, "path") + "` — " + text(problem, "reason"));
        Files.writeString(dest.resolve("index.md"), String.join("\n", lines) + "\n");
        Files.writeString(dest.resolve("index.json"), result.toPrettyString() + "\n");
        return result;
    }
}
