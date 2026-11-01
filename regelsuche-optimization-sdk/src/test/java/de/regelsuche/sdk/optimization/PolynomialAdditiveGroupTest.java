package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.ValueArithmeticTest.*;
import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolynomialAdditiveGroupTest {
    @Test void cancelledAdditiveIntermediatesDoNotConsumeFinalNormalFormCapacity() {
        Expr retained=sum("x",200),cancelled=sum("y",200);
        Expr source=op(NumericOperation.SUBTRACT,op(NumericOperation.ADD,retained,cancelled),cancelled);
        assertTrue(proof(CancellationToken.NONE).equivalent(source,retained));
        assertFalse(proof(CancellationToken.NONE).equivalent(source,sum("x",199)));
    }
    @Test void additiveGroupingCrossesOnlyTheSameModulusAndRetainsFinalTermCap() {
        Expr q=JavaExpressions.literal(BigInteger.valueOf(101)),r=JavaExpressions.literal(BigInteger.valueOf(103));
        Expr retained=sum("x",200),cancelled=sum("y",200);
        Expr source=mod(op(NumericOperation.SUBTRACT,mod(op(NumericOperation.ADD,retained,cancelled),q),cancelled),q);
        var request=request(List.of(JavaExpressions.literal(BigInteger.ZERO)));
        assertTrue(new PolynomialProof(q,new VerificationWork(request,CancellationToken.NONE)).equivalent(source,mod(retained,q)));
        assertFalse(new PolynomialProof(q,new VerificationWork(request,CancellationToken.NONE)).equivalent(mod(retained,r),mod(retained,q)));
        assertThrows(PolynomialProof.OutsideFragment.class,() -> proof(CancellationToken.NONE).equivalent(sum("large",257),sum("large",257)));
    }
    @Test void productContributionsCancelInsideTheBoundedAdditiveGroup() {
        Expr product=mul(sum("x",17),sum("y",17)),a=new VariableExpr("a");
        Expr source=op(NumericOperation.ADD,op(NumericOperation.SUBTRACT,product,product),a);
        assertTrue(proof(CancellationToken.NONE).equivalent(source,a));
        assertThrows(PolynomialProof.OutsideFragment.class,() -> proof(CancellationToken.NONE).equivalent(product,product));
    }
    @Test void scratchContributionsWorkAndCancellationRemainBounded() {
        var values=new ArrayList<Expr>(Collections.nCopies(2049,new VariableExpr("a")));
        Expr source=balanced(values,0,values.size());
        assertThrows(PolynomialProof.OutsideFragment.class,() -> proof(CancellationToken.NONE).equivalent(source,source));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(VerificationWork.Stopped.class,() -> proof(() -> calls.incrementAndGet()>20).equivalent(sum("x",200),sum("x",200)));
        var request=request(List.of(JavaExpressions.literal(BigInteger.ZERO)));
        assertThrows(VerificationWork.Stopped.class,() -> new PolynomialProof(NumericKind.BIG_INTEGER,true,false,
            new VerificationWork(request,CancellationToken.NONE,32)).equivalent(sum("x",200),sum("x",200)));
    }
    private static PolynomialProof proof(CancellationToken token) {
        var request=request(List.of(JavaExpressions.literal(BigInteger.ZERO)));
        return new PolynomialProof(NumericKind.BIG_INTEGER,true,false,new VerificationWork(request,token));
    }
    private static Expr sum(String prefix,int count) {
        var values=new ArrayList<Expr>(); for(int i=0;i<count;i++) values.add(new VariableExpr(prefix+i));
        return balanced(values,0,values.size());
    }
    private static Expr balanced(List<Expr> values,int start,int end) {
        if(end-start==1) return values.get(start);
        int middle=(start+end)/2;
        return op(NumericOperation.ADD,balanced(values,start,middle),balanced(values,middle,end));
    }
}
