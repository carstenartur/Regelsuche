package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.release.AutonomousDiscoveryComparisonSvg.Comparison;
import de.regelsuche.release.AutonomousDiscoveryComparisonSvg.Outcome;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class AutonomousDiscoveryComparisonSvgTest {
    private static Comparison example() {
        return new Comparison("held-out-case", "(20 + 2) * f + 20 * f",
            "(2 * 20 + 2) * f", "(A + 2)*x + A*x", "(2*A + 2)*x",
            new Outcome(false, -1, 80), new Outcome(true, 1, 3));
    }

    @Test
    void rendersTheTaskRuleAndActualMeasurementsRatherThanInternalSentinels() throws Exception {
        String svg = AutonomousDiscoveryComparisonSvg.render(example());
        Document document = parse(svg);
        assertTrue(svg.contains(example().inputExpression()));
        assertTrue(svg.contains(example().targetExpression()));
        assertTrue(svg.contains(example().leftPattern()));
        assertTrue(svg.contains(example().rightPattern()));
        assertTrue(svg.contains("Ziel im Lauf nicht erreicht"));
        assertTrue(svg.contains("Kein Zielpfad gefunden"));
        assertTrue(svg.contains("1 Umformung im Zielpfad"));
        assertTrue(svg.contains(">80</text>"));
        assertTrue(svg.contains(">3</text>"));
        assertTrue(svg.contains(AutonomousDiscoveryResultCard.CLAIM_BANNER));
        assertTrue(svg.contains("nicht gemessene Laufzeiten"));
        assertFalse(svg.contains("Path length: -1"));
        assertFalse(svg.contains("Reached: false"));
        assertEquals("img", document.getDocumentElement().getAttribute("role"));
        assertEquals(0, document.getElementsByTagName("path").getLength(),
            "The two independent searches must not look like consecutive states");
    }

    @Test
    void wrapsLongFormulaeAndIdentifiersWithoutDroppingTheirFullAccessibleText() throws Exception {
        String expression = "x".repeat(170) + " + <tag> & \"quoted\"";
        Comparison data = new Comparison("case-".repeat(50), expression, expression,
            "A", "A", example().baseline(), example().candidate());
        Document document = parse(AutonomousDiscoveryComparisonSvg.render(data));
        assertTrue(document.getElementsByTagName("desc").item(0)
            .getTextContent().contains(expression));
        assertEquals(0, document.getElementsByTagName("tag").getLength());
        assertEquals(data.id(), document.getDocumentElement().getAttribute("data-case-id"));
        var texts = document.getElementsByTagName("text");
        for (int index = 0; index < texts.getLength(); index++) {
            Element text = (Element) texts.item(index);
            if ("formula".equals(text.getAttribute("class"))) {
                String line = text.getTextContent();
                assertTrue(line.codePointCount(0, line.length()) <= 72, line);
            }
        }
        int height = Integer.parseInt(document.getDocumentElement().getAttribute("height"));
        int ordinaryHeight = Integer.parseInt(parse(AutonomousDiscoveryComparisonSvg.render(example()))
            .getDocumentElement().getAttribute("height"));
        assertTrue(height > ordinaryHeight, "Wrapping must grow the layout, not cover following panels");
    }

    @Test
    void doesNotTurnAFailedCandidateOrZeroStepSuccessIntoAFabricatedOneStepGain() throws Exception {
        Comparison data = new Comparison("regression", "x", "x", "A", "A",
            new Outcome(true, 0, 1), new Outcome(false, -1, Long.MAX_VALUE));
        String svg = AutonomousDiscoveryComparisonSvg.render(data);
        parse(svg);
        assertTrue(svg.contains("0 Umformungen im Zielpfad"));
        assertTrue(svg.contains("Kein Zielpfad gefunden"));
        assertTrue(svg.contains(Long.toString(Long.MAX_VALUE)));
        assertTrue(svg.contains("class=\"countLong\""));
        assertFalse(svg.contains("1 Umformung im Zielpfad"));
        assertFalse(svg.contains("Material gain"));
    }

    @Test
    void rejectsInconsistentOutcomesAndMissingContext() {
        assertThrows(IllegalArgumentException.class, () -> new Outcome(true, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Outcome(false, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Outcome(false, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Comparison("", "x", "x", "A", "A",
            example().baseline(), example().candidate()));
        assertThrows(NullPointerException.class, () -> new Comparison("case", "x", "x", "A", "A",
            null, example().candidate()));
        assertThrows(NullPointerException.class, () -> AutonomousDiscoveryComparisonSvg.render(null));
    }

    @Test
    void renderingIsByteDeterministic() {
        assertEquals(AutonomousDiscoveryComparisonSvg.render(example()),
            AutonomousDiscoveryComparisonSvg.render(example()));
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }
}
