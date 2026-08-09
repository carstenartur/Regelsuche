package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.mining.HypothesisCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelationalJsonTest {

    @Test
    void arraysRoundTripEscapedValuesAndTreatMissingInputAsEmpty() {
        List<String> values = List.of(
            "plain",
            "quote\"value",
            "back\\slash",
            "line\nfeed",
            "tab\tvalue");

        String json = RelationalJson.array(values);

        assertEquals(values, RelationalJson.arrayValues(json));
        assertEquals("[]", RelationalJson.array(null));
        assertEquals(List.of(), RelationalJson.arrayValues(null));
        assertEquals(List.of(), RelationalJson.arrayValues("  "));
    }

    @Test
    void arraysRejectNullElementsInsteadOfWritingUnreadableJson() {
        List<String> values = new ArrayList<>();
        values.add("readable");
        values.add(null);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> RelationalJson.array(values));

        assertEquals(
            "relational JSON string arrays must not contain null elements",
            failure.getMessage());
    }

    @Test
    void facetsAreCanonicalSortedAndLastDuplicateWins() {
        List<SearchFacet> facets = List.of(
            new SearchFacet(" z ", "first-z"),
            new SearchFacet("a", "first-a"),
            new SearchFacet("z", "replacement-z"));

        String json = RelationalJson.object(facets);

        assertEquals("{\"a\":\"first-a\",\"z\":\"replacement-z\"}", json);
        assertEquals(
            List.of(
                new SearchFacet("a", "first-a"),
                new SearchFacet("z", "replacement-z")),
            RelationalJson.facets(json));
        assertEquals("{}", RelationalJson.object(null));
        assertEquals(List.of(), RelationalJson.facets(null));
        assertEquals(List.of(), RelationalJson.facets("{}"));
    }

    @Test
    void expressionPairsRoundTripNullEndpointsAsExplicitEmptyStrings() {
        List<HypothesisCandidate.ExpressionPair> pairs = List.of(
            new HypothesisCandidate.ExpressionPair("x+0", "x"),
            new HypothesisCandidate.ExpressionPair(null, "right"),
            new HypothesisCandidate.ExpressionPair("left", null));

        String json = RelationalJson.expressionPairs(pairs);

        assertEquals(
            List.of(
                new HypothesisCandidate.ExpressionPair("x+0", "x"),
                new HypothesisCandidate.ExpressionPair("", "right"),
                new HypothesisCandidate.ExpressionPair("left", "")),
            RelationalJson.expressionPairsValues(json));
        assertEquals("[]", RelationalJson.expressionPairs(null));
        assertEquals(List.of(), RelationalJson.expressionPairsValues(null));
    }

    @Test
    void placeholderEntriesAreKeySortedAndRoundTripNestedStringArrays() {
        Map<String, List<String>> placeholders = new LinkedHashMap<>();
        placeholders.put("?Z", List.of("z", "z+1"));
        placeholders.put("?A", List.of("a", "a\\b"));

        String json = RelationalJson.placeholderEntries(placeholders);

        assertEquals(
            "[{\"key\":\"?A\",\"values\":[\"a\",\"a\\\\b\"]},"
                + "{\"key\":\"?Z\",\"values\":[\"z\",\"z+1\"]}]",
            json);
        assertEquals(Map.copyOf(placeholders), RelationalJson.placeholderEntriesValues(json));
        assertEquals("[]", RelationalJson.placeholderEntries(null));
        assertEquals(Map.of(), RelationalJson.placeholderEntriesValues(null));
        assertEquals(
            Map.of("kept", List.of("x")),
            RelationalJson.placeholderEntriesValues(
                "[{\"key\":\"\",\"values\":[\"ignored\"]},"
                    + "{\"key\":\"kept\",\"values\":[\"x\"]}]"));
    }

    @Test
    void joinHelpersRemainNullSafeAndQuoteRoundTripsControlCharacters() {
        assertEquals("", RelationalJson.join(null));
        assertEquals("a b", RelationalJson.join(List.of("a", "b")));
        assertEquals("", RelationalJson.joinFacets(null));
        assertEquals(
            "domain:algebra status:confirmed",
            RelationalJson.joinFacets(List.of(
                new SearchFacet("domain", "algebra"),
                new SearchFacet("status", "confirmed"))));

        String value = "a\\b\"c\n\r\t" + (char) 1;
        String quoted = RelationalJson.quote(value);
        assertEquals(value, RelationalJson.arrayValues("[" + quoted + "]").getFirst());
        assertEquals("null", RelationalJson.quote(null));
    }
}
