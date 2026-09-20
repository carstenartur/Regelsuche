package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CompiledAstExpressionCacheTest {
    private final CompiledAstReplayCodec codec = new CompiledAstReplayCodec();

    @Test void scopedDecodeReusesTheValidatedExpressionAcrossCodecInstances() {
        String document = codec.encodeExpression(new ExpressionParser().parseTerm("a+(b*c)"));
        inScope(8, 16_384, () -> {
            Expr first = codec.decodeExpression(document);
            assertSame(first, codec.decodeExpression(new String(document)));
            assertSame(first, new CompiledAstReplayCodec().decodeExpression(document));
            assertSame(document, codec.encodeExpression(first));
            return null;
        });
    }

    @Test void scopedEncodeReusesByIdentityAndKeepsOneExpressionForEquivalentText() {
        Expr first = new ExpressionParser().parseTerm("a+(b*c)");
        Expr equalButDistinct = new ExpressionParser().parseTerm("a+(b*c)");
        inScope(8, 16_384, () -> {
            String document = codec.encodeExpression(first);
            assertSame(document, codec.encodeExpression(first));
            assertSame(first, codec.decodeExpression(document));
            assertEquals(document, codec.encodeExpression(equalButDistinct));
            assertSame(first, codec.decodeExpression(document));
            return null;
        });
    }

    @Test void defaultAndSeparateSessionsDoNotReuseExpressionObjects() {
        String document = codec.encodeExpression(new VariableExpr("a"));
        assertNotSame(codec.decodeExpression(document), codec.decodeExpression(document));
        Expr first = inScope(4, 4_096, () -> codec.decodeExpression(document));
        Expr second = inScope(4, 4_096, () -> codec.decodeExpression(document));
        assertNotSame(first, second);
        assertNotSame(first, codec.decodeExpression(document));
    }

    @Test void invalidDocumentsCannotBorrowValidationFromAnExistingEntryOrEvictIt() {
        String document = codec.encodeExpression(new VariableExpr("a"));
        String other = codec.encodeExpression(new VariableExpr("b"));
        inScope(1, 4_096, () -> {
            Expr first = codec.decodeExpression(document);
            for (String invalid : List.of("{", document + "{}", " " + other,
                    document.replace("/v1", "/v2"), document.replace("\"schema\":", "\"unknown\":"),
                    document.replace("\"name\":\"a\"", "\"name\":\"a\",\"name\":\"a\""))) {
                assertThrows(IllegalArgumentException.class, () -> codec.decodeExpression(invalid));
            }
            assertSame(first, codec.decodeExpression(document));
            return null;
        });
    }

    @Test void entryEvictionUsesInsertionOrderAndRemovesTheEncodeIdentity() {
        Expr first = new VariableExpr("a");
        String firstDocument = codec.encodeExpression(first);
        inScope(2, 4_096, () -> {
            String retainedDocument = codec.encodeExpression(first);
            Expr second = codec.decodeExpression(codec.encodeExpression(new VariableExpr("b")));
            assertSame(first, codec.decodeExpression(firstDocument));
            codec.encodeExpression(new VariableExpr("c"));
            assertSame(second, codec.decodeExpression(codec.encodeExpression(second)));
            assertNotSame(retainedDocument, codec.encodeExpression(first));
            return null;
        });
    }

    @Test void aggregateCharacterLimitEvictsAtTheExactBoundary() {
        String a = codec.encodeExpression(new VariableExpr("a"));
        String b = codec.encodeExpression(new VariableExpr("b"));
        String c = codec.encodeExpression(new VariableExpr("c"));
        inScope(8, a.length() + b.length(), () -> {
            Expr first = codec.decodeExpression(a);
            Expr second = codec.decodeExpression(b);
            assertSame(first, codec.decodeExpression(a));
            codec.decodeExpression(c);
            assertSame(second, codec.decodeExpression(b));
            assertNotSame(first, codec.decodeExpression(a));
            return null;
        });
    }

    @Test void oversizedExpressionsAreReturnedUncachedWithoutDisplacingSmallerEntries() {
        String small = codec.encodeExpression(new VariableExpr("a"));
        String large = codec.encodeExpression(new VariableExpr("longer"));
        inScope(4, small.length(), () -> {
            Expr retained = codec.decodeExpression(small);
            assertEquals(new VariableExpr("longer"), codec.decodeExpression(large));
            assertNotSame(codec.decodeExpression(large), codec.decodeExpression(large));
            assertSame(retained, codec.decodeExpression(small));
            return null;
        });
    }

    @Test void nestedScopesAreFreshAndRestoreTheOuterScopeEvenAfterFailure() {
        String document = codec.encodeExpression(new VariableExpr("a"));
        Expr outer = inScope(4, 4_096, () -> {
            Expr first = codec.decodeExpression(document);
            Expr nested = inScope(4, 4_096, () -> {
                Expr second = codec.decodeExpression(document);
                assertSame(second, codec.decodeExpression(document));
                return second;
            });
            assertNotSame(first, nested);
            assertThrows(IllegalStateException.class, () -> inScope(4, 4_096, () -> {
                assertNotSame(first, codec.decodeExpression(document));
                throw new IllegalStateException("abort query");
            }));
            assertSame(first, codec.decodeExpression(document));
            return first;
        });
        assertNotSame(outer, codec.decodeExpression(document));
    }

    @Test void zeroLimitsDisableReuseInsideAnOuterScope() {
        String document = codec.encodeExpression(new VariableExpr("a"));
        inScope(4, 4_096, () -> {
            Expr retained = codec.decodeExpression(document);
            for (boolean disableEntries : List.of(true, false)) {
                inScope(disableEntries ? 0 : 4, disableEntries ? 4_096 : 0, () -> {
                    assertNotSame(retained, codec.decodeExpression(document));
                    assertNotSame(codec.decodeExpression(document), codec.decodeExpression(document));
                    return null;
                });
            }
            assertSame(retained, codec.decodeExpression(document));
            return null;
        });
    }

    @Test void scopeIsNotInheritedByOtherThreads() {
        String document = codec.encodeExpression(new VariableExpr("a"));
        inScope(4, 4_096, () -> {
            Expr retained = codec.decodeExpression(document);
            var outside = new AtomicReference<Expr>();
            var thread = new Thread(() -> outside.set(codec.decodeExpression(document)));
            thread.start();
            try { thread.join(); }
            catch (InterruptedException exception) { throw new AssertionError(exception); }
            assertNotSame(retained, outside.get());
            assertSame(retained, codec.decodeExpression(document));
            return null;
        });
    }

    @Test void invalidBoundsAndNullWorkAreRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> inScope(-1, 4_096, () -> fail("executed")));
        assertThrows(IllegalArgumentException.class, () -> inScope(4, -1, () -> fail("executed")));
        assertThrows(NullPointerException.class, () -> inScope(4, 4_096, null));
    }

    private static <T> T inScope(int entries, long characters, Supplier<T> work) {
        return CompiledAstReplayCodec.withExpressionCache(entries, characters, work);
    }
}
