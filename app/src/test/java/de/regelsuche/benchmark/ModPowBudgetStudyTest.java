package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModPowBudgetStudyTest {
    static final Path DOCS = Path.of("..", "docs", "research");
    static final Path STUDY = DOCS.resolve("modpow-budget-v3");
    @TempDir Path tmp;

    @Test
    void regeneratesTheFrozenReceiptAndAllFiniteBudgetOutcomes() throws Exception {
        Path model = tmp.resolve("model.json"), receipt = tmp.resolve("receipt.json"), report = tmp.resolve("report.json");
        String protocol = STUDY.resolve("protocol.json").toString();
        ModPowBudgetStudy.main(new String[]{"acquire", protocol, DOCS.resolve("modpow-transfer-v1/train.json").toString(),
            model.toString(), receipt.toString()});
        assertArrayEquals(Files.readAllBytes(STUDY.resolve("acquisition.json")), Files.readAllBytes(receipt));
        ModPowBudgetStudy.main(new String[]{"evaluate", protocol, model.toString(), receipt.toString(), report.toString()});
        assertArrayEquals(Files.readAllBytes(STUDY.resolve("result.json")), Files.readAllBytes(report));
        var actual = ModPowTransferFiles.JSON.readTree(Files.readAllBytes(report));
        assertEquals(12, actual.required("cases").size());
        assertEquals(232, actual.required("primitiveStreamWork").intValue());
        assertEquals(716, actual.required("learnedStreamWorkIncludingAcquisition").intValue());
        for (var row : actual.required("cases")) {
            if (row.required("id").asText().endsWith("missing-premise")) {
                assertEquals(0, row.at("/learned/ledger/approved").intValue());
                assertEquals(0, row.at("/primitive/ledger/approved").intValue());
                assertTrue(row.at("/learned/ledger/replayed").intValue() > 0);
            }
        }
        Files.writeString(receipt, "{}");
        assertThrows(IllegalArgumentException.class, () -> ModPowBudgetStudy.main(new String[]{"evaluate",
            protocol, model.toString(), receipt.toString(), tmp.resolve("forged.json").toString()}));
        assertFalse(Files.exists(tmp.resolve("forged.json")));
    }
}
