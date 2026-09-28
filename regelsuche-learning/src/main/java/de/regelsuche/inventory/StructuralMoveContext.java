package de.regelsuche.inventory;

import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Source structure only; unknown degree is explicit. Variable spelling is absent from the context key. */
public record StructuralMoveContext(String rootOperator, int degree, int variables, int products, int powers,
        int repeatedSubtrees, List<String> assumptions, List<String> capabilities, int visitedNodes) implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(rootOperator);v.reference(assumptions);v.reference(capabilities);}
    public static StructuralMoveContext of(MoveState state) {
        var root = new ExpressionParser().parse(new InputRequest(InputType.TERM, state.expression())).terms().getFirst();
        return of(root, state);
    }
    /** Explicit transport boundary; historical expression parsing remains unchanged. */
    public static StructuralMoveContext fromTyped(MoveState state) {
        return of(new de.regelsuche.search.program.CompiledAstReplayCodec().decodeExpression(state.expression()), state);
    }
    public static StructuralMoveContext of(de.regelsuche.search.moves.TypedMoveSearch.State state) {
        return of(state.expression(),state.assumptions(),state.capabilities());
    }
    private static StructuralMoveContext of(Expr root, MoveState state) {
        return of(root,state.assumptions(),state.capabilities());
    }
    private static StructuralMoveContext of(Expr root,List<String> assumptions,java.util.Set<String> capabilities) {
        var seen = new HashMap<Expr, Integer>(); var variables = new HashSet<String>();
        int[] counts = new int[3];
        RetainedOperation.work(3);
        try(var retained=RetainedOperation.retain(root,seen,variables,counts,assumptions,capabilities)) {
        collect(root, seen, variables, counts);
        String operator = root instanceof BinaryExpr binary ? binary.operator().name()
            : root instanceof FunctionExpr function ? "FUNCTION:" + function.name() : root instanceof VariableExpr ? "VARIABLE" : "NUMBER";
        return RetainedOperation.produced(new StructuralMoveContext(operator, degree(root), variables.size(), counts[1], counts[2],
            seen.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum(), assumptions,
            capabilities.stream().sorted().toList(), counts[0]));
        } finally {RetainedOperation.work(2L*seen.size()+variables.size()+3);seen.clear();variables.clear();}
    }
    public String key() {
        var writer=new JsonWriter();
        try(var retained=RetainedOperation.retain(this,writer)) {
        String result=writer.beginObject().property("root", rootOperator).property("degree", degree).property("variables", variables)
            .property("products", products).property("powers", powers).property("repeated", repeatedSubtrees)
            .stringArray("assumptions", assumptions).stringArray("capabilities", capabilities).endObject().toString();
        RetainedOperation.work(result.length());return RetainedOperation.produced(result);
        }
    }
    /** Prevents typed observations from silently changing frozen historical feature tables. */
    public String typedKey() {
        String key=key();try(var retained=RetainedOperation.retain(key)){
            String result="regelsuche.typed-structural-context/v1:"+key;
            RetainedOperation.work(result.length());return RetainedOperation.produced(result);
        }
    }
    private static void collect(Expr expr, HashMap<Expr, Integer> seen, HashSet<String> variables, int[] counts) {
        RetainedOperation.work(3);
        counts[0]++; seen.merge(expr, 1, Integer::sum);
        if (expr instanceof VariableExpr variable) variables.add(variable.name());
        else if (expr instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.MUL) counts[1]++;
            if (binary.operator() == BinaryOperator.POW) counts[2]++;
            collect(binary.left(), seen, variables, counts); collect(binary.right(), seen, variables, counts);
        } else if (expr instanceof FunctionExpr function) for (var argument : function.arguments()) collect(argument, seen, variables, counts);
    }
    private static int degree(Expr expr) {
        RetainedOperation.work(1);
        if (expr instanceof VariableExpr) return 1;
        if (expr instanceof NumberExpr) return 0;
        if (!(expr instanceof BinaryExpr binary)) return -1;
        int left = degree(binary.left()), right = degree(binary.right());
        if (left < 0 || right < 0) return -1;
        return switch (binary.operator()) {
            case ADD, SUB -> Math.max(left, right);
            case MUL -> Math.min(1000, left + right);
            case POW -> binary.right() instanceof NumberExpr number && number.value().isInteger()
                && number.value().numerator().signum() >= 0 && number.value().numerator().bitLength() < 10
                ? Math.min(1000, left * number.value().numerator().intValueExact()) : -1;
            case DIV -> right == 0 ? left : -1;
        };
    }
}
