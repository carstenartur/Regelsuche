package de.regelsuche.quality.supplychain;

import static de.regelsuche.quality.supplychain.SupplyChainJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Complete, lossless component identity projection of the already verified v1 inventory. */
record MavenScanInputs(Map<String, Component> components, ObjectNode bindings) {
    record Component(String purl, String group, String name, String version, String source, String sha256) {}

    static MavenScanInputs prepare(Path root, Path inventoryDirectory, Path output,
            VulnerabilityPolicy policy, ObjectNode evidence) throws IOException {
        Path bomPath = file(root, inventoryDirectory.resolve("bom.json"));
        Path inventoryPath = file(root, inventoryDirectory.resolve("dependency-inventory.json"));
        Path legacyPath = file(root, inventoryDirectory.resolve("supply-chain-evidence.json"));
        JsonNode bom = read(bomPath);
        JsonNode inventory = read(inventoryPath);
        JsonNode legacy = read(legacyPath);
        require(text(bom, "bomFormat").equals("CycloneDX") && text(bom, "specVersion").equals("1.6"),
            "unsupported SBOM format");
        require(!bom.has("serialNumber") && integer(bom, "version") >= 1, "invalid deterministic SBOM");
        require(text(inventory, "schema").equals("regelsuche.dependency-inventory/v1")
            && text(inventory, "format").equals("CycloneDX") && text(inventory, "specVersion").equals("1.6"),
            "unsupported inventory schema");
        require(text(legacy, "schema").equals("regelsuche.supply-chain-evidence/v1")
            && text(legacy, "vulnerabilityScanStatus").equals("NOT_EVALUATED")
            && text(legacy, "vulnerabilityDatabaseStatus").equals("NOT_BOUND")
            && text(legacy, "vulnerabilityPolicyStatus").equals("DEFERRED_UNTIL_CONTENT_ADDRESSED_DATABASE"),
            "v1 evidence meaning differs");
        require(text(legacy, "inventoryHash").equals(hash(inventoryPath)), "v1 inventory hash differs");
        require(text(legacy, "policyHash").equals(policy.inventoryPolicyHash()), "v1 inventory policy hash differs");
        ArrayNode componentArray = array(bom, "components");
        require(!componentArray.isEmpty() && componentArray.size() <= 10_000, "empty or oversized component set");
        Map<String, JsonNode> normalized = new TreeMap<>();
        for (JsonNode component : array(inventory, "components")) {
            require(normalized.put(text(component, "purl"), component) == null, "duplicate inventory PURL");
        }
        require(integer(legacy, "componentCount") == componentArray.size()
            && normalized.size() == componentArray.size(), "v1 component accounting differs");
        Map<String, Component> result = new TreeMap<>();
        ObjectNode bindings = JSON.createObjectNode();
        bindings.put("schema", "regelsuche.supply-chain-scanner-inputs/v1");
        bindings.put("rawBomHash", hash(bomPath));
        ArrayNode entries = bindings.putArray("components");
        Map<String, JsonNode> sorted = new TreeMap<>();
        for (JsonNode component : componentArray) {
            fields(component, Set.of("type", "bom-ref", "group", "name", "version", "purl"));
            String purl = text(component, "purl");
            String group = text(component, "group");
            String name = text(component, "name");
            String version = text(component, "version");
            require(group.matches("[A-Za-z0-9_.-]+") && name.matches("[A-Za-z0-9_.-]+")
                && version.length() <= 512 && version.chars().noneMatch(Character::isISOControl),
                "unsupported Maven coordinates: " + purl);
            String expected = "pkg:maven/" + encode(group) + "/" + encode(name) + "@" + encode(version);
            require(purl.equals(expected) && text(component, "bom-ref").equals(purl)
                && text(component, "type").equals("library"), "component PURL binding differs: " + purl);
            JsonNode previous = normalized.get(purl);
            require(previous != null, "component is absent from v1 inventory: " + purl);
            for (String field : Set.of("purl", "group", "name", "version", "type")) {
                require(component.get(field).equals(previous.get(field)), "v1 component binding differs: " + purl);
            }
            require(text(previous, "bomRef").equals(purl), "v1 bomRef differs");
            require(sorted.put(purl, component) == null, "duplicate SBOM PURL: " + purl);
        }
        for (var entry : sorted.entrySet()) {
            String purl = entry.getKey();
            JsonNode component = entry.getValue();
            String source = "inputs/" + hash(purl.getBytes(StandardCharsets.UTF_8)).substring(7) + ".cdx.json";
            ObjectNode single = JSON.createObjectNode();
            single.put("bomFormat", "CycloneDX").put("specVersion", "1.6").put("version", 1);
            single.putArray("components").add(component);
            byte[] bytes = canonical(single);
            write(root, output.resolve(source), bytes);
            Component bound = new Component(purl, text(component, "group"), text(component, "name"),
                text(component, "version"), source, hash(bytes));
            result.put(purl, bound);
            entries.addObject().put("purl", purl).put("source", source).put("sha256", bound.sha256());
        }
        write(root, output.resolve("scanner-inputs.json"), canonical(bindings));
        evidence.put("rawBomHash", hash(bomPath)).put("inventoryHash", hash(inventoryPath))
            .put("inventoryEvidenceHash", hash(legacyPath)).put("scannerInputsHash", hash(canonical(bindings)));
        // Retain the exact upstream inputs alongside the decision, including the unchanged v1 status.
        write(root, output.resolve("bom.json"), Files.readAllBytes(bomPath));
        write(root, output.resolve("dependency-inventory.json"), Files.readAllBytes(inventoryPath));
        write(root, output.resolve("inventory-evidence-v1.json"), Files.readAllBytes(legacyPath));
        return new MavenScanInputs(Map.copyOf(result), bindings);
    }

    void verifyFiles(Path root, Path output) throws IOException {
        require(hash(file(root, output.resolve("scanner-inputs.json"))).equals(hash(canonical(bindings))),
            "scanner input binding manifest changed");
        for (Component component : components.values()) {
            require(hash(file(root, output.resolve(component.source()))).equals(component.sha256()),
                "scanner input changed: " + component.purl());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
