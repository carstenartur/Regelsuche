package de.regelsuche.egraph;

import de.regelsuche.scalar.ExactRational;
import java.util.*;

/** Bounded structural observations across retained representatives. An unknown degree or cost is represented by -1. */
public record ScalarShapeFacts(List<String> exactConstants, Set<String> observedVariables, int polynomialDegreeUpper,
                               boolean hasDenominator, int smallestSyntaxNodes) {
    static final ScalarShapeFacts EMPTY = new ScalarShapeFacts(List.of(),Set.of(),-1,false,-1);
    public ScalarShapeFacts {
        exactConstants=List.copyOf(exactConstants);
        observedVariables=Collections.unmodifiableSet(new TreeSet<>(observedVariables));
    }
    boolean contradictory() { return exactConstants.size()>1; }
    ScalarShapeFacts merge(ScalarShapeFacts other) {
        var constants=new TreeSet<>(exactConstants);
        if (!contradictory()) constants.addAll(other.exactConstants);
        var symbols=new TreeSet<>(observedVariables); symbols.addAll(other.observedVariables);
        return new ScalarShapeFacts(constants.stream().limit(2).toList(),symbols,min(polynomialDegreeUpper,other.polynomialDegreeUpper),
            hasDenominator || other.hasDenominator,min(smallestSyntaxNodes,other.smallestSyntaxNodes));
    }
    private static int min(int a,int b) { return a<0?b:b<0?a:Math.min(a,b); }

    static ScalarShapeFacts make(ENode node,List<ScalarShapeFacts> children) {
        String symbol=node.symbol();
        if (symbol.startsWith("num:")) return new ScalarShapeFacts(List.of(symbol.substring(4)),Set.of(),0,false,1);
        if (symbol.startsWith("var:")) return new ScalarShapeFacts(List.of(),Set.of(symbol.substring(4)),1,false,1);
        var variables=new TreeSet<String>();
        for (var facts:children) variables.addAll(facts.observedVariables);
        boolean denominator=symbol.equals("op:DIV") || children.stream().anyMatch(ScalarShapeFacts::hasDenominator);
        int size=children.stream().anyMatch(c->c.smallestSyntaxNodes<0) ? -1 :
            (int)Math.min(Integer.MAX_VALUE, (symbol.startsWith("ac:")?children.size()-1L:1L)+children.stream().mapToLong(ScalarShapeFacts::smallestSyntaxNodes).sum());
        var constant=constant(symbol,children);
        int degree=-1;
        if (constant!=null) degree=0;
        else if (children.stream().noneMatch(c->c.polynomialDegreeUpper<0)) {
            if (symbol.equals("ac:MUL")) degree=(int)Math.min(Integer.MAX_VALUE,children.stream().mapToLong(ScalarShapeFacts::polynomialDegreeUpper).sum());
            else if (symbol.equals("ac:ADD") || symbol.equals("op:SUB")) degree=children.stream().mapToInt(ScalarShapeFacts::polynomialDegreeUpper).max().orElse(-1);
            else if (symbol.equals("op:DIV") && singleNonzero(children.get(1))) degree=children.getFirst().polynomialDegreeUpper();
        }
        return new ScalarShapeFacts(constant==null?List.of():List.of(constant),variables,degree,denominator,size);
    }
    private static boolean singleNonzero(ScalarShapeFacts value) {
        return value.exactConstants.size()==1 && !value.exactConstants.getFirst().equals("0");
    }
    private static String constant(String symbol,List<ScalarShapeFacts> children) {
        if (symbol.startsWith("fn:") || children.isEmpty() || children.stream().anyMatch(c -> c.exactConstants.size()!=1
                || c.exactConstants.getFirst().length()>512)) return null;
        var n=ExactRational.fromCanonicalText(children.getFirst().exactConstants.getFirst());
        for(int i=1;i<children.size();i++) {
            var x=ExactRational.fromCanonicalText(children.get(i).exactConstants.getFirst());
            n=switch(symbol) {
                case "ac:ADD" -> n.add(x);
                case "ac:MUL" -> n.multiply(x);
                case "op:SUB" -> n.subtract(x);
                case "op:DIV" -> x.isZero()?null:n.divide(x);
                default -> null;
            };
            if(n==null || n.numerator().bitLength()>2048 || n.denominator().bitLength()>2048) return null;
        }
        return n.canonicalText();
    }
}
