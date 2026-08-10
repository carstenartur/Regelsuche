package de.regelsuche.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.radar.AstRuleRadar.CandidateOutcome;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.web.StreamingJsonRequestBody;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleRadarRequestBodyTest {

    @Test
    void decodesNestedTypedContextAndNormalizesCollections() throws Exception {
        RuleRadarRequestBody body = decode("""
            {
              "expression":"  (x + 1)^2  ",
              "candidateId":"  candidate-1  ",
              "targetExpression":"  x*x + 2*x + 1  ",
              "maxDepth":7,
              "maxStates":321,
              "maxMovesPerState":45,
              "context":{
                "knowledgeProfile":"minimal-kernel",
                "enabledPacks":[" pack-b ","pack-a","pack-b",null,""],
                "disabledPacks":["disabled"],
                "includePlugins":false,
                "includeLearnedMacros":false,
                "minMacroProofStatus":"symbolically-verified",
                "searchProfile":"  DIVERSITY_DISCOVERY  ",
                "goalExpression":"  x^2  ",
                "maxCandidatesPerPosition":500,
                "maxCandidatesTotal":5000,
                "assumptions":[" y != 0 ","x > 0","y != 0",null],
                "includeRejectedCandidates":false,
                "selectedCandidateId":" selected ",
                "outcomeByCandidateId":{
                  " candidate-a ":"applied",
                  "candidate-b":"pruned-budget",
                  "ignored":null
                },
                "unknown":{"deep":[1,2,3]}
              },
              "unknownRoot":{"large":[1,2,3]}
            }
            """);

        assertEquals("(x + 1)^2", body.expression());
        assertEquals("candidate-1", body.candidateId());
        assertEquals("x*x + 2*x + 1", body.targetExpression());
        assertEquals(7, body.maxDepth());
        assertEquals(321, body.maxStates());
        assertEquals(45, body.maxMovesPerState());

        AstRuleRadar.Context context = body.context();
        assertEquals(RuleProfile.MINIMAL_KERNEL, context.knowledgeProfile());
        assertEquals(Set.of("pack-a", "pack-b"), context.enabledPacks());
        assertEquals(Set.of("disabled"), context.disabledPacks());
        assertFalse(context.includePlugins());
        assertFalse(context.includeLearnedMacros());
        assertEquals(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            context.minMacroProofStatus()
        );
        assertEquals("DIVERSITY_DISCOVERY", context.searchProfile());
        assertEquals("x^2", context.goalExpression());
        assertEquals(200, context.maxCandidatesPerPosition());
        assertEquals(2_000, context.maxCandidatesTotal());
        assertEquals(List.of("x > 0", "y != 0"), context.assumptions());
        assertFalse(context.includeRejectedCandidates());
        assertEquals("selected", context.selectedCandidateId());
        assertEquals(
            Map.of(
                "candidate-a", CandidateOutcome.APPLIED,
                "candidate-b", CandidateOutcome.PRUNED_BUDGET
            ),
            context.outcomeByCandidateId()
        );
    }

    @Test
    void supportsLegacyTopLevelContextAndEmptyNestedFallback() throws Exception {
        RuleRadarRequestBody topLevel = decode("""
            {
              "expression":"x",
              "includePlugins":false,
              "enabledPacks":["pack"],
              "goalExpression":"goal"
            }
            """);
        assertFalse(topLevel.context().includePlugins());
        assertEquals(Set.of("pack"), topLevel.context().enabledPacks());
        assertEquals("goal", topLevel.context().goalExpression());

        RuleRadarRequestBody emptyNested = decode("""
            {
              "expression":"x",
              "includePlugins":false,
              "context":{}
            }
            """);
        assertFalse(emptyNested.context().includePlugins());
    }

    @Test
    void unknownOnlyNestedContextFallsBackToLegacyTopLevelFields() throws Exception {
        RuleRadarRequestBody body = decode("""
            {
              "expression":"x",
              "includePlugins":false,
              "enabledPacks":["legacy-pack"],
              "goalExpression":"legacy-goal",
              "context":{"unknown":{"deep":[1,2,3]}}
            }
            """);

        assertFalse(body.context().includePlugins());
        assertEquals(Set.of("legacy-pack"), body.context().enabledPacks());
        assertEquals("legacy-goal", body.context().goalExpression());
    }

    @Test
    void defaultsRemainStableForMissingAndNullValues() throws Exception {
        RuleRadarRequestBody body = decode("""
            {
              "expression":null,
              "candidateId":null,
              "targetExpression":null,
              "maxDepth":null,
              "maxStates":null,
              "maxMovesPerState":null,
              "context":null
            }
            """);

        assertEquals("", body.expression());
        assertEquals("", body.candidateId());
        assertEquals("", body.targetExpression());
        assertEquals(4, body.maxDepth());
        assertEquals(120, body.maxStates());
        assertEquals(60, body.maxMovesPerState());
        assertEquals(AstRuleRadar.Context.defaults(), body.context());
    }

    @Test
    void rejectsUnknownEnumsAndWrongScalarTypes() {
        assertThrows(
            IllegalArgumentException.class,
            () -> decode("""
                {"expression":"x","context":{"knowledgeProfile":"unknown"}}
                """)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> decode("""
                {"expression":"x","context":{"outcomeByCandidateId":{"id":"unknown"}}}
                """)
        );
        assertThrows(
            StreamingJsonRequestBody.MalformedJsonRequestException.class,
            () -> decode("""
                {"expression":"x","maxDepth":"7"}
                """)
        );
    }

    @Test
    void nestedContextTakesPrecedenceWhenItContainsFields() throws Exception {
        RuleRadarRequestBody body = decode("""
            {
              "expression":"x",
              "includePlugins":false,
              "context":{"includeLearnedMacros":false}
            }
            """);

        assertTrue(body.context().includePlugins());
        assertFalse(body.context().includeLearnedMacros());
    }

    private RuleRadarRequestBody decode(String json) throws Exception {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(16 * 1024);
        return RuleRadarRequestBody.read(
            reader,
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
        );
    }
}
