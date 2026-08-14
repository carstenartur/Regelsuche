package de.regelsuche.discovery.representation;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PatternExpr;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnownStructureMatcherTest {
    @Test
    void findsKnownStructuresAtExactOccurrences() {
        KnownStructureCatalog catalog = new KnownStructureCatalog(
            "catalog-v1",
            List.of(perfectSquare())
        );
        KnownStructureMatcher matcher = new KnownStructureMatcher(catalog);

        List<KnownStructureMatch> matches =
            matcher.match("z + (a + b)^2");

        assertTrue(matches.stream().anyMatch(match ->
            match.structureId().equals("perfect-square")
                && match.occurrencePath().equals(
                    new ExpressionOccurrencePath(List.of(1)))));
        assertFalse(matches.stream().anyMatch(
            KnownStructureMatch::wholeExpression));
    }

    @Test
    void catalogIdentityIsIndependentOfInputOrdering() {
        KnownStructure first = perfectSquare();
        KnownStructure second = new KnownStructure(
            "sum",
            "algebra",
            PatternExpr.op(ADD, PatternExpr.var("left"), PatternExpr.var("right")),
            List.of(),
            List.of(),
            "first-party"
        );

        assertEquals(
            new KnownStructureCatalog("v1", List.of(first, second)).contentHash(),
            new KnownStructureCatalog("v1", List.of(second, first)).contentHash()
        );
    }

    @Test
    void duplicateStructureIdsFailClosed() {
        KnownStructure structure = perfectSquare();

        assertThrows(
            IllegalArgumentException.class,
            () -> new KnownStructureCatalog("v1", List.of(structure, structure))
        );
    }

    private static KnownStructure perfectSquare() {
        return new KnownStructure(
            "perfect-square",
            "algebra",
            PatternExpr.op(
                POW,
                PatternExpr.var("base"),
                PatternExpr.num(2)
            ),
            List.of(),
            List.of("rule:perfect-square-reasoning"),
            "first-party"
        );
    }
}
