package de.regelsuche.polynomial;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Differential controls retain the pre-optimization division and its work trace. */
final class DivisionEvidenceOptimizationChecks {
    private static final String HASH = "sha256:" + "a".repeat(64);
    private DivisionEvidenceOptimizationChecks() { }

    static void divisionReusesStageLabels() {
        var q = ring(ExactRationalField.INSTANCE);
        var source = powerMinusOne(q, 24);
        var divisor = view(q, -1, 1);
        Map<String, String> labels = new HashMap<>();
        source.divideAndRemainder(divisor, ExactRationalField.INSTANCE, (stage, units) -> {
            String first = labels.putIfAbsent(stage, stage);
            require(first == null || first == stage, "division rebuilt an unchanged stage label");
        }, "division\uD83D\uDE80");
        require(labels.size() == 3, "all three work stages must remain present");
    }

    static void divisionDoesNotRescanTheKnownZeroSuffix() {
        var field = new CountingField<>(ExactRationalField.INSTANCE);
        var q = ring(field);
        var source = powerMinusOne(q, 48);
        var divisor = view(q, -1, 1);
        field.zeroChecks = 0;
        var result = source.divideAndRemainder(divisor, field);
        require(result.remainder().isZero() && result.quotient().degree() == 47, "geometric quotient");
        require(field.zeroChecks < 250, "quadratic rescanning of known zeros: " + field.zeroChecks);
    }

    static void divisionMatchesEveryBudgetPrefix() {
        Random random = new Random(20260913L);
        for (int p : new int[] {2, 7, 101, Integer.MAX_VALUE}) {
            var field = PrimeField.of(p);
            for (int n = 0; n < 16; n++) {
                var r = ring(field);
                var a = randomView(r, random, 2 + random.nextInt(9));
                var b = randomView(r, random, 1 + random.nextInt(4));
                if (b.isZero()) b = view(r, 1);
                compareAllBudgets(a, b, field, n % 2 == 0 ? null : "\u0000 division\uD83D\uDE80");
            }
        }
        var q = ring(ExactRationalField.INSTANCE);
        for (int n = 0; n < 16; n++) {
            var a = randomRational(q, random, 2 + random.nextInt(8));
            var b = randomRational(q, random, 1 + random.nextInt(4));
            if (b.isZero()) b = view(q, 1);
            compareAllBudgets(a, b, ExactRationalField.INSTANCE, n % 2 == 0 ? "" : "  ");
        }
        compareAllBudgets(powerMinusOne(q, 17), view(q, -1, 1), ExactRationalField.INSTANCE, "gap");
        compareAllBudgets(view(q, 1), view(q, 1, 1), ExactRationalField.INSTANCE, "early");
        compareAllBudgets(UnivariatePolynomialView.zero(q), view(q, 1), ExactRationalField.INSTANCE, "zero");
    }

    static void arbitraryFieldsKeepTheirDivisionAndFailureOrder() {
        var field = new CountingField<>(PrimeField.of(101));
        var r = ring(field);
        var a = powerMinusOne(r, 12);
        var b = view(r, -2, 2);
        field.divisions = 0;
        a.divideAndRemainder(b, field);
        require(field.divisions == 12, "custom field must retain its own division contract");
        field.divisions = 0;
        rejects(BudgetEnd.class, () -> a.divideAndRemainder(b, field, new Trace(1), "d"));
        require(field.divisions == 0, "no inverse or division before coefficient charge is admitted");
        rejects(ArithmeticException.class, () -> a.divideAndRemainder(UnivariatePolynomialView.zero(r), field, null, null));
        rejects(NullPointerException.class, () -> a.divideAndRemainder(null, field, null, null));
        rejects(NullPointerException.class, () -> a.divideAndRemainder(b, null, null, null));
        rejects(NullPointerException.class, () -> a.divideAndRemainder(b, field, null, null));
        var other = ring(PrimeField.of(103));
        rejects(IllegalArgumentException.class, () -> a.divideAndRemainder(view(other, 1), field));
        require(UnivariatePolynomialView.zero(r).divideAndRemainder(b, field, null, null).remainder().isZero(),
            "zero input must retain no-work early return");
    }

    static void largePrimeInputsAndRepeatedDivisionRemainExact() {
        for (int p : new int[] {2, 101, Integer.MAX_VALUE}) {
            var field = PrimeField.of(p);
            var r = ring(field);
            var big = BigInteger.ONE.shiftLeft(4096).add(BigInteger.valueOf(17));
            var a = UnivariatePolynomialView.of(r, List.of(big.negate(), big, BigInteger.ONE, big.add(BigInteger.TWO)));
            var b = view(r, p == 2 ? 1 : -2, p == 2 ? 1 : 2);
            compareAllBudgets(a, b, field, "large");
            var other = view(r, 1, p == 2 ? 1 : 3);
            compareAllBudgets(a, other, field, "other-divisor");
            var result = a.divideAndRemainder(b, field);
            require(result.quotient().multiply(b).add(result.remainder()).equals(a), "exact product identity");
        }
    }

    static void factorKeysAreSerializedOnce() {
        var domain = new CountingField<>(ExactRationalField.INSTANCE);
        var r = ring(domain);
        List<PolynomialFactor<ExactRational>> factors = new ArrayList<>();
        for (int k : new int[] {9, 2, 7, 4, 1, 8, 3, 6, 5}) factors.add(new PolynomialFactor<>(view(r, k, 1).toSparsePolynomial(), 1));
        long expected = factors.stream().mapToLong(f -> {domain.textCalls = 0; f.polynomial().canonicalMaterial(); return domain.textCalls;}).sum();
        domain.textCalls = 0;
        new FactorizationEngine.Proposal<>(ExactRational.ONE, factors, SparsePolynomial.one(r), HASH);
        require(domain.textCalls == expected, "factor sort rebuilt canonical keys: " + domain.textCalls + " vs " + expected);
    }

    static void proposalKeysAreSerializedOnce() {
        var domain = new CountingField<>(ExactRationalField.INSTANCE);
        var r = ring(domain);
        List<FactorizationEngine.Proposal<ExactRational>> proposals = new ArrayList<>();
        for (int k : new int[] {9, 2, 7, 4, 1, 8, 3, 6, 5}) proposals.add(new FactorizationEngine.Proposal<>(
            ExactRational.ONE, List.of(new PolynomialFactor<>(view(r, k, 1).toSparsePolynomial(), 1)), SparsePolynomial.one(r), HASH));
        proposals.add(proposals.getFirst());
        long expected = proposals.stream().mapToLong(p -> {domain.textCalls = 0; p.canonicalMaterial(); return domain.textCalls;}).sum();
        domain.textCalls = 0;
        var result = engineResult(proposals);
        require(domain.textCalls == expected, "proposal ordering rebuilt canonical keys: " + domain.textCalls + " vs " + expected);
        require(result.proposals().size() == 9, "duplicate proposals remain deduplicated");
    }

    static void orderingMergingAndFirstDuplicateAreUnchanged() {
        var r = ring(ExactRationalField.INSTANCE);
        var a = view(r, 10, 1).toSparsePolynomial();
        var b = view(r, -2, 1).toSparsePolynomial();
        var one = SparsePolynomial.one(r);
        var input = List.of(new PolynomialFactor<>(a, 3), new PolynomialFactor<>(b, 2), new PolynomialFactor<>(a, 1));
        var ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparing((PolynomialFactor<ExactRational> f) -> f.polynomial().canonicalMaterial()).thenComparingInt(PolynomialFactor::multiplicity));
        List<PolynomialFactor<ExactRational>> expected = new ArrayList<>();
        for (var f : ordered) {
            if (!expected.isEmpty() && expected.getLast().polynomial().equals(f.polynomial())) {
                var previous = expected.removeLast();
                expected.add(new PolynomialFactor<>(f.polynomial(), Math.addExact(previous.multiplicity(), f.multiplicity())));
            } else expected.add(f);
        }
        var first = new FactorizationEngine.Proposal<>(ExactRational.ONE, input, one, HASH);
        require(first.factors().equals(expected), "factor ordering/merging");
        var equal = new FactorizationEngine.Proposal<>(ExactRational.ONE, input, one, HASH);
        var other = new FactorizationEngine.Proposal<>(ExactRational.NEGATIVE_ONE, input, one, HASH);
        var proposals = List.of(other, first, first, equal);
        var legacy = new java.util.LinkedHashMap<String, FactorizationEngine.Proposal<ExactRational>>();
        proposals.stream().sorted(Comparator.comparing(FactorizationEngine.Proposal::canonicalMaterial))
            .forEach(p -> legacy.putIfAbsent(p.canonicalMaterial(), p));
        var actual = engineResult(proposals);
        require(actual.proposals().equals(List.copyOf(legacy.values())), "proposal order/deduplication");
        for (var p : actual.proposals()) if (p.equals(first)) require(p == first, "first duplicate identity");
        rejects(UnsupportedOperationException.class, () -> actual.proposals().clear());
        rejects(ArithmeticException.class, () -> new FactorizationEngine.Proposal<>(ExactRational.ONE,
            List.of(new PolynomialFactor<>(a, Integer.MAX_VALUE), new PolynomialFactor<>(a, 1)), one, HASH));
        rejects(IllegalArgumentException.class, () -> new FactorizationEngine.Proposal<>(ExactRational.ONE, input, one, "bad"));
    }

    private static <C> FactorizationEngine.EngineResult<C> engineResult(List<FactorizationEngine.Proposal<C>> proposals) {
        return new FactorizationEngine.EngineResult<>("test.order/v1", FactorizationEngine.Outcome.CANDIDATES,
            "CANDIDATES", PolynomialWorkLedger.empty(), proposals, FactorizationEngine.BackendClaim.NONE, HASH);
    }

    private static <C> void compareAllBudgets(UnivariatePolynomialView<C> a, UnivariatePolynomialView<C> b, ExactField<C> field, String stage) {
        var complete = new Trace(Long.MAX_VALUE);
        legacyDivision(a, b, field, complete, stage);
        for (long budget = 0; budget <= complete.total + 1; budget++) {
            var oldTrace = new Trace(budget); var newTrace = new Trace(budget);
            String expected = outcome(() -> legacyDivision(a, b, field, oldTrace, stage));
            String actual = outcome(() -> a.divideAndRemainder(b, field, newTrace, stage));
            require(actual.equals(expected), "division/result/budget mismatch at " + budget + ": " + actual + " != " + expected);
            require(oldTrace.entries.equals(newTrace.entries), "changed budget prefix at " + budget);
            require(oldTrace.total == newTrace.total, "changed work total");
        }
    }

    /** Intentionally preserves the old full suffix scan and per-iteration field division. */
    private static <C> UnivariatePolynomialView.DivisionResult<C> legacyDivision(UnivariatePolynomialView<C> a,
            UnivariatePolynomialView<C> divisor, ExactField<C> field, Trace work, String stage) {
        var ring = a.ring(); var domain = ring.coefficientDomain();
        if (divisor.isZero()) throw new ArithmeticException("polynomial division by zero");
        if (a.isZero() || a.degree() < divisor.degree()) return new UnivariatePolynomialView.DivisionResult<>(UnivariatePolynomialView.zero(ring), a);
        var quotient = new ArrayList<C>(); for (int i=0;i<=a.degree()-divisor.degree();i++) quotient.add(domain.zero());
        var remainder = new ArrayList<>(a.coefficients());
        int degree = oldDegree(remainder, domain);
        while (degree >= divisor.degree()) {
            work.consume(stage + ".iterations", 1);
            int shift = degree - divisor.degree();
            work.consume(stage + ".coefficient-divisions", 1);
            C scale = field.divide(remainder.get(degree), divisor.leadingCoefficient());
            quotient.set(shift, domain.add(quotient.get(shift), scale));
            for (int exponent=0; exponent<=divisor.degree(); exponent++) {
                work.consume(stage + ".coefficient-updates", 2);
                int index = exponent + shift;
                remainder.set(index, domain.subtract(remainder.get(index), domain.multiply(scale, divisor.coefficient(exponent))));
            }
            degree = oldDegree(remainder, domain);
        }
        return new UnivariatePolynomialView.DivisionResult<>(UnivariatePolynomialView.of(ring, quotient), UnivariatePolynomialView.of(ring, remainder));
    }
    private static <C> int oldDegree(List<C> coefficients, CoefficientDomain<C> domain) {
        int degree = coefficients.size()-1; while (degree>=0 && domain.isZero(coefficients.get(degree))) degree--; return degree;
    }
    private interface Operation { UnivariatePolynomialView.DivisionResult<?> run(); }
    private static String outcome(Operation operation) {
        try { var r=operation.run(); return r.quotient().canonicalMaterial()+";"+r.remainder().canonicalMaterial(); }
        catch (BudgetEnd end) { return "BUDGET"; }
    }
    private record Entry(String stage, long units) { }
    private static final class Trace implements PolynomialWorkSink {
        final long limit; long total; final List<Entry> entries=new ArrayList<>();
        Trace(long limit) {this.limit=limit;}
        public void consume(String stage,long units) {if (total>limit-units) throw new BudgetEnd(); total+=units; entries.add(new Entry(stage,units));}
    }
    private static final class BudgetEnd extends RuntimeException { private static final long serialVersionUID=1L; }
    private static <C> PolynomialRing<C> ring(CoefficientDomain<C> domain) {
        return new PolynomialRing<>(domain,List.of(new PolynomialVariable("x")),PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }
    private static <C> UnivariatePolynomialView<C> view(PolynomialRing<C> ring,long... coefficients) {
        return UnivariatePolynomialView.of(ring,java.util.Arrays.stream(coefficients).mapToObj(v->ring.coefficientDomain().fromInteger(BigInteger.valueOf(v))).toList());
    }
    private static <C> UnivariatePolynomialView<C> powerMinusOne(PolynomialRing<C> r,int degree) {
        var values = new ArrayList<C>(); values.add(r.coefficientDomain().fromInteger(BigInteger.ONE.negate()));
        for (int i=1;i<degree;i++) values.add(r.coefficientDomain().zero()); values.add(r.coefficientDomain().one());
        return UnivariatePolynomialView.of(r,values);
    }
    private static <C> UnivariatePolynomialView<C> randomView(PolynomialRing<C> r,Random random,int n) {
        var values = new ArrayList<C>(); for(int i=0;i<n;i++) values.add(r.coefficientDomain().fromInteger(BigInteger.valueOf(random.nextInt(19)-9)));
        return UnivariatePolynomialView.of(r,values);
    }
    private static UnivariatePolynomialView<ExactRational> randomRational(PolynomialRing<ExactRational> r,Random random,int n) {
        var values=new ArrayList<ExactRational>();for(int i=0;i<n;i++) values.add(new ExactRational(BigInteger.valueOf(random.nextInt(19)-9),BigInteger.valueOf(1+random.nextInt(9))));
        return UnivariatePolynomialView.of(r,values);
    }
    private static final class CountingField<C> implements ExactField<C> {
        final ExactField<C> delegate; long textCalls,zeroChecks,divisions;
        CountingField(ExactField<C> delegate){this.delegate=delegate;}
        public String id(){return delegate.id();} public BigInteger characteristic(){return delegate.characteristic();}
        public C fromInteger(BigInteger v){return delegate.fromInteger(v);} public C zero(){return delegate.zero();} public C one(){return delegate.one();}
        public C canonical(C v){return delegate.canonical(v);} public C add(C a,C b){return delegate.add(a,b);}
        public C negate(C a){return delegate.negate(a);} public C multiply(C a,C b){return delegate.multiply(a,b);}
        public boolean isZero(C v){zeroChecks++;return delegate.isZero(v);} public String canonicalText(C v){textCalls++;return delegate.canonicalText(v);}
        public int bitLength(C v){return delegate.bitLength(v);} public C divide(C a,C b){divisions++;return delegate.divide(a,b);}
    }
    private static void rejects(Class<? extends Throwable> type,Runnable action){try{action.run();}catch(Throwable t){if(type.isInstance(t))return;throw new AssertionError(t);}throw new AssertionError("expected "+type);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        String[] names={"labels","bounded-scan","budget-parity","generic-field","large-prime","factor-keys","proposal-keys","ordering"};
        Runnable[] groups={DivisionEvidenceOptimizationChecks::divisionReusesStageLabels,DivisionEvidenceOptimizationChecks::divisionDoesNotRescanTheKnownZeroSuffix,
            DivisionEvidenceOptimizationChecks::divisionMatchesEveryBudgetPrefix,DivisionEvidenceOptimizationChecks::arbitraryFieldsKeepTheirDivisionAndFailureOrder,
            DivisionEvidenceOptimizationChecks::largePrimeInputsAndRepeatedDivisionRemainExact,DivisionEvidenceOptimizationChecks::factorKeysAreSerializedOnce,
            DivisionEvidenceOptimizationChecks::proposalKeysAreSerializedOnce,DivisionEvidenceOptimizationChecks::orderingMergingAndFirstDuplicateAreUnchanged};
        int failed=0;
        for(int i=0;i<groups.length;i++){try{groups[i].run();System.out.println("PASS "+names[i]);}catch(Throwable t){failed++;System.out.println("FAIL "+names[i]+": "+t);}}
        if(failed>0)throw new AssertionError(failed+" groups failed");
    }
}
