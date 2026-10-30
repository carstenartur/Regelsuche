package de.regelsuche.docs;

import de.regelsuche.ast.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.HiddenRulePilotRunner.*;
import de.regelsuche.evolution.ExactPolynomialPatternVerificationService;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.*;
import de.regelsuche.transform.*;
import java.util.*;

/** Independent historical controls using existing runtime and learned precursors, not a new solver. */
final class HistoricalSearchControlContract {
    static final String SOPHIE = HistoricalAbstractionBoundaryContract.SOPHIE;
    static final String BRAHMAGUPTA = HistoricalAbstractionBoundaryContract.BRAHMAGUPTA;
    private enum Case {
        FACTORIZATION("sophie-germain", SOPHIE, HistoricalAbstractionBoundaryContract.Shape.NONCONSTANT_PRODUCT, TransformationGoal.FACTORIZE),
        TWO_SQUARES("brahmagupta-fibonacci", BRAHMAGUPTA, HistoricalAbstractionBoundaryContract.Shape.TWO_BILINEAR_SQUARES, TransformationGoal.SIMPLIFY);
        final String id;
        final String source;
        final HistoricalAbstractionBoundaryContract.Shape goal;
        final TransformationGoal ranking;
        Case(String id, String source, HistoricalAbstractionBoundaryContract.Shape goal, TransformationGoal ranking) {
            this.id = id; this.source = source; this.goal = goal; this.ranking = ranking;
        }
    }
    private final ExactPolynomialPatternVerificationService proof = new ExactPolynomialPatternVerificationService();
    private final ExpressionParser parser = new ExpressionParser();
    private final List<Map<String,Object>> rows = new ArrayList<>();
    private final List<Map<String,Object>> learning = new ArrayList<>();
    private static void check(boolean b,String m) {HistoricalAbstractionBoundaryContract.check(b,m);}
    private record Frozen(DynamicPatternOperator operator, CandidateSnapshot snapshot) { }
    private static TransformationEngine engine(List<HypothesisOperator> ops) {
        return new HypothesisTransformationEngine(ignored -> List.of(), ops,96);
    }
    private Frozen freeze(boolean complete) {
        String source=complete?"p^2+q^2":"r^2-s^2";
        TransformationEngine teacher;
        if(complete) teacher=engine(List.of(new SumOfSquaresCompletionOperator()));
        else teacher=new AstRewriteTransformationEngine(AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule->rule.id().equals("ast_square_difference_factor")).toList());
        var direct=teacher.transform(source);check(direct.size()==1,"one generic precursor demonstration");
        var task=new RuntimeTask(complete?"precursor-complete":"precursor-factor",source,
            SearchProblem.SearchTarget.syntaxExact(direct.getFirst().transformedExpression()),teacher,
            new SearchHeuristic(1,48,1,4,8,16),
            List.of(new PositiveHoldout("compound",complete?"(m+1)^2+n^2":"(m+1)^2-n^2",
                complete?"((m+1)+n)^2-2*(m+1)*n":"((m+1)-n)*((m+1)+n)")),
            List.of(new NegativeHoldout("wrong-shape",complete?"m^3+n^2":"m^3-n^2")));
        var result=new HiddenRulePilotRunner().run(task);
        check(result.frozen() && result.holdouts().allPassed(),"precursor really learned and frozen: "+result);
        var c=result.candidate();check(proof.verify(c.leftPattern(),c.rightPattern()).proved(),"independent symbolic theorem proof");
        var compiled=new DynamicOperatorCompiler().compile("pilot-"+task.opaqueCaseId(),"frozen-v1",c.leftPattern(),c.rightPattern());
        check(compiled.isSuccess(),"learned pattern executable");
        learning.add(Map.of("id",task.opaqueCaseId(),"source",source,"patternLeft",c.leftPattern(),"patternRight",c.rightPattern(),
            "discoveryMetrics",result.searchMetrics(),"status",result.status().name(),"scope","teacher-assisted precursor lesson, not autonomous theorem discovery"));
        return new Frozen(compiled.operator().orElseThrow(),c);
    }
    Map<String,Object> run() {
        Frozen completion=freeze(true), difference=freeze(false);
        var exposed=new ExactMonomialSquareExposureOperator();
        var nativeDifference=new AstRewriteTransformationEngine(AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule->rule.id().equals("ast_square_difference_factor")).toList());
        HypothesisOperator differenceAdapter=nativeDifference::transform;
        TransformationEngine weak=engine(List.of(exposed));
        TransformationEngine primitive=engine(List.of(exposed,new SumOfSquaresCompletionOperator(),differenceAdapter));
        TransformationEngine learned=engine(List.of(exposed,completion.operator(),difference.operator()));
        for(int round=1;round<=10;round++) {
            runSearch(round,Case.FACTORIZATION,"exposure-only-ablation",weak,false,5,256);
            runSearch(round,Case.FACTORIZATION,"equivalent-native-precursors",primitive,false,5,256);
            runSearch(round,Case.FACTORIZATION,"frozen-learned-precursors",learned,false,5,256);
        }
        var bfLearned=brahmaguptaEngine(completion.operator());
        var bfNative=brahmaguptaEngine(new SumOfSquaresCompletionOperator());
        for(int round=1;round<=10;round++) {
            runSearch(round,Case.TWO_SQUARES,"native-scalar",bfNative,false,12,256);
            runSearch(round,Case.TWO_SQUARES,"learned-scalar",bfLearned,false,12,256);
            runSearch(round,Case.TWO_SQUARES,"learned-diversity",bfLearned,true,12,256);
        }
        runSearch(0,Case.TWO_SQUARES,"native-scalar-10x-states",bfNative,false,12,2560);
        runSearch(0,Case.TWO_SQUARES,"learned-scalar-10x-states",bfLearned,false,12,2560);
        var residual=residual(completion.operator());
        check(rows.stream().filter(r->r.get("arm").equals("frozen-learned-precursors")).allMatch(r->(boolean)r.get("structuralSuccess")),"reproduce source-only Sophie positive control");
        check(rows.stream().filter(r->r.get("arm").equals("exposure-only-ablation")).noneMatch(r->(boolean)r.get("structuralSuccess")),"ablation retains its negative outcome");
        return Map.of("learning",learning,"searches",rows,"residualComposer",residual,
            "costBoundary","search states/calls/candidates are not a total-work or lifecycle speedup comparison; teacher/freeze/proof costs not collapsed");
    }
    private TransformationEngine brahmaguptaEngine(HypothesisOperator completion) {
        var rules=AstRewriteTransformationEngine.allBuiltInRules().stream().filter(r->Set.of("ast_distribute_left_add","ast_distribute_right_add","ast_canonical_normalize").contains(r.id())).toList();
        return new HypothesisTransformationEngine(new AstRewriteTransformationEngine(rules),List.of(new ExactMonomialSquareExposureOperator(),
            new SubtreeHypothesisOperator(new SquareBaseSignSymmetryOperator(),16),new AdditivePairHypothesisOperator(completion,24)),96);
    }
    private void runSearch(int round,Case task,String arm,TransformationEngine original,boolean diversity,int depth,int ceiling) {
        String source = task.source;
        long[] calls={0}, candidates={0};
        TransformationEngine counted=s->{calls[0]++;var result=original.transform(s);candidates[0]+=result.size();return result;};
        var problem=new SearchProblem(source,counted,new ExpressionScorer(),new ExpressionCanonicalizer(),new SearchHeuristic(depth,ceiling,1,12,64,64))
            .withObjective(task.ranking).withoutTarget();
        long started=System.nanoTime();
        List<SearchState> states=diversity?new StructuralDiversitySearchStrategy().search(problem):new BestFirstSearchStrategy().search(problem);
        long elapsed=System.nanoTime()-started;
        var goal=task.goal;
        var successful=states.stream().filter(s->goal.accepts(parser.parseExactTerm(s.expression()).expression())).toList();
        for(var s:successful) check(proof.verify(source,s.expression()).proved(),"candidate must be exactly equivalent");
        var first=successful.isEmpty()?null:successful.getFirst();
        var row=new TreeMap<String,Object>();row.put("round",round);row.put("case",task.id);row.put("arm",arm);row.put("states",states.size());row.put("calls",calls[0]);
        row.put("generated",candidates[0]);row.put("elapsedNanos",elapsed);row.put("structuralSuccess",first!=null);row.put("depthLimit",depth);row.put("stateLimit",ceiling);
        row.put("result",first==null?"":first.expression());row.put("path",first==null?List.of():first.path());row.put("rules",first==null?List.of():first.appliedRuleIds());rows.add(row);
        System.out.println("SEARCH "+round+" "+task.id+" "+arm+" success="+(first!=null)+" states="+states.size()+" calls="+calls[0]+" generated="+candidates[0]);
    }
    private Map<String,Object> residual(DynamicPatternOperator completion) {
        var normal=new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());
        String current=normal.normalForm(BRAHMAGUPTA).orElseThrow();
        var exposure=new ExactMonomialSquareExposureOperator();
        var exposurePath=new ArrayList<String>();exposurePath.add(current);
        for(int step=0;step<8;step++) {var c=exposure.generateCandidates(current);if(c.isEmpty()) break;current=c.getFirst().transformedExpression();exposurePath.add(current);}
        check(proof.verify(BRAHMAGUPTA,current).proved(),"normal form and exposure preserve source");
        var composer=new ExactPolynomialResidualComposer();var parts=composer.additiveComponents(current);
        var plus=new ArrayList<ExactPolynomialResidualComposer.Effect>();var all=new ArrayList<ExactPolynomialResidualComposer.Effect>();
        for(int i=0;i<parts.size();i++) for(int j=i+1;j<parts.size();j++) {
            var pair=List.of(parts.get(i),parts.get(j));String pairSource="("+pair.get(0).expression()+")+("+pair.get(1).expression()+")";
            var move=only(completion.generateCandidates(pairSource));
            var positive=composer.effect("plus-"+i+"-"+j,pair,move.transformedExpression(),square(move.transformedExpression()),List.of(),move.primitiveRuleIds(),List.of(move.applicationKey()));
            plus.add(positive);all.add(positive);
            var reflection=only(new SquareBaseSignSymmetryOperator().generateCandidates(pair.get(1).expression()));
            var reflected=only(completion.generateCandidates("("+pair.get(0).expression()+")+("+reflection.transformedExpression()+")"));
            var ids=new ArrayList<>(reflection.primitiveRuleIds());ids.addAll(reflected.primitiveRuleIds());
            all.add(composer.effect("minus-"+i+"-"+j,pair,reflected.transformedExpression(),square(reflected.transformedExpression()),List.of(),ids,List.of(reflection.applicationKey(),reflected.applicationKey())));
        }
        var none=composer.compose(current,parts,plus,2,16);var found=List.copyOf(composer.compose(current,parts,all,2,16));
        check(none.isEmpty(),"positive-only completions cannot cancel the remainders");check(found.size()==2,"two source-derived complementary sign choices");
        for(var c:found) check(proof.verify(BRAHMAGUPTA,c.candidateExpression()).proved(),"independent exact reconstruction");
        return Map.of("components",parts.size(),"effects",all.size(),"positiveOnlyCandidates",none.size(),"candidates",found.stream().map(ExactPolynomialResidualComposer.Composition::candidateExpression).toList(),
            "exposurePath",exposurePath,"scope","existing hand-designed residual-selection procedure; pairing and signs selected without target; not learned strategy");
    }
    private static Transformation only(List<Transformation> candidates) {check(candidates.size()==1,"exactly one generic application");return candidates.getFirst();}
    private String square(String expression) {
        var root=parser.parseExactTerm(expression).expression();var result=new ArrayList<Expr>();collect(root,result);
        check(result.size()==1,"one completed square");return ExpressionFormatter.format(result.getFirst());
    }
    private static void collect(Expr e,List<Expr> squares) {
        if(!(e instanceof BinaryExpr b)) return;
        if(b.operator()==BinaryOperator.POW && b.right() instanceof NumberExpr n && n.value().equalsInteger(2)
            && b.left() instanceof BinaryExpr l && (l.operator()==BinaryOperator.ADD||l.operator()==BinaryOperator.SUB)) squares.add(e);
        collect(b.left(),squares);collect(b.right(),squares);
    }
    public static void main(String[] args) throws Exception {
        var report=new HistoricalSearchControlContract().run();
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of(args[0]).toFile(),report);
        System.out.println("PASS historical learned-precursor and residual controls");
    }
}
