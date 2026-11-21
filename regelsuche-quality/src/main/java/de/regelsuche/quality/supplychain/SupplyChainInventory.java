package de.regelsuche.quality.supplychain;

import static de.regelsuche.quality.supplychain.SupplyChainJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** V1 inventory evidence describes resolution. Only the independently executed scanner produces a vulnerability decision. */
public final class SupplyChainInventory {
    static final String DEFERRED = "DEFERRED_UNTIL_CONTENT_ADDRESSED_DATABASE";

    private SupplyChainInventory() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("expected repository root, raw bom.json, and report directory");
        generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    public static void generate(Path root, Path raw, Path directory) throws IOException {
        Path out = checked(root, directory);
        var policy = read(file(root, Path.of("config/quality/supply-chain-policy.json")));
        validatePolicy(policy);
        var bom = read(file(root, raw));
        require("CycloneDX".equals(text(bom, "bomFormat")) && "1.6".equals(text(bom, "specVersion"))
            && !bom.has("serialNumber") && integer(bom, "version") >= 1, "unsupported deterministic CycloneDX 1.6 BOM");
        var inventory = JSON.createObjectNode().put("schema", "regelsuche.dependency-inventory/v1")
            .put("format", "CycloneDX").put("specVersion", "1.6");
        List<ObjectNode> components = new ArrayList<>();
        Set<String> identities = new HashSet<>(), references = new HashSet<>();
        for (JsonNode item : optionalArray(bom, "components")) {
            ObjectNode component = normalizeComponent(item);
            String ref = text(component, "bomRef"), purl = component.required("purl").textValue();
            require(identities.add(purl.isEmpty() ? ref : purl) && references.add(ref), "duplicate component identity or reference: " + ref);
            components.add(component);
        }
        components.sort(Comparator.comparing((ObjectNode node) -> node.required("purl").textValue()).thenComparing(node -> text(node,"bomRef")));
        inventory.set("components", JSON.valueToTree(components));
        if (bom.path("metadata").path("component").isObject()) {
            ObjectNode app = normalizeComponent(bom.required("metadata").required("component"));
            references.add(text(app, "bomRef"));
            inventory.set("rootComponent", app);
        } else inventory.putNull("rootComponent");

        var edges = new TreeMap<String, Set<String>>();
        for (JsonNode item : optionalArray(bom, "dependencies")) {
            String ref = text(item, "ref");
            Set<String> targets = new TreeSet<>();
            for (JsonNode target : optionalArray(item, "dependsOn")) {
                require(target.isTextual() && targets.add(target.textValue()) && references.contains(target.textValue()),
                    "unknown or duplicate dependency edge at " + ref);
            }
            require(references.contains(ref) && edges.putIfAbsent(ref, targets) == null, "unknown or duplicate dependency node " + ref);
        }
        var deps = inventory.putArray("dependencies");
        edges.forEach((ref, targets) -> deps.addObject().put("ref", ref).set("dependsOn", JSON.valueToTree(targets)));
        int edgeCount = edges.values().stream().mapToInt(Set::size).sum();
        byte[] bytes = canonical(inventory);
        var evidence = JSON.createObjectNode().put("schema","regelsuche.supply-chain-evidence/v1")
            .put("policyHash", hash(canonical(policy))).put("inventoryHash", hash(bytes)).put("componentCount", components.size())
            .put("dependencyNodeCount", edges.size()).put("dependencyEdgeCount", edgeCount)
            .put("rawBomTimestampPresent", !bom.path("metadata").path("timestamp").asText("").isEmpty())
            .put("rawBomTimestampSemantic", false).put("vulnerabilityDatabaseStatus","NOT_BOUND")
            .put("vulnerabilityScanStatus","NOT_EVALUATED").put("vulnerabilityPolicyStatus", DEFERRED)
            .put("claimBoundary", text(policy,"claimBoundary"));
        String markdown = """
            # Supply-chain evidence

            - Components: **%d**
            - Dependency graph nodes: **%d**
            - Dependency graph edges: **%d**
            - Inventory hash: `%s`
            - Policy hash: `%s`
            - Vulnerability database: **NOT BOUND**
            - Vulnerability scan: **NOT EVALUATED**

            > This tranche establishes deterministic resolved-dependency inventory only. It does not claim that dependencies are vulnerability-free.
            """.formatted(components.size(), edges.size(), edgeCount, text(evidence,"inventoryHash"), text(evidence,"policyHash"));
        write(root, out.resolve("dependency-inventory.json"), bytes);
        write(root, out.resolve("supply-chain-evidence.json"), canonical(evidence));
        write(root, out.resolve("supply-chain-evidence.md"), markdown.getBytes(StandardCharsets.UTF_8));
    }

    private static Iterable<JsonNode> optionalArray(JsonNode node, String field) {
        return node.has(field) ? array(node, field) : List.of();
    }

    private static ObjectNode normalizeComponent(JsonNode item) {
        var result = JSON.createObjectNode().put("bomRef", text(item,"bom-ref")).put("type", text(item,"type"))
            .put("name", text(item,"name")).put("version", text(item,"version"));
        for (String field : List.of("group","purl","scope")) {
            JsonNode value = item.path(field);
            require(value.isTextual() || value.isNull() || value.isMissingNode(), "invalid component " + field);
            result.put(field, value.asText(""));
        }
        var licenses = new ArrayList<ObjectNode>();
        for (JsonNode entry : optionalArray(item,"licenses")) {
            JsonNode value = entry.has("license") ? entry.get("license") : entry.path("expression");
            ObjectNode license = JSON.createObjectNode();
            if (value.isTextual()) license.put("expression", value.textValue());
            else {
                require(value.isObject(),"missing component license");
                for (String field : List.of("id","name","url")) if (value.hasNonNull(field)) license.put(field, text(value, field));
                require(!license.isEmpty(),"missing component license identity");
            }
            licenses.add(license);
        }
        licenses.sort(Comparator.comparing(ObjectNode::toString));
        result.set("licenses", JSON.valueToTree(licenses));
        var hashes = new ArrayList<ObjectNode>();
        for (JsonNode entry : optionalArray(item,"hashes")) {
            hashes.add(JSON.createObjectNode().put("algorithm", text(entry,"alg")).put("content", text(entry,"content").toLowerCase(Locale.ROOT)));
        }
        hashes.sort(Comparator.comparing((ObjectNode node) -> text(node,"algorithm")).thenComparing(node -> text(node,"content")));
        result.set("hashes", JSON.valueToTree(hashes));
        return result;
    }

    private static void validatePolicy(JsonNode policy) {
        require("regelsuche.supply-chain-policy/v1".equals(text(policy,"schema")), "unsupported inventory policy");
        var input = policy.required("inventory");
        require("CycloneDX".equals(text(input,"format")) && "1.6".equals(text(input,"specVersion"))
            && input.path("includeBomSerialNumber").isBoolean() && !input.path("includeBomSerialNumber").booleanValue()
            && input.path("includeBuildSystem").isBoolean() && !input.path("includeBuildSystem").booleanValue()
            && "EXCLUDED_FROM_SEMANTIC_IDENTITY".equals(text(input,"rawTimestampTreatment")), "unsupported deterministic inventory policy");
        var vuln = policy.required("vulnerabilityPolicy");
        var threshold = vuln.required("failOnCvssAtOrAbove");
        require(DEFERRED.equals(text(vuln,"status")) && threshold.isNumber() && threshold.doubleValue() >= 0 && threshold.doubleValue() <= 10
            && "FAIL_CLOSED".equals(text(vuln,"unknownSeverity")) && "FAIL_CLOSED".equals(text(vuln,"scannerFailure")),
            "inventory cannot promote the independent vulnerability decision");
        var required = array(vuln,"requiredDatabaseProperties");
        require(!required.isEmpty() && required.valueStream().allMatch(JsonNode::isTextual),"invalid required database properties");
    }
}
