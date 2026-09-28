package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.symbol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class AstExpressionValidationTest {
    @Test void canonicalSizeCountsEveryTagSeparatorEscapeAndUtf8ByteWithoutCreatingJson() {
        var scope=new SymbolScope(new UUID(0,12));
        for(var expression:List.<Expr>of(new NumberExpr(1),NumberExpr.exact("-7/13"),new VariableExpr("x"),
                VariableExpr.scoped(scope.declare("x")),new FunctionExpr("f",List.of()),
                new FunctionExpr("f",List.of(new VariableExpr("é\n\"\\\u0001😀"),new NumberExpr(0))),
                new BinaryExpr(new VariableExpr("a"),BinaryOperator.ADD,new NumberExpr(0)))) {
            long bytes=new CompiledAstReplayCodec().encodeExpression(expression).getBytes(StandardCharsets.UTF_8).length;
            assertEquals(bytes,AstExpressionValidation.inspect(expression).canonicalBytes(),expression.toString());
            assertEquals(new CompiledAstReplayCodec().encodeExpression(expression).length(),AstExpressionValidation.inspect(expression).canonicalCharacters());
        }
    }
}
