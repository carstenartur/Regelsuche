package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModPowFrozenTransferTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void trainsFromPrimitiveSearchAndRetainsActualEvidence() throws Exception {
        var model = ModPowFrozenTransfer.train(corpus("TRAIN", false, false));
        assertEquals(2, model.path("witnesses").size());
        assertEquals(0, model.path("shuffledWitnessCount").asInt(-1));
        assertEquals("function", model.path("sourceTemplate").path("kind").asText());
        assertFalse(model.path("sourceTemplate").toString().contains("aa"));
        assertFalse(model.path("targetTemplate").toString().contains("cc"));
        for (var witness : model.path("witnesses")) {
            assertEquals(2, witness.path("primitiveGenerated").asInt());
            assertTrue(witness.path("sourceDag").asLong() > witness.path("selectedDag").asLong());
            assertTrue(witness.path("assumptions").size() >= 7);
        }
    }

    @Test void frozenArtifactTransfersWithoutRetrainingAndPreservesExtraOutput() throws Exception {
        Path model = model(); byte[] before = Files.readAllBytes(model);
        var result = ModPowFrozenTransfer.evaluate(model, hash(before), corpus("TEST", true, false));
        assertEquals(2, result.path("positiveTransfers").asInt(-1));
        assertEquals("GREEN", result.path("verdict").asText());
        for (var row : result.path("cases")) {
            assertTrue(row.path("primitiveOptimumParity").asBoolean());
            assertEquals(1, row.path("learnedApproved").asInt());
            assertTrue(row.path("untouchedOutputsPreserved").asBoolean());
        }
        assertArrayEquals(before, Files.readAllBytes(model));
    }

    @Test void wrongHashRejectsBeforeOpeningAnyTestFile() throws Exception {
        Path model = model();
        assertThrows(IllegalArgumentException.class, () -> ModPowFrozenTransfer.evaluate(
            model, "0".repeat(64), dir.resolve("TEST_MUST_NOT_BE_OPENED.json")));
    }

    @Test void syntaxMatchCannotAuthorizeMissingConcretePremises() throws Exception {
        Path model = model();
        var result = ModPowFrozenTransfer.evaluate(model, hash(Files.readAllBytes(model)), corpus("TEST", true, true));
        assertEquals("RED", result.path("verdict").asText());
        for (var row : result.path("cases")) {
            assertEquals(1, row.path("learnedProposed").asInt());
            assertEquals(0, row.path("learnedApproved").asInt());
        }
    }

    @Test void hiddenTargetsAreRejectedByTheSourceOnlyCorpusReader() throws Exception {
        Path train = corpus("TRAIN", false, false);
        ObjectNode root = (ObjectNode) JSON.readTree(train.toFile());
        ((ObjectNode) root.path("cases").get(0)).put("target", "forbidden");
        Files.writeString(train, root.toString());
        assertThrows(IllegalArgumentException.class, () -> ModPowFrozenTransfer.train(train));
    }

    @Test void trainCommandCannotOverwriteAnEarlierArtifact() throws Exception {
        Path destination = dir.resolve("frozen.json"); Files.writeString(destination, "DO_NOT_REPLACE");
        Path train = corpus("TRAIN", false, false);
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () ->
            ModPowFrozenTransfer.main(new String[]{"train", train.toString(), destination.toString()}));
        assertEquals("DO_NOT_REPLACE", Files.readString(destination));
    }

    private Path model() throws Exception {
        Path model = dir.resolve("model.json");
        Files.writeString(model, ModPowFrozenTransfer.train(corpus("TRAIN", false, false)).toString());
        return model;
    }

    private Path corpus(String split, boolean extra, boolean missing) throws Exception {
        ObjectNode root = JSON.createObjectNode().put("schema", "regelsuche.modpow-transfer-corpus/v1").put("split", split);
        var cases = root.putArray("cases");
        for (String prefix : new String[]{"aa", "cc"}) {
            String a=prefix+"a", q=prefix+"q", e=prefix+"e", n=prefix+"n", r=prefix+"r";
            String full="modpow("+a+","+q+"*"+e+","+n+")", kept="modpow("+a+","+e+","+n+")";
            var row=cases.addObject().put("id",prefix).put("source", extra ? "program("+kept+",17,"+full+")" : "program("+full+","+kept+")");
            row.putObject("roles").put("a",a).put("q",q).put("e",e).put("n",n).put("r",r);
            row.put("qBits",8).put("eBits",5).put("rBits",5).put("positive",true);
            var assumptions=row.putArray("assumptions");
            if (!missing) for (String value : new String[]{a+" integer",q+" integer",q+" >= 0",e+" integer",e+" >= 0",n+" integer",n+" > 0",r+" integer",r+" >= 0"}) assumptions.add(value);
        }
        Path file=dir.resolve(split+".json"); Files.writeString(file,root.toString(),StandardCharsets.UTF_8);return file;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
