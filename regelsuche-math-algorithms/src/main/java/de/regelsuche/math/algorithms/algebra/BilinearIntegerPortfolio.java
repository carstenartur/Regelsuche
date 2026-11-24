package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Plan;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Witness;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Select a checked integer schedule by its estimated cost at the requested operand widths. */
public final class BilinearIntegerPortfolio {
    private BilinearIntegerPortfolio() {}

    public enum Completion { DICTIONARY_EXHAUSTED, SUBSET_LIMIT, CANDIDATE_LIMIT }

    public record Candidate(Request request, Witness witness, Plan schedule) {}

    public record Selection(Candidate selected, Optional<Candidate> direct,
            Optional<Candidate> preferredWithoutAllocationIncrease, long examinedSubsets,
            int verifiedCandidates, Completion completion) {
        /** Replay the selected witness against this request. */
        public Request request() { return selected.request(); }
        public boolean complete() { return completion == Completion.DICTIONARY_EXHAUSTED; }
    }

    public static Result<Selection> select(Tensor tensor, int maximumProducts, int maximumSubsets,
            int maximumCandidates, Profile profile, IterationLimits limits, BooleanSupplier cancellation) {
        if (maximumSubsets < 0 || maximumProducts < 0 || maximumCandidates < 1)
            throw new IllegalArgumentException("INVALID_PORTFOLIO_LIMIT");
        return BilinearProgram.run(limits, cancellation, work ->
            new Search(tensor, profile, maximumProducts, maximumSubsets, maximumCandidates, work).run());
    }

    private static final class Search {
        private final Tensor tensor;
        private final Profile profile;
        private final int maximumProducts;
        private final int maximumSubsets;
        private final int maximumCandidates;
        private final CertificateWork work;
        private final long sourceCells;
        private Candidate direct;
        private Candidate selected;
        private Candidate guarded;
        private int examined;
        private int accepted;

        Search(Tensor tensor, Profile profile, int maximumProducts, int maximumSubsets,
               int maximumCandidates, CertificateWork work) {
            this.tensor = tensor;
            this.profile = profile;
            this.maximumProducts = maximumProducts;
            this.maximumSubsets = maximumSubsets;
            this.maximumCandidates = maximumCandidates;
            this.work = work;
            sourceCells = BilinearProgram.matrixCells(tensor.outputs()) + profile.retainedCells();
        }

        Selection run() {
            work.cells(sourceCells);
            if (profile.leftBits().size() != tensor.leftSize() || profile.rightBits().size() != tensor.rightSize())
                throw new BilinearProgram.Rejection("PROFILE_SHAPE");
            for (var output : tensor.outputs()) {
                for (var value : output) {
                    if (!work.check(value).denominator().equals(BigInteger.ONE))
                        throw new BilinearProgram.Rejection("NON_INTEGRAL_TENSOR");
                }
            }
            var baseline = directProgram();
            if (baseline != null) {
                var request = new Request(tensor, baseline.u(), baseline.v(), maximumProducts);
                direct = evaluate(request, baseline, 0);
                accepted++;
                selected = direct;
                guarded = direct;
                if (baseline.u().isEmpty()) return finish(Completion.DICTIONARY_EXHAUSTED);
            }
            Completion capped = capped();
            if (capped != null) return finish(capped);

            long base = sourceCells + retained(null);
            int minimum = BilinearRankBound.derive(tensor, base, work).lowerBound();
            if (minimum > maximumProducts) throw new CertificateWork.Stop("PRODUCT_LIMIT_BELOW_BOUND");
            var request = BilinearFormDiscovery.generatedRequest(tensor, maximumProducts, base, true, work);
            long pairs = (long) request.leftForms().size() * request.rightForms().size();
            int dimension = tensor.outputs().getFirst().size();
            int maximum = (int) Math.min(maximumProducts, Math.min(pairs, dimension));
            for (int rank = minimum; rank <= maximum; rank++) {
                work.cells(sourceCells + formsCells(request) + retained(request) + rank);
                work.charge(rank);
                long[] subset = new long[rank];
                for (int p = 0; p < rank; p++) subset[p] = p;
                do {
                    capped = capped();
                    if (capped != null) return finish(capped);
                    work.charge(1);
                    examined++;
                    long cells = sourceCells + formsCells(request) + retained(request);
                    var candidate = BilinearProgramSearch.solve(request, subset, cells, work);
                    if (candidate != null) consider(request, candidate, rank);
                } while (BilinearProgramSearch.nextSubset(subset, pairs, work));
            }
            return finish(Completion.DICTIONARY_EXHAUSTED);
        }

        private Completion capped() {
            if (examined >= maximumSubsets) return Completion.SUBSET_LIMIT;
            return accepted >= maximumCandidates ? Completion.CANDIDATE_LIMIT : null;
        }

        private Selection finish(Completion reason) {
            if (selected == null) throw new CertificateWork.Stop(reason.name());
            return new Selection(selected, Optional.ofNullable(direct), Optional.ofNullable(guarded),
                examined, accepted, reason);
        }

        /** Every nonzero coordinate product appears exactly once, shared by all requiring it. */
        private BilinearProgram directProgram() {
            var active = new ArrayList<Integer>();
            int width = tensor.rightSize();
            for (int ij = 0; ij < tensor.outputs().getFirst().size(); ij++) {
                for (var output : tensor.outputs()) {
                    work.charge(1);
                    if (output.get(ij).isZero()) continue;
                    if (active.size() == maximumProducts) return null;
                    work.cells(sourceCells + active.size() + 1L);
                    active.add(ij);
                    break;
                }
            }
            int rank = active.size();
            long formCells = (long) rank * (2L + tensor.leftSize() + width);
            long weights = (long) tensor.outputs().size() * (1L + rank);
            long buffers = rank == 0 ? 0 : Math.max(rank, Math.max(tensor.leftSize(), width));
            work.cells(sourceCells + formCells + weights + buffers + 5L * rank + tensor.outputs().size());
            work.charge(formCells + 5L * rank + 2 * weights);
            var left = new ArrayList<List<ExactRational>>(rank);
            var right = new ArrayList<List<ExactRational>>(rank);
            for (int ij : active) {
                left.add(unit(tensor.leftSize(), ij / width));
                right.add(unit(width, ij % width));
            }
            var outputWeights = new ArrayList<List<ExactRational>>();
            for (var output : tensor.outputs()) {
                var row = new ArrayList<ExactRational>(rank);
                for (int ij : active) row.add(output.get(ij));
                outputWeights.add(List.copyOf(row));
            }
            return new BilinearProgram(left, right, outputWeights);
        }

        private List<ExactRational> unit(int size, int coordinate) {
            work.charge(size + 1L);
            var row = new ArrayList<>(Collections.nCopies(size, ExactRational.ZERO));
            row.set(coordinate, ExactRational.ONE);
            return List.copyOf(row);
        }

        private void consider(Request request, BilinearProgram program, int subsetCells) {
            // A zero output column is a dead product. Its smaller subset is also enumerated.
            for (int p = 0; p < program.u().size(); p++) {
                boolean used = false;
                for (var row : program.w()) {
                    work.charge(1);
                    if (!row.get(p).isZero()) { used = true; break; }
                }
                if (!used) return;
            }
            if (direct != null && program.u().size() == direct.witness().program().u().size()) {
                work.charge(programCells(program));
                if (program.equals(direct.witness().program())) return;
            }
            for (var matrix : List.of(program.u(), program.v(), program.w())) {
                for (var row : matrix) {
                    for (var coefficient : row) {
                        work.charge(1);
                        if (!coefficient.denominator().equals(BigInteger.ONE)) return;
                    }
                }
            }
            var candidate = evaluate(request, program, subsetCells);
            accepted++;
            if (cheaper(candidate, selected)) selected = candidate;
            if (direct != null && withinAllocation(candidate, direct) && cheaper(candidate, guarded)) guarded = candidate;
        }

        private Candidate evaluate(Request request, BilinearProgram program, int subsetCells) {
            long base = sourceCells + formsCells(request) + retained(request) + subsetCells;
            var outputCost = BilinearProgramVerifier.check(request, program, base, work);
            var schedule = BilinearIntegerSchedule.plan(program, profile, base + programCells(program), work);
            return new Candidate(request, new Witness(program, outputCost), schedule);
        }

        /** Input tensor is shared. Retain each incumbent and each distinct inactive dictionary once. */
        private long retained(Request active) {
            long cells = candidateCells(direct);
            if (direct != null && direct.request() != active) cells += formsCells(direct.request());
            if (selected != direct) cells += candidateCells(selected);
            if (guarded != direct && guarded != selected) cells += candidateCells(guarded);
            return cells;
        }

        private static long candidateCells(Candidate candidate) {
            return candidate == null ? 0 : programCells(candidate.witness().program()) + candidate.schedule().retainedCells() + 3;
        }

        private static long formsCells(Request request) {
            return BilinearProgram.matrixCells(request.leftForms()) + BilinearProgram.matrixCells(request.rightForms());
        }

        private static long programCells(BilinearProgram program) {
            return BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v())
                + BilinearProgram.matrixCells(program.w());
        }

        private static boolean cheaper(Candidate a, Candidate b) {
            return b == null || a.schedule().cost().compareTo(b.schedule().cost()) < 0;
        }

        private static boolean withinAllocation(Candidate a, Candidate baseline) {
            var cost = a.schedule().cost();
            var directCost = baseline.schedule().cost();
            return cost.allocatedWords() <= directCost.allocatedWords()
                && cost.allocatedObjects() <= directCost.allocatedObjects();
        }
    }
}
