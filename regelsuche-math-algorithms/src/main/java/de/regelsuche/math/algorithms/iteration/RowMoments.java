package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;

/** Shared factorization and evaluation of monomials grouped by row slot. */
final class RowMoments {
    private RowMoments() {}

    private record Position(int slot, int column) {}

    static Map<List<Moment>, BigInteger> split(List<List<SymbolId>> slots,
            Map<IterationPolynomial.Monomial, ExactRational> terms, Work work) {
        var positions = new HashMap<SymbolId, Position>();
        for (int column = 0; column < slots.getFirst().size(); column++) {
            work.charge(1);
            for (int slot = 0; slot < slots.size(); slot++) {
                positions.put(slots.get(slot).get(column), new Position(slot, column));
            }
        }
        var factors = new LinkedHashMap<List<Moment>, BigInteger>();
        for (var term : terms.entrySet()) {
            var perSlot = new ArrayList<List<Integer>>();
            work.charge(1 + term.getKey().symbols().size());
            for (int i = 0; i < slots.size(); i++) perSlot.add(new ArrayList<>());
            for (var symbol : term.getKey().symbols()) {
                var position = positions.get(symbol);
                perSlot.get(position.slot()).add(position.column());
            }
            factors.put(perSlot.stream().map(Moment::new).toList(), term.getValue().numerator());
        }
        return factors;
    }

    static BigInteger[] values(List<BigInteger> row, int columns, Work work) {
        if (row == null || row.size() != columns) throw new Unavailable("ROW_DIMENSION");
        var values = row.toArray(BigInteger[]::new);
        for (var value : values) {
            if (value == null) throw new Unavailable("MISSING_ROW_VALUE");
            work.runtimeInteger(value);
        }
        return values;
    }

    static BigInteger product(Moment moment, BigInteger[] values, Work work) {
        BigInteger product = BigInteger.ONE;
        for (int column : moment.columns()) product = bounded(product.multiply(values[column]), work);
        return product;
    }

    static BigInteger bounded(BigInteger value, Work work) {
        work.runtimeInteger(value);
        return value;
    }
}
