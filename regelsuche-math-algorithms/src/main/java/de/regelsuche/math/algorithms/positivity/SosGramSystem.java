package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** One coefficient equation per monomial, one column per upper triangle entry or signed multiplier term. */
final class SosGramSystem {
    private final List<SosSynthesis.Premise> blocks;
    private final CertificateWork work;
    private final int width;
    private long rowKeyCells;
    private final Map<Monomial, ExactRational[]> equations = new LinkedHashMap<>();

    private SosGramSystem(SosSynthesis.Request request, CertificateWork work) {
        this.work = work;
        blocks = new ArrayList<>();
        blocks.add(new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, SosPolynomials.ONE, request.template().basis()));
        blocks.addAll(request.template().premises());
        long columns = 0, witnessCells = 0;
        for (var block : blocks) {
            long n = block.basis().size();
            boolean gram = block.relation() == SosSynthesis.Relation.NONNEGATIVE;
            columns += gram ? n*(n+1)/2 : n;
            witnessCells += gram ? n*n : n;
            work.cells(witnessCells);
        }
        width = Math.toIntExact(columns);
        for (var term : request.target().terms().entrySet()) {
            row(term.getKey())[width] = work.check(term.getValue().exactValue());
        }
        int column = 0;
        for (var block : blocks) {
            for (int i = 0; i < block.basis().size(); i++) {
                var left = block.basis().get(i);
                if (block.relation() == SosSynthesis.Relation.ZERO) {
                    expandColumn(column++, block.polynomial(), left, false);
                } else {
                    for (int j = i; j < block.basis().size(); j++) {
                        var combined = SosPolynomials.times(left, block.basis().get(j), work);
                        expandColumn(column++, block.polynomial(), combined, i != j);
                    }
                }
            }
        }
    }

    private ExactRational[] row(Monomial monomial) {
        var existing = equations.get(monomial);
        if (existing != null) return existing;
        rowKeyCells += monomial.powers().size();
        work.cells(rowKeyCells + (equations.size()+1L)*(width+1L));
        work.charge(width+1L);
        var result = new ExactRational[width+1];
        Arrays.fill(result, ExactRational.ZERO);
        equations.put(monomial, result);
        return result;
    }

    private void expandColumn(int index, Polynomial factor, Monomial offset, boolean doubled) {
        work.charge(1);
        for (var term : factor.terms().entrySet()) {
            var value = work.check(term.getValue().exactValue());
            row(SosPolynomials.times(term.getKey(), offset, work))[index] = doubled ? work.add(value, value) : value;
        }
    }

    static Optional<SosSynthesis.Certificate> search(SosSynthesis.Request request, CertificateWork work,
            BooleanSupplier cancellation) {
        var system = new SosGramSystem(request, work);
        var values = request.template().freeValues();
        var space = new SosAffineSpace(system.equations.values(), system.width, work);
        if (!space.consistent()) return Optional.empty();
        var digits = new int[space.freeCount()];
        do {
            var entries = space.solution(values, digits);
            var certificate = system.decode(request, entries);
            if (SosVerifier.replay(request, certificate, work, cancellation).valid()) return Optional.of(certificate);
        } while (SosSynthesis.advance(digits, values.size(), work));
        return Optional.empty();
    }

    private SosSynthesis.Certificate decode(SosSynthesis.Request request, ExactRational[] entries) {
        var grams = new ArrayList<SosSynthesis.Gram>();
        var multipliers = new ArrayList<List<ExactRational>>();
        int offset = 0;
        for (var block : blocks) {
            int n = block.basis().size();
            if (block.relation() == SosSynthesis.Relation.ZERO) {
                work.charge(n);
                multipliers.add(List.of(Arrays.copyOfRange(entries, offset, offset+n)));
                offset += n;
            } else {
                work.charge((long)n*n);
                var matrix = new ExactRational[n][n];
                for (int i = 0; i < n; i++) {
                    for (int j = i; j < n; j++) matrix[i][j] = matrix[j][i] = entries[offset++];
                }
                grams.add(new SosSynthesis.Gram(Arrays.stream(matrix).map(List::of).toList()));
            }
        }
        return new SosSynthesis.Certificate(request, grams, multipliers);
    }
}
