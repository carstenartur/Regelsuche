package de.regelsuche.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScientificSeedCorporaTest {

    @Test
    void curatedCorpusCoversScientificDiscoveryDomainsDeterministically() {
        List<SeedExpression> seeds = ScientificSeedCorpora.curated();

        assertTrue(seeds.stream().anyMatch(seed -> seed.category().equals("binomial")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.category().equals("geometric-series")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.category().equals("factorization")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.category().equals("trigonometric")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.category().equals("matrix")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.category().equals("rational")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.source().equals("DLMF")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.source().equals("OEIS")));

        assertEquals(seeds, ScientificSeedCorpora.curated(),
            "curated corpus must be reproducible across repeated calls");
        assertEquals(seeds.size(), seeds.stream().map(SeedExpression::stableKey).distinct().count());
    }

    @Test
    void loadsJsonAndYamlLocalCatalogs() throws Exception {
        Path dir = Files.createTempDirectory("scientific-seeds-");
        Path json = dir.resolve("catalog.json");
        Path yaml = dir.resolve("catalog.yaml");

        Files.writeString(json, """
            [
              {
                "id": "json-1",
                "expression": "x + 0",
                "source": "local-json",
                "category": "identity",
                "tags": ["simple"]
              }
            ]
            """);
        Files.writeString(yaml, """
            - id: yaml-1
              expression: "x * 1"
              source: local-yaml
              category: identity
              tags: [simple]
            """);

        List<SeedExpression> seeds = ScientificSeedCorpora.fromCatalogs(List.of(yaml, json));

        assertEquals(2, seeds.size());
        assertTrue(seeds.stream().anyMatch(seed -> seed.id().equals("json-1") && seed.source().equals("local-json")));
        assertTrue(seeds.stream().anyMatch(seed -> seed.id().equals("yaml-1") && seed.source().equals("local-yaml")));
    }
}
