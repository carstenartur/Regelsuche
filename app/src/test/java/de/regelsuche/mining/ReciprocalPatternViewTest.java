package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReciprocalPatternViewTest {
    @ParameterizedTest
    @CsvSource(delimiter=';', value={
        "(A*B)^-1*A*C;(A*C)/(A*B)",
        "B^-1*C;C/B",
        "A^-1;1/A",
        "A*B^-1*C^-1;(A/B)/C",
        "(A/B)^-1;1/(A/B)",
        "A*(B/C)^-1;A/(B/C)",
        "(A*B)^-2*C;(A*B)^-2*C",
        "A^N*B;A^N*B",
        "A^(0-N)*B;A^(0-N)*B",
        "f(A*B^-1);f(A/B)",
        "f(A)*B^-1;f(A)/B",
        "A+A^-1;A+1/A"
    })
    void changesOnlyReciprocalSpellingWithoutCancellingOrFlatteningDivision(String source, String expected) {
        var parser = new RulePatternParser();
        assertEquals(parser.parse(expected), ReciprocalPatternView.quotient(parser.parse(source)));
    }
}
