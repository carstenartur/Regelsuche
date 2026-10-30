package de.regelsuche.docs;

import de.regelsuche.ast.*;
import de.regelsuche.evolution.*;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.mining.TypedPatternGeneralizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.transform.*;
import java.util.*;

/** Real-runtime characterization, not a new search kernel or a mathematical capability claim. */
final class HistoricalAbstractionBoundaryContract {
    static final String SOPHIE = "x^4+4*y^4";
    static final String BRAHMAGUPTA = "(a^2+b^2)*(c^2+d^2)";
    static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(16,16,100_000,512,100_000);
    static final int ROUNDS = 10;
    private static Expr parse(String expression) { return new ExpressionParser().parseExactTerm(expression).expression(); }

    enum Shape implements TypedSourceOnlySearch.Objective {
        NONCONSTANT_PRODUCT, TWO_BILINEAR_SQUARES;
        @Override public TypedSourceOnlySearch.Score evaluate(de.regelsuche.search.moves.TypedMoveSearch.State state) {
            Expr e = state.expression();
            return new TypedSourceOnlySearch.Score(accepts(e) ? 0 : 1, nodes(e) * 4L + 1);
        }
        boolean accepts(Expr e) {
            if (!(e instanceof BinaryExpr b)) return false;
            if (this == NONCONSTANT_PRODUCT) return b.operator() == BinaryOperator.MUL && degree(b.left()) > 0 && degree(b.right()) > 0;
            return b.operator() == BinaryOperator.ADD && mixedSquare(b.left()) && mixedSquare(b.right());
        }
    }
    private static int nodes(Expr e) {
        if(e instanceof BinaryExpr b) return 1 + nodes(b.left()) + nodes(b.right());
        if(e instanceof FunctionExpr f) return 1 + f.arguments().stream().mapToInt(HistoricalAbstractionBoundaryContract::nodes).sum();
        return 1;
    }
    private static int degree(Expr e) {
        if(e instanceof VariableExpr) return 1;
        if(e instanceof NumberExpr) return 0;
        if(!(e instanceof BinaryExpr b)) return -1000;
        int l = degree(b.left()), r = degree(b.right());
        return switch(b.operator()) {
            case ADD, SUB -> Math.max(l,r);
            case MUL -> l+r;
            case DIV -> r == 0 ? l : -1000;
            case POW -> b.right() instanceof NumberExpr n && n.value().equalsInteger(2) ? l*2 : -1000;
        };
    }
    private static boolean mixedSquare(Expr e) {
        return e instanceof BinaryExpr p && p.operator() == BinaryOperator.POW
            && p.right() instanceof NumberExpr n && n.value().equalsInteger(2)
            && p.left() instanceof BinaryExpr b && (b.operator() == BinaryOperator.ADD || b.operator() == BinaryOperator.SUB)
            && degree(b.left()) == 2 && degree(b.right()) == 2;
    }
    static CheckedSchemaTaskSolver.Task task(String input, Shape goal, MoveSearch.Budget budget) {
        return new CheckedSchemaTaskSolver.Task(parse(input), List.of(), new CheckedSchemaTaskSolver.Goal(goal,0),budget);
    }
    static Map<String,Object> expressionBoundary() {
        check(!Shape.NONCONSTANT_PRODUCT.accepts(parse(SOPHIE)), "equivalence alone is not factorization");
        check(!Shape.TWO_BILINEAR_SQUARES.accepts(parse(BRAHMAGUPTA)), "unchanged source is not the required representation");
        check(!Shape.NONCONSTANT_PRODUCT.accepts(parse("1*(x^4+4*y^4)")), "a unit factor is not a nontrivial factorization");
        var base = TraceStrategyTransferExample.inventory();
        var verifier = new ExactPolynomialPatternVerificationService();
        var rows = new ArrayList<Map<String,Object>>();
        String[][] pairs = {{"split", "?A", "?A/2+?A/2"}, {"completion", "?A^2+?B^2", "(?A+?B)^2-2*?A*?B"},
            {"auxiliary", "?A", "(?A+?T)-?T"}};
        for(var p: pairs) {
            var gene = new EvolutionGenome.RewriteGene(p[0],p[1],p[2],RewriteKind.EXPAND,false,1,8,32,List.of(),base.rewrites().getFirst().evidenceObligations());
            var report = new EvolutionGenomeValidator().validate(base.withRewrites(List.of(gene)));
            new ExactPolynomialAnalysis().requireEquivalent(p[1].replace("?", ""),p[2].replace("?", ""));
            check(report.accepted() != p[0].equals("auxiliary"), "bound and unbound pattern contracts");
            rows.add(Map.of("name",p[0],"source",p[1],"target",p[2],"proved",true,"accepted",report.accepted(),"blockers",report.blockerCodes().toString(),"integerPatternChecker",verifier.verify(p[1],p[2]).status().name()));
        }
        var generalizer = new TypedPatternGeneralizer();
        var generated = generalizer.generalize(List.of(example("p^2+q^2", "(p+q)^2-2*p*q"),example("r^2+s^2", "(r+s)^2-2*r*s")));
        check(generated.isPresent(), "a compound new term made from bound inputs can be generalized");
        var unbound = generalizer.generalize(List.of(example("p", "(p+q)-q"),example("r", "(r+s)-s")));
        check(unbound.isEmpty(), "an auxiliary not determined by the source is not invented by anti-unification");
        var completion = new SumOfSquaresCompletionOperator();
        check(completion.generateCandidates(SOPHIE).isEmpty(), "hidden squares are not a syntactic direct match");
        String exposed = SOPHIE;
        for (int i=0;i<2;i++) exposed = new ExactMonomialSquareExposureOperator().generateCandidates(exposed).getFirst().transformedExpression();
        check(completion.generateCandidates(exposed).size()==1, "exposing squares enables the generic completion");
        return Map.of("patterns",rows,"compoundTermGeneralized",true,"freeAuxiliaryGeneralized",false,"rawCompletionCandidates",0,
            "exposedSource",exposed,"completion",completion.generateCandidates(exposed).getFirst().transformedExpression());
    }
    private static TypedPatternGeneralizer.Example example(String s,String t) {
        return new TypedPatternGeneralizer.Example(parse(s),parse(t));
    }
    static Map<String,Object> cumulativeRounds() {
        var current = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var baseline = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var tasks = List.of(task(SOPHIE,Shape.NONCONSTANT_PRODUCT,BUDGET),task(BRAHMAGUPTA,Shape.TWO_BILINEAR_SQUARES,BUDGET));
        var validation = List.of(task("(u+1)^4+4*v^4",Shape.NONCONSTANT_PRODUCT,BUDGET),
            task("((p+1)^2+q^2)*(r^2+s^2)",Shape.TWO_BILINEAR_SQUARES,BUDGET));
        var firstBatch = baseline.learnBatch(tasks,validation,500_000);
        var frozen = firstBatch.next();
        var primitive = new AstRewriteTransformationEngine(new EvolutionGenomeCompiler().compile(TraceStrategyTransferExample.inventory()).rules());
        check(primitive.transform(SOPHIE).isEmpty() && primitive.transform(BRAHMAGUPTA).isEmpty(), "no initial primitive candidates");
        var rows = new ArrayList<Map<String,Object>>();
        var updates = new ArrayList<Map<String,Object>>();
        for(int round=1;round<=ROUNDS;round++) {
            for(int i=0;i<tasks.size();i++) {
                var t=tasks.get(i);
                rows.add(row(round,i,"restart-baseline",baseline.solve(t,CumulativeTaskKnowledge.Strategy.BASELINE)));
                rows.add(row(round,i,"frozen-after-first-batch",frozen.solve(t,CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST)));
                rows.add(row(round,i,"cumulative",current.solve(t,CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST)));
            }
            var update=current.learnBatch(tasks,validation,500_000);
            current=update.next();
            updates.add(Map.of("round",round,"promoted",update.promoted(),"reason",update.reason().name(),"schemas",current.schemaCount(),
                "paidAcquisition",update.work().total(),"cumulativeAcquisition",current.acquisitionWork(),
                "formationAttempts",current.lastAttempts().stream().map(Object::toString).toList(),"snapshot",current.id()));
        }
        for(int i=0;i<tasks.size();i++) {
            var t=tasks.get(i);
            var longBudget=new MoveSearch.Budget(160,160,1_000_000,5120,1_000_000);
            rows.add(row(0,i,"single-10x-budget",baseline.solve(task(i==0?SOPHIE:BRAHMAGUPTA,i==0?Shape.NONCONSTANT_PRODUCT:Shape.TWO_BILINEAR_SQUARES,longBudget),CumulativeTaskKnowledge.Strategy.BASELINE)));
        }
        check(current.schemaCount()==0,"characterization: failed lessons cannot form new schema");
        check(rows.stream().noneMatch(r->Boolean.TRUE.equals(r.get("solved"))),"current restricted inventory is not the historical full engine");
        return Map.of("rounds",ROUNDS,"inventory",TraceStrategyTransferExample.inventory().contentHash(),"rows",rows,"updates",updates,
            "frozenAcquisition",frozen.acquisitionWork(),"initialPrimitiveCandidates",List.of(0,0),"scope","restricted eight-rule cumulative API; not global mathematical unreachability");
    }
    static Map<String,Object> customGoalRouting() {
        var state = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var lessons=List.of(nodesTask("(a+b)*(a-b)+b*b+3",5), nodesTask("5*((c+d)*(c-d)+d*d)",5));
        var validation=List.of(nodesTask("(p*q+r)*(p*q-r)+r*r",5),nodesTask("(z+w)*(z-w)+w*w+7",5),
            nodesTask("x",1),nodesTask("(x+y)*(x-y)+y*(y+1)",1));
        var update=state.learnBatch(lessons,validation);
        check(update.promoted(), "known node-count lesson can still activate knowledge");
        var learned=update.next();
        var request=nodesTask("(u*v+w)*(u*v-w)+w*w",5);
        var custom=new CheckedSchemaTaskSolver.Task(request.source(),request.assumptions(),
            new CheckedSchemaTaskSolver.Goal(s -> NativeNodeCountObjective.INSTANCE.evaluate(s),5),request.budget());
        var nativeRun=learned.solve(request,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        var customRun=learned.solve(custom,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        check(nativeRun.selectedStrategy()==CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST,"validated builtin profile uses knowledge");
        check(customRun.selectedStrategy()==CumulativeTaskKnowledge.Strategy.BASELINE,"custom callback currently falls back even for the same criterion");
        check(nativeRun.solved() && customRun.solved(),"both controls still solve");
        return Map.of("builtinSelected",nativeRun.selectedStrategy().name(),"customSelected",customRun.selectedStrategy().name(),
            "builtinWork",nativeRun.totalWork(),"customWork",customRun.totalWork(),"acquisition",learned.acquisitionWork(),
            "scope","current router supports builtin node-count goals only; this test does not claim the two costs generalize");
    }
    private static CheckedSchemaTaskSolver.Task nodesTask(String source,int nodes) {
        return new CheckedSchemaTaskSolver.Task(parse(source),List.of(),CheckedSchemaTaskSolver.Goal.atMostNodes(nodes),BUDGET);
    }
    private static Map<String,Object> row(int round,int index,String arm,CumulativeTaskKnowledge.Execution e) {
        var solution=e.solution().orElseThrow();
        return Map.of("round",round,"case",index==0?"sophie-germain":"brahmagupta-fibonacci","arm",arm,"solved",e.solved(),
            "work",e.totalWork(),"outcome",solution.evidence().search().outcome().name(),"output",solution.evidence().incumbent().expression().toString(),
            "learnedApplications",solution.learnedApplications(),"primitiveSteps",solution.primitiveSteps(),"events",solution.evidence().search().events().size());
    }
    static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        var result=new TreeMap<String,Object>();result.put("representation",expressionBoundary());result.put("cumulative",cumulativeRounds());result.put("goalRouting",customGoalRouting());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String out=json.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),out);
        System.out.println("PASS representation + ten cumulative rounds; report="+args[0]);
    }
}
