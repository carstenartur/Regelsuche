package de.regelsuche.quality.supplychain;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.quality.supplychain.SupplyChainJson.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupplyChainInventoryTest {
    @TempDir Path root;

    private Path raw() throws Exception {
        Path policy = Path.of("config/quality/supply-chain-policy.json");
        Files.createDirectories(root.resolve(policy).getParent());
        Files.copy(Path.of(System.getProperty("regelsuche.repositoryRoot")).resolve(policy), root.resolve(policy));
        Path raw = root.resolve("accepted-bom.json");
        try (var resource = getClass().getResourceAsStream("/supply-chain/accepted-bom-44804422507.json.gz");
                var input = new GZIPInputStream(resource)) { Files.copy(input, raw); }
        assertEquals("sha256:f5aa38e021142de9db447e0468272c58631309f0a2b7c40e5372f1eb7548af32", hash(raw));
        return raw;
    }

    @Test void reproducesTheActualHostedInventoryAndAll148ComponentsByteForByte() throws Exception {
        Path bom = raw(), out = root.resolve("native");
        SupplyChainInventory.generate(root, bom, out);
        assertEquals("sha256:7a7becc21337d02aded69b8b5469c18a877c084ea72a97ed1741279bfd3f65fc", hash(out.resolve("dependency-inventory.json")));
        assertEquals("sha256:bb3c2e780776d76577d7b4964c8db11e71b19af63b5bed2985e5d7160f8392f7", hash(out.resolve("supply-chain-evidence.json")));
        ObjectNode model = read(bom);
        ((ObjectNode)model.get("metadata")).put("timestamp", "2026-11-20T00:00:00Z");
        write(root, bom, canonical(model));
        SupplyChainInventory.generate(root, bom, out);
        assertEquals("sha256:7a7becc21337d02aded69b8b5469c18a877c084ea72a97ed1741279bfd3f65fc", hash(out.resolve("dependency-inventory.json")));
        assertTrue(read(out.resolve("supply-chain-evidence.json")).required("rawBomTimestampPresent").booleanValue());
        assertEquals("NOT_EVALUATED", read(out.resolve("supply-chain-evidence.json")).required("vulnerabilityScanStatus").textValue());
    }

    @Test void rejectsSerialNumbersUnknownEdgesDuplicateIdentitiesAndPolicyPromotion() throws Exception {
        Path bom = raw(), out = root.resolve("native");
        ObjectNode model = read(bom);
        write(root, bom, canonical(model.deepCopy().put("serialNumber","unstable")));
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, out));
        var missing = model.deepCopy();
        ((ObjectNode)missing.required("dependencies").get(0)).putArray("dependsOn").add("missing");
        write(root, bom, canonical(missing));
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, out));
        var dup = model.deepCopy();
        dup.withArray("components").add(dup.required("components").get(0).deepCopy());
        write(root, bom, canonical(dup));
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, out));
        write(root, bom, canonical(model));
        Path policy = root.resolve("config/quality/supply-chain-policy.json");
        ObjectNode cfg = read(policy);
        ((ObjectNode)cfg.required("vulnerabilityPolicy")).put("status","PASSED");
        write(root, policy, canonical(cfg));
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, out));
    }

    @Test void preservesLicenseAndHashOrderAndRejectsMalformedEntries() throws Exception {
        Path bom = raw();
        Path output = root.resolve("normalized");
        ObjectNode model = read(bom);
        ObjectNode first = (ObjectNode) model.required("components").get(0);
        first.putArray("licenses").addObject().putObject("license").put("id", "Apache-2.0");
        first.withArray("licenses").addObject().put("expression", "MIT OR GPL-2.0");
        first.putArray("hashes").addObject().put("alg", "SHA-256").put("content", "ABCD");
        write(root, bom, canonical(model));
        SupplyChainInventory.generate(root, bom, output);
        var normalized = read(output.resolve("dependency-inventory.json")).required("components").get(0);
        assertEquals("abcd", normalized.required("hashes").get(0).required("content").textValue());
        assertEquals("MIT OR GPL-2.0", normalized.required("licenses").get(0).required("expression").textValue());
        first.withArray("licenses").addObject().putObject("license");
        write(root, bom, canonical(model));
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, output));
    }

    @Test void refusesLinkedAndEscapingInputsAndOutputFiles() throws Exception {
        Path bom = raw(), out = Files.createDirectory(root.resolve("native"));
        Path sentinel = Files.writeString(root.resolve("sentinel"),"private data");
        Files.createSymbolicLink(out.resolve("dependency-inventory.json"), sentinel);
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, out));
        assertEquals("private data", Files.readString(sentinel));
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, bom, root.resolve("../other")));
        Path linked = Files.createSymbolicLink(root.resolve("linked.json"), bom);
        assertThrows(Exception.class, () -> SupplyChainInventory.generate(root, linked, out));
    }
}
