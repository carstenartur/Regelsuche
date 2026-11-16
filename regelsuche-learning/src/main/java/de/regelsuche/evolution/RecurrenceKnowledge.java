package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily.Anchor;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily.VerifiedFamily;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.VerifiedLaw;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed payload/execution adapter of CumulativeTaskKnowledge; no separate search or learning kernel. */
public final class RecurrenceKnowledge {
    static final int MAX_ENTRIES = 32;
    private RecurrenceKnowledge() { }
    public enum Outcome { VERIFIED, NO_LAW_IN_ANSATZ, UNSUPPORTED, BUDGET_INCONCLUSIVE }

    /** Success means one nonconstant law, never completeness of a stored invariant basis. */
    public record Execution(String generationId, CumulativeTaskKnowledge.Strategy selectedStrategy,
            Outcome outcome, Optional<VerifiedLaw> law, long totalWork, boolean reused) {
        public Execution {
            Objects.requireNonNull(generationId); Objects.requireNonNull(selectedStrategy);
            Objects.requireNonNull(outcome); Objects.requireNonNull(law);
            if (totalWork < 0 || (outcome == Outcome.VERIFIED) != law.isPresent() || reused && law.isEmpty())
                throw new IllegalArgumentException("invalid recurrence execution");
        }
        public boolean solved() { return law.isPresent(); }
    }
    public record Validation(Request task, Execution previous, Execution candidate) { }
    public record Update(CumulativeTaskKnowledge next, CumulativeTaskKnowledge.Decision reason,
            List<Execution> training, List<Validation> validation, CumulativeTaskKnowledge.Costs work) {
        public Update { training = List.copyOf(training); validation = List.copyOf(validation); }
        public boolean promoted() { return reason == CumulativeTaskKnowledge.Decision.PROMOTED; }
    }
    /** Proposals define the snapshot identity. The prepared proof is issued again on loading. */
    record Entry(Transition transition, int degree, Proposal proposal, Optional<VerifiedFamily> family,
            Optional<VerifiedLaw> anchorProof) {
        Entry(Transition transition, int degree, Proposal proposal) {
            this(transition, degree, proposal, Optional.empty(), Optional.empty());
        }
        Entry {
            Objects.requireNonNull(transition); Objects.requireNonNull(proposal); Objects.requireNonNull(family);
            Objects.requireNonNull(anchorProof);
            if (degree < 1 || degree > 2) throw new IllegalArgumentException("invalid recurrence degree");
            if (family.isPresent() && (family.get().degree()!=degree
                    || !family.get().first().equals(new Anchor(transition,proposal))))
                throw new IllegalArgumentException("family anchor mismatch");
            anchorProof.ifPresent(law -> {
                if (family.isPresent() || !law.certifies(transition, proposal) || law.degree() > degree) {
                    throw new IllegalArgumentException("retained proof does not match its anchor");
                }
            });
        }
        static Entry fromFamily(VerifiedFamily family) {
            return new Entry(family.first().transition(),family.degree(),family.first().proposal(),
                Optional.of(family),Optional.empty());
        }
        Entry retain(VerifiedLaw law) {
            return new Entry(transition, degree, proposal, family, Optional.of(law));
        }
        boolean applies(Request task) {
            return degree <= task.degree() && (family.isEmpty() ? transition.equals(task.transition()) : family.get().matches(task.transition()));
        }
        @Override public boolean equals(Object other) {
            return other instanceof Entry entry && degree == entry.degree && transition.equals(entry.transition)
                && proposal.equals(entry.proposal) && family.equals(entry.family);
        }
        @Override public int hashCode() {
            return Objects.hash(transition, degree, proposal, family);
        }
    }

    private record Resolution(Execution execution, Entry anchor) {}

    static Execution execute(String id, List<Entry> catalog, Request task, CumulativeTaskKnowledge.Strategy strategy) {
        return resolve(id, catalog, task, strategy).execution();
    }

    private static Resolution resolve(String id, List<Entry> catalog, Request task, CumulativeTaskKnowledge.Strategy strategy) {
        Objects.requireNonNull(task); Objects.requireNonNull(strategy);
        var paid = new Meter(task.budget().maxWorkUnits());
        try {
            if (strategy != CumulativeTaskKnowledge.Strategy.BASELINE) {
                var reused = reuse(id, catalog, task, paid);
                if (reused.isPresent()) return reused.orElseThrow();
            }
            var result = new RecurrenceInvariantSynthesis().solveFirst(new Request(task.transition(), task.initialState(),
                task.degree(), new Budget(paid.remaining())));
            paid.add(result.work().consumedWorkUnits());
            return new Resolution(receipt(id, outcome(result), result.law(), paid, false), null);
        } catch (Limit exhausted) {
            return new Resolution(receipt(id, Outcome.BUDGET_INCONCLUSIVE, Optional.empty(), paid, false), null);
        }
    }

    private static Optional<Resolution> reuse(String id, List<Entry> catalog, Request task, Meter paid) {
        var verifier = new RecurrenceInvariantVerifier();
        int dimension = task.transition().linear().columns();
        for (var entry : catalog) {
            paid.add(2L + dimension * dimension);
            if (!entry.applies(task)) continue;
            var law = pay(verifyEntry(verifier, entry, task, new Budget(paid.remaining())), paid);
            if (law.isPresent()) return Optional.of(new Resolution(receipt(id, Outcome.VERIFIED, law, paid, true), entry));
        }
        for (var entry : catalog) {
            paid.add(1);
            if (entry.degree() > task.degree() || entry.anchorProof().isEmpty()) continue;
            var law = pay(verifier.transport(entry.anchorProof().orElseThrow(), task.transition(),
                task.initialState(), new Budget(paid.remaining())), paid);
            if (law.isPresent()) return Optional.of(new Resolution(receipt(id, Outcome.VERIFIED, law, paid, true), entry));
        }
        return Optional.empty();
    }

    private static Optional<VerifiedLaw> pay(RecurrenceInvariantVerifier.Verification proof, Meter meter) {
        meter.add(proof.work().consumedWorkUnits());
        if (proof.outcome() == RecurrenceInvariantVerifier.Outcome.BUDGET_INCONCLUSIVE) throw new Limit();
        return proof.law();
    }

    private static RecurrenceInvariantVerifier.Verification verifyEntry(RecurrenceInvariantVerifier verifier,
            Entry entry, Request task, Budget budget) {
        if (entry.family().isPresent())
            return verifier.verifyFamily(entry.family().orElseThrow(), task.transition(), task.initialState(), budget);
        if (entry.anchorProof().isPresent())
            return verifier.transport(entry.anchorProof().orElseThrow(), task.transition(), task.initialState(), budget);
        return verifier.verify(task.transition(), entry.proposal(), task.initialState(), budget);
    }

    /** One bounded, symbolically proved family per generation; validation never enters its formation. */
    static Update generalize(CumulativeTaskKnowledge owner, List<Entry> catalog, List<Request> validationInput, Budget budget) {
        var validation=bounded(validationInput);
        var progress=new Progress(budget.maxWorkUnits());
        var exact=catalog.stream().filter(e -> e.family().isEmpty()).toList();
        try {
            var next=generalizePairs(owner.id(),catalog,exact,validation,progress);
            return finish(owner,next.orElse(catalog),next.isPresent() ? CumulativeTaskKnowledge.Decision.PROMOTED
                : CumulativeTaskKnowledge.Decision.NO_VALIDATED_BENEFIT,progress);
        } catch (Limit exhausted) {
            return finish(owner,catalog,CumulativeTaskKnowledge.Decision.ACQUISITION_LIMIT,progress);
        } catch (RuntimeException failure) {
            throw new LearningFailure(progress.costs(),progress.training,failure);
        }
    }
    private static Optional<List<Entry>> generalizePairs(String id, List<Entry> catalog, List<Entry> exact,
            List<Request> validation, Progress progress) {
        for (int i=0;i<exact.size();i++) for (int j=i+1;j<exact.size();j++) {
            var result=generalizePair(id,catalog,exact.get(i),exact.get(j),validation,progress);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
    private static Optional<List<Entry>> generalizePair(String id, List<Entry> catalog, Entry a, Entry b,
            List<Request> validation, Progress progress) {
        progress.meter.add(1);
        if (a.degree()!=b.degree() || alreadyCovered(catalog,a,b,progress.meter)) return Optional.empty();
        var formed=new RecurrenceInvariantFamily().form(new Anchor(a.transition(),a.proposal()),
            new Anchor(b.transition(),b.proposal()),a.degree(),new Budget(progress.meter.remaining()));
        progress.meter.add(formed.work().consumedWorkUnits());
        if (formed.outcome()==RecurrenceInvariantVerifier.Outcome.BUDGET_INCONCLUSIVE) throw new Limit();
        if (formed.family().isEmpty()) return Optional.empty();
        var entry=Entry.fromFamily(formed.family().get());
        if (!hasUnseenValidation(entry,catalog,validation,progress.meter)) return Optional.empty();
        var candidate=new ArrayList<>(catalog);
        addCandidate(candidate,entry,progress.meter);
        return validate(id,catalog,candidate,List.of(entry),validation,progress) ? Optional.of(candidate) : Optional.empty();
    }
    private static boolean alreadyCovered(List<Entry> catalog, Entry a, Entry b, Meter work) {
        for (var entry : catalog) {
            work.add(1);
            if (entry.degree()>a.degree() || entry.family().isEmpty()) continue;
            int dimension=a.transition().linear().columns();
            work.add(2L*dimension*(dimension+1));
            if (entry.family().get().matches(a.transition()) && entry.family().get().matches(b.transition())) return true;
        }
        return false;
    }
    private static boolean hasUnseenValidation(Entry candidate, List<Entry> catalog, List<Request> tasks, Meter work) {
        for (var task : tasks) {
            work.add(1L+catalog.size());
            if (candidate.applies(task) && catalog.stream().noneMatch(e -> e.applies(task))) return true;
        }
        return false;
    }

    private static Outcome outcome(RecurrenceInvariantSynthesis.FirstLaw result) {
        return switch (result.status()) {
            case BUDGET_INCONCLUSIVE -> Outcome.BUDGET_INCONCLUSIVE;
            case UNSUPPORTED -> Outcome.UNSUPPORTED;
            case COMPLETE -> result.law().isEmpty() ? Outcome.NO_LAW_IN_ANSATZ : Outcome.VERIFIED;
        };
    }
    private static Execution receipt(String id, Outcome outcome, Optional<VerifiedLaw> law, Meter work, boolean reused) {
        return new Execution(id, reused ? CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST : CumulativeTaskKnowledge.Strategy.BASELINE,
            outcome, law, work.used, reused);
    }

    /** Each added proof has to be selected on a separate validation request. */
    static Update learn(CumulativeTaskKnowledge owner, List<Entry> catalog, List<Request> lessonInput,
            List<Request> validationInput, Budget budget) {
        var lessons = bounded(lessonInput); var validation = bounded(validationInput);
        var progress = new Progress(budget.maxWorkUnits());
        var candidate = new ArrayList<>(catalog);
        try {
            requireDisjoint(lessons, validation, progress.meter);
            progress.split = progress.meter.used;
            learnLessons(owner.id(), catalog, candidate, lessons, progress);
            if (candidate.equals(catalog)) return finish(owner, catalog, CumulativeTaskKnowledge.Decision.NO_NEW_LAW, progress);
            boolean benefit = validate(owner.id(), catalog, candidate, candidate.subList(catalog.size(), candidate.size()),
                validation, progress);
            return finish(owner, benefit ? candidate : catalog, benefit ? CumulativeTaskKnowledge.Decision.PROMOTED
                : CumulativeTaskKnowledge.Decision.NO_VALIDATED_BENEFIT, progress);
        } catch (Limit exhausted) {
            return finish(owner, catalog, CumulativeTaskKnowledge.Decision.ACQUISITION_LIMIT, progress);
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException invalid && progress.training.isEmpty()) throw invalid;
            throw new LearningFailure(progress.costs(), progress.training, failure);
        }
    }
    private static void learnLessons(String id, List<Entry> parent, List<Entry> candidate,
            List<Request> lessons, Progress progress) {
        for (var task : lessons) {
            var run = runPaid(id, parent, task, progress.meter).execution();
            progress.training.add(run); progress.lessons += run.totalWork();
            if (progress.meter.remaining() == 0 && !run.solved()) throw new Limit();
            if (!run.solved() || run.reused()) continue;
            var law = run.law().orElseThrow();
            var entry = new Entry(task.transition(), task.degree(), law.proposal()).retain(law);
            if (candidate.contains(entry)) continue;
            addCandidate(candidate, entry, progress.meter);
        }
    }
    private static void addCandidate(List<Entry> candidate, Entry entry, Meter work) {
        if (candidate.size() >= MAX_ENTRIES) throw new Limit();
        candidate.add(entry);
        if (!RecurrenceKnowledgeCodec.fitsPayload(candidate, work::add)) throw new Limit();
    }
    private static boolean validate(String id, List<Entry> parent, List<Entry> candidate,
            List<Entry> added, List<Request> tasks, Progress progress) {
        long oldWork = 0, newWork = 0;
        boolean safe = true, moreSolved = false;
        progress.meter.add(added.size());
        var uncovered = new ArrayList<>(added);
        for (var task : tasks) {
            var before = runPaid(id, parent, task, progress.meter).execution();
            progress.validationWork += before.totalWork();
            var selected = runPaid(id, candidate, task, progress.meter);
            var after = selected.execution();
            progress.validationWork += after.totalWork();
            progress.validation.add(new Validation(task, before, after));
            if (progress.meter.remaining() == 0 && !after.solved()) throw new Limit();
            progress.meter.add(uncovered.size());
            uncovered.remove(selected.anchor());
            safe &= !before.solved() || after.solved();
            moreSolved |= !before.solved() && after.solved();
            oldWork += before.totalWork(); newWork += after.totalWork();
        }
        return uncovered.isEmpty() && safe && (moreSolved || newWork < oldWork);
    }
    private static Resolution runPaid(String id, List<Entry> catalog, Request task, Meter acquisition) {
        if (acquisition.remaining() == 0) throw new Limit();
        var bounded = new Request(task.transition(), task.initialState(), task.degree(),
            new Budget(Math.min(task.budget().maxWorkUnits(), acquisition.remaining())));
        var run = resolve(id, catalog, bounded, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        acquisition.add(run.execution().totalWork());
        return run;
    }
    private static Update finish(CumulativeTaskKnowledge owner, List<Entry> catalog,
            CumulativeTaskKnowledge.Decision decision, Progress progress) {
        // A cutoff after a partial query retains the meter's paid prefix, not only completed receipts.
        var costs = progress.costs();
        var next = owner.withRecurrences(catalog, decision, costs);
        return new Update(next, decision, progress.training, progress.validation, costs);
    }
    private static List<Request> bounded(List<Request> input) {
        var tasks = List.copyOf(input);
        if (tasks.isEmpty() || tasks.size() > 32) throw new IllegalArgumentException("one to 32 recurrence tasks required");
        return tasks;
    }
    private static void requireDisjoint(List<Request> lessons, List<Request> validation, Meter work) {
        for (var a : lessons) for (var b : validation) {
            work.add(1);
            if (a.transition().equals(b.transition()) && a.initialState().equals(b.initialState()) && a.degree() == b.degree())
                throw new IllegalArgumentException("lesson/validation task overlap");
        }
    }
    private static final class Progress {
        final Meter meter;
        final List<Execution> training = new ArrayList<>();
        final List<Validation> validation = new ArrayList<>();
        long lessons, validationWork, split;
        Progress(int budget) { meter = new Meter(budget); }
        CumulativeTaskKnowledge.Costs costs() {
            return new CumulativeTaskKnowledge.Costs(lessons, meter.used - lessons - validationWork - split,
                0, validationWork, 0, 0, split);
        }
    }
    /** Unexpected technical failure retains known work and cannot authorize promotion. */
    public static final class LearningFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final CumulativeTaskKnowledge.Costs knownWork;
        private final List<Execution> completed;
        private LearningFailure(CumulativeTaskKnowledge.Costs work, List<Execution> completed, RuntimeException cause) {
            super("recurrence learning failed; unfinished operation work is unknown", cause);
            this.knownWork = work; this.completed = List.copyOf(completed);
        }
        public CumulativeTaskKnowledge.Costs knownWork() { return knownWork; }
        public List<Execution> completedLessons() { return completed; }
        public boolean accountingComplete() { return false; }
    }
    private static final class Meter {
        final int limit; int used;
        Meter(int limit) { this.limit = limit; }
        int remaining() { return limit - used; }
        void add(long amount) {
            if (amount < 0) throw new IllegalArgumentException("negative work");
            if (amount > remaining()) { used = limit; throw new Limit(); }
            used += (int) amount;
        }
    }
    private static final class Limit extends RuntimeException { private static final long serialVersionUID = 1L; }
}
