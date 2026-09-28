package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.scalar.ExactRational;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolynomialTemporaryOwnershipTest {
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;long work;int simultaneousTerms;boolean rejectedCoefficient;int renderedFactors;boolean optionalEnvelope;Expr inputRoot;boolean missingInput,abortAtCoefficient;int sourceOnlyFrames,simultaneousPowers;boolean zeroTerms,abortAtZeroTerms;
        Set<Expr> inputNodes=Collections.newSetFromMap(new IdentityHashMap<>());long peakNodes;
        Set<String> normalizedVariables=new HashSet<>();
        Set<String> wantedSortKeys=Set.of();
        boolean sawSortKeys, sortCopiesOverlap, abortAtSortKeys;
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){work=Math.addExact(work,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        @Override public void checkpoint(){
            peakNodes=Math.max(peakNodes,RetainedGraph.measure(scope).retained().nodes());
            var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);int terms=0,sourceFrames=0,powers=0;
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof Object[] array && array.length==1 && array[0] instanceof Expr expression && inputNodes.contains(expression))sourceFrames++;
                if(value instanceof ExactRational rational && rational.numerator().bitLength()>4096)rejectedCoefficient=true;
                if(value instanceof Optional<?> optional){optionalEnvelope=true;optional.ifPresent(visitor::reference);}
                if(value instanceof ArrayList<?> list && !list.isEmpty() && list.stream().allMatch(Expr.class::isInstance))
                    renderedFactors=Math.max(renderedFactors,list.size());
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Map<?,?> map){
                    if(!map.isEmpty() && map.values().stream().allMatch(ExactRational.class::isInstance)){
                        terms++;
                        if(map.values().stream().anyMatch(valueEntry->((ExactRational)valueEntry).isZero()))zeroTerms=true;
                    }
                    map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                    if(!map.isEmpty() && map.keySet().stream().allMatch(String.class::isInstance) && map.values().stream().allMatch(Integer.class::isInstance)){
                        powers++;map.keySet().forEach(key->normalizedVariables.add((String)key));
                    }
                }else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
                else if(value instanceof Object[] array)for(var item:array)visitor.reference(item);
                else if(value instanceof NumberExpr number)visitor.reference(number.value());
                else if(value instanceof BinaryExpr binary){visitor.reference(binary.left());visitor.reference(binary.right());}
            }
            simultaneousTerms=Math.max(simultaneousTerms,terms);
            simultaneousPowers=Math.max(simultaneousPowers,powers);
            sourceOnlyFrames=Math.max(sourceOnlyFrames,sourceFrames);
            if(inputRoot!=null && !seen.contains(inputRoot))missingInput=true;
            if(!wantedSortKeys.isEmpty() && wantedSortKeys.stream().allMatch(key->seen.stream().anyMatch(key::equals))){
                sawSortKeys=true;
                sortCopiesOverlap=wantedSortKeys.stream().allMatch(key->seen.stream().anyMatch(
                    value->value instanceof char[] buffer && key.equals(new String(buffer))));
                if(abortAtSortKeys)throw new CoefficientLimit();
            }
            if(abortAtCoefficient && rejectedCoefficient)throw new CoefficientLimit();
            if(abortAtZeroTerms && zeroTerms)throw new CoefficientLimit();
        }
    }
    @Test void sortingOwnsBothProducedKeysAndTheirActualTextBuffers(){
        var parser=new de.regelsuche.parse.ExpressionParser();
        var source=parser.parseTerm("x*y+u*v");var observation=sourceObservation(source);
        observation.wantedSortKeys=Set.of("u*v","x*y");
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertEquals(parser.parseTerm("u*v+x*y"),new PolynomialNormalizer().normalize(source).orElseThrow());
        }
        assertTrue(observation.sawSortKeys,"both real comparator keys overlap, including the first while building the second");
        assertTrue(observation.sortCopiesOverlap,"finished key Strings overlap their actual populated buffers");
        assertFalse(observation.missingInput);assertTrue(observation.work>12);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().characters());
    }
    @Test void sortingCanAbortAfterProducingKeysWithoutLosingTheirWorkOrOwners(){
        var parser=new de.regelsuche.parse.ExpressionParser();
        var source=parser.parseTerm("x*y+u*v");var observation=sourceObservation(source);
        observation.wantedSortKeys=Set.of("u*v","x*y");observation.abortAtSortKeys=true;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertThrows(CoefficientLimit.class,()->new PolynomialNormalizer().normalize(source));
            assertTrue(observation.sawSortKeys);assertTrue(observation.sortCopiesOverlap);
            assertFalse(observation.missingInput);assertTrue(observation.work>12);
            observation.abortAtSortKeys=false;
            assertEquals(parser.parseTerm("u*v+x*y"),new PolynomialNormalizer().normalize(source).orElseThrow());
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().characters());
    }
    @Test void reusedVariablePowerStillPaysAndOwnsItsInputAndOptionalHandoff(){
        var source=new BinaryExpr(new VariableExpr("x"),BinaryOperator.POW,new NumberExpr(2));
        var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertSame(source,new PolynomialNormalizer().normalize(source).orElseThrow());
        }
        assertTrue(observation.work>0);assertFalse(observation.missingInput);
        assertTrue(observation.optionalEnvelope);
        assertEquals(3,observation.peakNodes,"only the original three AST nodes are needed throughout this normalization");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void failedLeftOperandDoesNotBuildTheUnusedRightPolynomial(){
        var right=new VariableExpr("unused_right");
        for(var normalizer:List.of(new PolynomialNormalizer(),PolynomialNormalizer.monomialOnly()))
            for(var operator:List.of(BinaryOperator.ADD,BinaryOperator.SUB,BinaryOperator.MUL)){
                var source=new BinaryExpr(new FunctionExpr("f",List.of(new VariableExpr("x"))),operator,right);
                var observation=sourceObservation(source);
                try(var scope=RetainedOperation.open(observation)){
                    observation.scope=scope;assertTrue(normalizer.normalize(source).isEmpty());
                }
                assertFalse(observation.normalizedVariables.contains(right.name()),"a rejected left operand makes the right conversion unnecessary");
                assertTrue(observation.work>0);assertFalse(observation.missingInput);
                assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
            }
    }
    @Test void monomialRestrictionChecksTheSimplifiedLeftBeforeBuildingTheRight(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");var right=new VariableExpr("right_operand");
        var sum=new BinaryExpr(x,BinaryOperator.ADD,y);var product=new BinaryExpr(sum,BinaryOperator.MUL,right);
        var observation=sourceObservation(product);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertTrue(PolynomialNormalizer.monomialOnly().normalize(product).isEmpty());
        }
        assertFalse(observation.normalizedVariables.contains(right.name()));
        assertTrue(new PolynomialNormalizer().normalize(product).isPresent(),"the full normalizer still expands both terms");
        var cancelled=new BinaryExpr(sum,BinaryOperator.SUB,x);
        assertEquals(Optional.of(new BinaryExpr(right,BinaryOperator.MUL,y)),
            PolynomialNormalizer.monomialOnly().normalize(new BinaryExpr(cancelled,BinaryOperator.MUL,right)),
            "the left restriction applies after cancellation, not to its unreduced AST shape");
        assertFalse(observation.missingInput);assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void singleFactorRenderingDoesNotAllocateAFactorList(){
        var source=new VariableExpr("x");var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(Optional.of(source),new PolynomialNormalizer().normalize(source));
        }
        assertEquals(0,observation.renderedFactors,"one emitted variable needs no factor list or AST fold");
        assertTrue(observation.optionalEnvelope);assertFalse(observation.missingInput);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void subtractionReusesImmutableMonomialKeysWhileNegatingOwnedCoefficients(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");
        var source=new BinaryExpr(new NumberExpr(0),BinaryOperator.SUB,new BinaryExpr(x,BinaryOperator.ADD,y));
        var expected=new BinaryExpr(new BinaryExpr(new NumberExpr(0),BinaryOperator.SUB,x),BinaryOperator.SUB,y);
        var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(Optional.of(expected),new PolynomialNormalizer().normalize(source));
        }
        assertEquals(2,observation.simultaneousPowers,"negating coefficients needs no copied variable powers or multiplication by a constant monomial");
        assertTrue(observation.work>0);assertFalse(observation.missingInput);assertTrue(observation.optionalEnvelope);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void aDistinctVariableSumReusesItsLeavesWithoutAGeneralPolynomialWorkspace(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");var z=new VariableExpr("z");
        var source=new BinaryExpr(z,BinaryOperator.ADD,new BinaryExpr(y,BinaryOperator.ADD,x));
        var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;var result=(BinaryExpr)new PolynomialNormalizer().normalize(source).orElseThrow();
            var left=(BinaryExpr)result.left();assertSame(x,left.left());assertSame(y,left.right());assertSame(z,result.right());
        }
        assertTrue(observation.peakNodes<=7,"the five input AST nodes plus two new addition nodes suffice");
        assertFalse(observation.missingInput);assertTrue(observation.optionalEnvelope);assertTrue(observation.work>0);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void anAlreadyOrderedLeftAssociatedVariableSumReusesTheWholeInputTree(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");var z=new VariableExpr("z");
        var source=new BinaryExpr(new BinaryExpr(x,BinaryOperator.ADD,y),BinaryOperator.ADD,z);
        var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertSame(source,new PolynomialNormalizer().normalize(source).orElseThrow());
        }
        assertEquals(5,observation.peakNodes,"unchanged normal form needs no new AST ancestor");
        assertFalse(observation.missingInput);assertTrue(observation.optionalEnvelope);
        assertTrue(observation.work>4);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void realPolynomialMultiplicationRetainsBothOperandsAndAccumulatingTerms(){
        var sum=new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new VariableExpr("y"));
        var normalizer=new PolynomialNormalizer();
        for(var expression:List.of(new BinaryExpr(sum,BinaryOperator.MUL,sum),new BinaryExpr(sum,BinaryOperator.POW,new NumberExpr(3)))){
            var expected=normalizer.normalize(expression);assertTrue(expected.isPresent());
            var observation=new Observation();
            try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(expected,normalizer.normalize(expression));}
            assertTrue(observation.simultaneousTerms>=3,"left/right polynomial terms and the actual accumulating map overlap");
            assertTrue(observation.work>0);assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        }
    }
    @Test void rejectedCoefficientIsObservedAndPaidBeforeItsIntermediatePolynomialIsDiscarded(){
        var expression=new BinaryExpr(new NumberExpr(2),BinaryOperator.POW,new NumberExpr(4096));
        var normalizer=new PolynomialNormalizer();assertTrue(normalizer.normalize(expression).isEmpty());
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertTrue(normalizer.normalize(expression).isEmpty());}
        assertTrue(observation.rejectedCoefficient,"the actual 4097-bit coefficient exists before the unchanged 4096-bit rejection");
        assertTrue(observation.work>4,"attempted normalization does more than opening/closing the outer scope");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void actualMonomialFactorListIsObservedDuringAstFolding(){
        var product=new BinaryExpr(new BinaryExpr(new VariableExpr("x"),BinaryOperator.MUL,new VariableExpr("y")),
            BinaryOperator.MUL,new VariableExpr("z"));
        var normalizer=new PolynomialNormalizer();var expected=normalizer.normalize(product);
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(expected,normalizer.normalize(product));
        }
        assertEquals(3,observation.renderedFactors,"the actual three rendered Expr factors survive until the AST fold finishes");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void actualOptionalResultEnvelopeOverlapsItsRenderedExpression(){
        var source=new VariableExpr("x");var normalizer=new PolynomialNormalizer();
        var expected=normalizer.normalize(source);var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(expected,normalizer.normalize(source));
        }
        assertTrue(observation.optionalEnvelope,"normalization constructs an Optional owner before handing off the Expr");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private static final class CoefficientLimit extends RuntimeException {}
    private static Observation sourceObservation(Expr root){
        var observation=new Observation();observation.inputRoot=root;
        var pending=new ArrayDeque<Expr>();pending.add(root);
        while(!pending.isEmpty()){
            var expression=pending.removeFirst();if(!observation.inputNodes.add(expression))continue;
            if(expression instanceof BinaryExpr binary){pending.add(binary.left());pending.add(binary.right());}
            else if(expression instanceof FunctionExpr function)pending.addAll(function.arguments());
        }
        return observation;
    }
    @Test void oneInputOwnerCoversRecursiveSourceVisitsWhileActualPolynomialPeaksRemainVisible(){
        var source=new BinaryExpr(new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new VariableExpr("y")),BinaryOperator.POW,new NumberExpr(3));
        var normalizer=new PolynomialNormalizer();var expected=normalizer.normalize(source);
        var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(expected,normalizer.normalize(source));}
        assertFalse(observation.missingInput,"the outer input owner covers every actual nested checkpoint");
        assertTrue(observation.simultaneousTerms>=3);assertTrue(observation.optionalEnvelope);
        assertEquals(0,observation.sourceOnlyFrames,"recursive visits do not allocate frames for already-owned immutable source nodes");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void currentInputAndOversizedCoefficientRemainOwnedWhenTheirCheckpointAborts(){
        var source=new BinaryExpr(new NumberExpr(2),BinaryOperator.POW,new NumberExpr(4096));
        var observation=sourceObservation(source);observation.abortAtCoefficient=true;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertThrows(CoefficientLimit.class,()->new PolynomialNormalizer().normalize(source));
        }
        assertTrue(observation.rejectedCoefficient);assertFalse(observation.missingInput);
        assertTrue(observation.work>4);assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        assertTrue(new PolynomialNormalizer().normalize(source).isEmpty());
    }

    @Test void completedPolynomialTakesItsExclusiveAccumulatorWithoutCopyingIt(){
        var source=new VariableExpr("x");var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(Optional.of(source),new PolynomialNormalizer().normalize(source));
        }
        assertEquals(1,observation.simultaneousTerms,"one private monomial accumulator becomes the completed polynomial's terms");
        assertFalse(observation.missingInput);assertTrue(observation.optionalEnvelope);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void finalZeroFilteringPreservesOperandMapsAndStableOutput(){
        var sum=new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new VariableExpr("y"));
        var source=new BinaryExpr(sum,BinaryOperator.SUB,sum);var normalizer=new PolynomialNormalizer();
        var before=normalizer.normalize(sum);var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(Optional.of(new NumberExpr(0)),normalizer.normalize(source));
        }
        assertTrue(observation.simultaneousTerms>=3,"separate left/right and accumulating maps still overlap");
        assertFalse(observation.missingInput);assertEquals(before,normalizer.normalize(sum));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void zeroTermIsObservedBeforeTheFinalFilterMayDiscardIt(){
        var source=new NumberExpr(0);var observation=sourceObservation(source);observation.abortAtZeroTerms=true;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertThrows(CoefficientLimit.class,()->new PolynomialNormalizer().normalize(source));
        }
        assertTrue(observation.zeroTerms);assertFalse(observation.missingInput);
        assertTrue(observation.work>4);assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        assertEquals(Optional.of(source),new PolynomialNormalizer().normalize(source));
    }

    @Test void singletonMonomialTakesItsImmutablePowersWithoutACopy(){
        var source=new VariableExpr("x");var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(Optional.of(source),new PolynomialNormalizer().normalize(source));
        }
        assertEquals(1,observation.simultaneousPowers,"the private monomial keeps its original immutable singleton powers");
        assertFalse(observation.missingInput);assertTrue(observation.optionalEnvelope);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void multipliedPowersPreserveBothOperandsAndNaturalVariableOrder(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");
        var xy=new BinaryExpr(y,BinaryOperator.MUL,x);
        var source=new BinaryExpr(xy,BinaryOperator.MUL,xy);
        var expected=new BinaryExpr(new BinaryExpr(x,BinaryOperator.POW,new NumberExpr(2)),BinaryOperator.MUL,
            new BinaryExpr(y,BinaryOperator.POW,new NumberExpr(2)));
        var normalizer=new PolynomialNormalizer();var observation=sourceObservation(source);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertEquals(Optional.of(expected),normalizer.normalize(source));
        }
        assertTrue(observation.simultaneousPowers>=3,"both operand powers and a distinct accumulation map remain owned");
        assertEquals(Optional.of(new BinaryExpr(x,BinaryOperator.MUL,y)),normalizer.normalize(xy));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

}
