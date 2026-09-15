package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.policy.SearchPolicyModel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyScoreRevisionBindingTest {
    @Test void portablePolicyIdentityIncludesTheProducingScoreContract() {
        assertTrue(SearchPolicyModel.FEATURE_SCHEMA.contains(ScoreRevision.CURRENT));
        var current = model(SearchPolicyModel.FEATURE_SCHEMA);
        assertTrue(SearchPolicyModel.load(current.toPortableText()).compatible());
        assertFalse(model("regelsuche.search-policy-features/v2").compatible());
        assertFalse(model(SearchPolicyModel.FEATURE_SCHEMA.replace(ScoreRevision.CURRENT, "other-score/v1")).compatible());
    }

    private static SearchPolicyModel model(String features) {
        return new SearchPolicyModel("test", "dataset", features, "rules",
            SearchPolicyModel.Mode.FREQUENCY, 1, Map.of());
    }
}
