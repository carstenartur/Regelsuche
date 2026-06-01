package de.regelsuche.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PatternRewriteRule;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KnowledgePackCiGateTest {
    @Test
    void externalRulesSatisfyRegistrationGates() throws URISyntaxException {
        Path packDirectory = Path.of(Thread.currentThread()
                .getContextClassLoader()
                .getResource("rules/packs")
                .toURI());
        List<KnowledgePack> packs = new KnowledgePackLoader().loadAll(packDirectory);
        List<PatternRewriteRule> rules = packs.stream().flatMap(pack -> pack.rules().stream()).toList();
        List<PatternRewriteRule> registrable = rules.stream().filter(rule -> rule.descriptor().eligibleForRegistration()).toList();

        assertTrue(registrable.size() >= 30, "expected at least 30 external registrable rules");
        for (PatternRewriteRule rule : rules) {
            RuleDescriptor descriptor = rule.descriptor();
            assertTrue(!descriptor.eligibleForRegistration() || descriptor.status() == RuleStatus.VALIDATED || descriptor.status() == RuleStatus.REVIEWED);
            assertFalse(descriptor.originProject().isBlank(), rule.id() + " missing provenance origin");
            assertFalse(descriptor.packId().isBlank(), rule.id() + " missing pack id");
            assertFalse(descriptor.sourceReference().isBlank(), rule.id() + " missing source reference");
            assertFalse(descriptor.license().isBlank(), rule.id() + " missing license");
            if (descriptor.status() == RuleStatus.VALIDATED) {
                assertFalse(descriptor.validationExamples().isEmpty(), rule.id() + " missing validation examples");
                assertTrue(descriptor.counterExamples().isEmpty(), rule.id() + " has counterexamples");
            }
        }
    }
}
