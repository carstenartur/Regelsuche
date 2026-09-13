package de.regelsuche.discovery.representation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

final class ReferenceIndependentValidationFixtures {
    static final String FREEZE_HASH =
        "sha256:b4a8fd1dbc70a7fc5f59df61e9c6871f1be65c47df5441b017be7804e187090a";
    static final String HISTORICAL_QUALIFICATION_HASH =
        "sha256:7317ba66e37e532ec945364efd4f1ee61b89cab7b39ffff32d6751a69bc274c7";
    static final String REVISION = "3f437b044dc9e33c8d18cb52710ecceedbe6d200";
    static final String PLAN = read("plan");
    static final String FREEZE = read("candidate-freeze");

    private ReferenceIndependentValidationFixtures() {
    }

    static String read(String suffix) {
        String path = "/de/regelsuche/discovery/representation/"
            + "reference-independent-v1/target-free-held-out-"
            + suffix + ".json.gz";
        try (var input = new GZIPInputStream(
                ReferenceIndependentValidationFixtures.class
                    .getResourceAsStream(path))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
