package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramMutationKindTest {
    @Test
    void controlledMutationVocabularyAndOrderRemainStable() {
        assertEquals(
            List.of(
                "WRAP_REPEAT",
                "WRAP_REQUIRE",
                "WRAP_PRIORITY",
                "WRAP_PRUNE",
                "PREPEND_SOURCE",
                "APPEND_SOURCE",
                "SWAP_ADJACENT_CHILDREN",
                "REMOVE_WRAPPER",
                "CHOICE_TO_FIRST_APPLICABLE",
                "FIRST_APPLICABLE_TO_CHOICE"),
            Arrays.stream(EvolutionRewriteProgramMutationKind.values())
                .map(Enum::name)
                .toList());
    }
}
