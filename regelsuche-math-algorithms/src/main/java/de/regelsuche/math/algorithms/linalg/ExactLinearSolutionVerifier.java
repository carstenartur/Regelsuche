package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.*;

/** Independent, bounded solution-set checker; never validates route or execution work claims. */
public final class ExactLinearSolutionVerifier {
    public static final String WORK_SCHEMA = "regelsuche.linear-semantic-work/v1";
    public static final int MAX_RATIONAL_BITS = 16_384;
    public static final int MAX_OPERATIONS = 4_096;
    public enum Status { VERIFIED, INVALID_CERTIFICATE, DOMAIN_UNSUPPORTED, BUDGET_INCONCLUSIVE }
    public enum Stage { SOURCE_BINDING, PROOF_VALIDATION, REPLAY, RANK, CONSEQUENCE }
    public record WorkProfile(int sourceBinding, int proofValidation, int replay, int rank, int consequence) {
        public int totalWork() { return sourceBinding + proofValidation + replay + rank + consequence; }
        public String schema() { return WORK_SCHEMA; }
    }
    public record Ranks(int coefficient, int augmented) { }
    public record Verification(Status status, WorkProfile work, String detailCode, Optional<Ranks> ranks) {
        public boolean verified() { return status == Status.VERIFIED; }
    }

    public Verification verify(List<String> source, ExactLinearSolutionConsequence claim,
                               ExactLinearSolutionCertificate proof, Budget budget) {
        Objects.requireNonNull(budget);
        Meter meter = new Meter(budget.maxWorkUnits());
        try {
            meter.take(1);
            require(source != null && !source.isEmpty() && source.size() <= 16, "SOURCE_SIZE", true);
            List<Equation> equations = new ArrayList<>();
            Set<String> names = new TreeSet<>();
            ExpressionParser parser = new ExpressionParser();
            for (String text : source) {
                meter.take(1);
                require(text != null && !text.isEmpty() && text.length() <= 512, "SOURCE_TEXT", true);
                meter.take(text.length()); // Bounds and charges parser/text validation traversal.
                try {
                    LinearRepresentationPlanner.validateText(text);
                    Equation equation = parser.parseEquation(text);
                    LinearRepresentationPlanner.powerWeight(equation.left(), 0);
                    LinearRepresentationPlanner.powerWeight(equation.right(), 0);
                    collect(equation.left(), names, meter, 0);
                    collect(equation.right(), names, meter, 0);
                    equations.add(equation);
                } catch (IllegalArgumentException exception) { throw new Rejected("SOURCE_SYNTAX_OR_LIMIT", true); }
            }
            require(!names.isEmpty() && names.size() <= 16, "VARIABLE_COUNT", true);
            List<String> variables = List.copyOf(names);
            int n = variables.size(), m = equations.size();
            Rational[][] original = new Rational[m][n + 1];
            for (int i = 0; i < m; i++) {
                Rational[] left = affine(equations.get(i).left(), variables, meter);
                Rational[] right = affine(equations.get(i).right(), variables, meter);
                for (int j = 0; j <= n; j++) original[i][j] = meter.sub(left[j],right[j]);
                original[i][n] = meter.neg(original[i][n]);
            }
            meter.stage = Stage.PROOF_VALIDATION;
            meter.take(1);
            require(claim != null && proof != null, "MISSING_STATEMENT_OR_PROOF", false);
            require(ExactLinearSolutionCertificate.SCHEMA.equals(proof.schema()), "SCHEMA", false);
            require(proof.parts().size() > 0 && proof.parts().size() <= m, "PART_COUNT", false);
            require(claim.variables().size() == n, "VARIABLE_BINDING", false);
            for (int j = 0; j < n; j++) {
                meter.take(1 + variables.get(j).length());
                require(variables.get(j).equals(claim.variables().get(j)), "VARIABLE_BINDING", false);
            }
            boolean[] covered = new boolean[m];
            int count = 0;
            for (var part : proof.parts()) {
                meter.take(1);
                require(!part.sourceRows().isEmpty() && part.sourceRows().size() <= m, "ROW_MAP_SIZE", false);
                for (int row : part.sourceRows()) {
                    meter.take(1);
                    require(row >= 0 && row < m && !covered[row], "ROW_COVERAGE", false);
                    covered[row] = true;
                }
                require(part.operations().size() <= MAX_OPERATIONS - count, "OPERATION_COUNT", false);
                count += part.operations().size();
                for (var op : part.operations()) {
                    meter.take(1);
                    meter.value(op.multiplier());
                    require(op.targetRow() < part.sourceRows().size()
                        && (op.sourceRow() < 0 || op.sourceRow() < part.sourceRows().size()), "OPERATION_ROW", false);
                }
            }
            for (boolean present : covered) { meter.take(1); require(present, "ROW_COVERAGE", false); }
            meter.stage = Stage.REPLAY;
            Rational[][] reduced = new Rational[m][n + 1];
            for (int i=0;i<m;i++) for(int j=0;j<=n;j++) { meter.value(original[i][j]); reduced[i][j]=original[i][j]; }
            for (var part : proof.parts()) for (var op : part.operations()) {
                meter.take(1);
                int target = part.sourceRows().get(op.targetRow());
                int other = op.sourceRow() < 0 ? -1 : part.sourceRows().get(op.sourceRow());
                switch (op.kind()) {
                    case SWAP_ROWS -> { meter.take(1); var row = reduced[target]; reduced[target]=reduced[other]; reduced[other]=row; }
                    case SCALE_ROW -> { for(int j=0;j<=n;j++) reduced[target][j]=meter.mul(reduced[target][j],op.multiplier()); }
                    case ADD_ROW_MULTIPLE -> { for(int j=0;j<=n;j++) reduced[target][j]=meter.add(reduced[target][j],meter.mul(op.multiplier(),reduced[other][j])); }
                }
            }
            meter.stage = Stage.RANK;
            int[] pivotRows = new int[n]; Arrays.fill(pivotRows,-1);
            int rank=0; boolean inconsistent=false;
            for(int i=0;i<m;i++) {
                int pivot=-1;
                for(int j=0;j<n;j++) { meter.value(reduced[i][j]); if(pivot<0 && !reduced[i][j].isZero()) pivot=j; }
                if(pivot<0) { meter.value(reduced[i][n]); inconsistent |= !reduced[i][n].isZero(); continue; }
                require(reduced[i][pivot].isOne() && pivotRows[pivot]<0,"COEFFICIENT_NORMAL_FORM",false);
                for(int k=0;k<m;k++) { meter.value(reduced[k][pivot]); require(k==i || reduced[k][pivot].isZero(),"PIVOT_COLUMN",false); }
                pivotRows[pivot]=i; rank++;
            }
            meter.stage=Stage.CONSEQUENCE;
            meter.take(1);
            if(inconsistent) {
                require(claim.classification()==SolutionClassification.INCONSISTENT && claim.particularSolution().isEmpty()
                    && claim.nullspaceBasis().isEmpty() && claim.normalizedContradiction().isPresent(),"CLASSIFICATION",false);
                meter.value(claim.normalizedContradiction().orElseThrow());
                require(claim.normalizedContradiction().orElseThrow().isOne(),"CONTRADICTION",false);
            } else {
                int nullity=n-rank;
                require(claim.classification()==(nullity==0?SolutionClassification.UNIQUE:SolutionClassification.UNDERDETERMINED)
                    && claim.particularSolution().isPresent() && claim.nullspaceBasis().size()==nullity
                    && claim.normalizedContradiction().isEmpty(),"COMPLETE_FAMILY",false);
                int free=0;
                for(int j=0;j<n;j++) {
                    meter.take(1);
                    if(pivotRows[j]>=0) continue;
                    var value=claim.particularSolution().orElseThrow().get(j); meter.value(value);
                    require(value.isZero(),"CANONICAL_PARTICULAR",false);
                    for(int b=0;b<nullity;b++) {
                        value=claim.nullspaceBasis().get(b).get(j); meter.value(value);
                        require(value.equals(b==free?Rational.ONE:Rational.ZERO),"CANONICAL_BASIS",false);
                    }
                    free++;
                }
                residual(original,claim.particularSolution().orElseThrow(),false,meter);
                for(var vector:claim.nullspaceBasis()) residual(original,vector,true,meter);
            }
            return new Verification(Status.VERIFIED,meter.profile(),"COMPLETE_EXACT_SOLUTION_SET",Optional.of(new Ranks(rank,rank+(inconsistent?1:0))));
        } catch (Exhausted exception) {
            return new Verification(Status.BUDGET_INCONCLUSIVE,meter.profile(),"WORK_BUDGET_EXHAUSTED",Optional.empty());
        } catch (Rejected exception) {
            return new Verification(exception.domain?Status.DOMAIN_UNSUPPORTED:Status.INVALID_CERTIFICATE,meter.profile(),exception.getMessage(),Optional.empty());
        }
    }
    private static void residual(Rational[][] rows, ExactVector vector, boolean homogeneous, Meter meter) {
        for(var row:rows) {
            Rational sum=Rational.ZERO;
            for(int j=0;j<vector.dimension();j++) sum=meter.add(sum,meter.mul(row[j],vector.get(j)));
            meter.value(sum); meter.value(row[vector.dimension()]);
            require(sum.equals(homogeneous?Rational.ZERO:row[vector.dimension()]),"ORIGINAL_RESIDUAL",false);
        }
    }
    private static void collect(Expr expr, Set<String> names, Meter meter, int depth) {
        meter.take(1); require(depth<=64,"SOURCE_DEPTH",true);
        if(expr instanceof VariableExpr variable) { meter.take(variable.name().length()); names.add(variable.name()); }
        else if(expr instanceof BinaryExpr binary) { collect(binary.left(),names,meter,depth+1); collect(binary.right(),names,meter,depth+1); }
        else if(!(expr instanceof NumberExpr)) throw new Rejected("UNSUPPORTED_EXPRESSION",true);
    }
    private static Rational[] affine(Expr expr,List<String> vars,Meter meter) {
        meter.take(1); int n=vars.size(); Rational[] result=new Rational[n+1];
        for(int i=0;i<=n;i++) { meter.take(1); result[i]=Rational.ZERO; }
        if(expr instanceof NumberExpr number) { result[n]=Rational.fromExact(number.value()); meter.value(result[n]); return result; }
        if(expr instanceof VariableExpr variable) {
            for(int i=0;i<n;i++) { meter.take(1); if(vars.get(i).equals(variable.name())) result[i]=Rational.ONE; }
            return result;
        }
        if(!(expr instanceof BinaryExpr binary)) throw new Rejected("UNSUPPORTED_EXPRESSION",true);
        Rational[] a=affine(binary.left(),vars,meter), b=affine(binary.right(),vars,meter);
        boolean ac=true,bc=true;
        for(int i=0;i<n;i++) { meter.value(a[i]);meter.value(b[i]);ac &= a[i].isZero();bc &= b[i].isZero(); }
        switch(binary.operator()) {
            case ADD, SUB -> { for(int i=0;i<=n;i++) result[i]=binary.operator()==BinaryOperator.ADD?meter.add(a[i],b[i]):meter.sub(a[i],b[i]); }
            case MUL -> {
                require(ac||bc,"NONLINEAR_PRODUCT",true);
                for(int i=0;i<=n;i++) result[i]=ac?meter.mul(a[n],b[i]):meter.mul(a[i],b[n]);
            }
            case DIV -> {
                require(bc&&!b[n].isZero(),"NONCONSTANT_OR_ZERO_DIVISOR",true);
                for(int i=0;i<=n;i++) result[i]=meter.div(a[i],b[n]);
            }
            case POW -> {
                require(bc && b[n].denominator().equals(java.math.BigInteger.ONE)
                    && b[n].numerator().abs().compareTo(java.math.BigInteger.valueOf(64))<=0,"EXPONENT",true);
                int power=b[n].numerator().intValue();
                if(power==1) return a;
                require(ac,"NONLINEAR_POWER",true);
                require(!a[n].isZero()||power>0,"UNDEFINED_ZERO_POWER",true);
                Rational value=Rational.ONE;
                for(int i=0;i<Math.abs(power);i++) value=meter.mul(value,a[n]);
                result[n]=power<0?meter.div(Rational.ONE,value):value;
            }
        }
        return result;
    }
    private static void require(boolean condition,String detail,boolean domain) { if(!condition) throw new Rejected(detail,domain); }
    private static int rationalUnits(Rational value, boolean domain) {
        int bits=Math.max(value.numerator().bitLength(),value.denominator().bitLength());
        require(bits<=MAX_RATIONAL_BITS,"RATIONAL_SIZE_LIMIT",domain);
        return 1+(bits+31)/32;
    }
    private static final class Meter {
        final int limit; int used; Stage stage=Stage.SOURCE_BINDING; final int[] stages=new int[5];
        Meter(int limit) { this.limit=limit; }
        void take(int units) {
            int charged=Math.min(units,limit-used);used+=charged;stages[stage.ordinal()]+=charged;
            if(charged<units) throw new Exhausted();
        }
        void value(Rational value) { take(1);take(rationalUnits(value, stage == Stage.SOURCE_BINDING)); }
        Rational add(Rational a,Rational b) { value(a);value(b);take(1);Rational r=a.add(b);value(r);return r; }
        Rational sub(Rational a,Rational b) { value(a);value(b);take(1);Rational r=a.subtract(b);value(r);return r; }
        Rational mul(Rational a,Rational b) { value(a);value(b);take(1);Rational r=a.multiply(b);value(r);return r; }
        Rational div(Rational a,Rational b) { value(a);value(b);take(1);Rational r=a.divide(b);value(r);return r; }
        Rational neg(Rational a) { value(a);take(1);Rational r=a.negate();value(r);return r; }
        WorkProfile profile() { return new WorkProfile(stages[0],stages[1],stages[2],stages[3],stages[4]); }
    }
    private static final class Exhausted extends RuntimeException { private static final long serialVersionUID=1L; }
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID=1L; final boolean domain;
        Rejected(String message,boolean domain) { super(message);this.domain=domain; }
    }
}
