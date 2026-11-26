package de.regelsuche.math.algorithms.modular;

import static de.regelsuche.math.algorithms.modular.QuadraticRootContract.Observation.EXACT_PAIR;
import static de.regelsuche.math.algorithms.modular.QuadraticRootContract.Observation.UP_TO_JOINT_SIGN;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Step.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuadraticRootTest {
    private static final IterationLimits LIMITS = new IterationLimits(200_000_000, 8192, 30_000);
    private static final SharedPowerPlan.Weights WEIGHTS = new SharedPowerPlan.Weights(1, 1, 1000);
    private static final BigInteger LARGE = new BigInteger("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    private static QuadraticRootContract source(BigInteger p, QuadraticRootContract.Observation observation) {
        var half = p.add(BigInteger.ONE).shiftRight(1);
        var exponent = half.shiftRight(1);
        var program = new QuadraticRootProgram(List.of(constant(half), add(0, 2), multiply(4, 3),
                power(5, exponent), square(6)), 7, 5,
                new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(8, 9)), 6, 10, false),
                new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3), power(9, exponent),
                        multiply(1, 3), inverse(10), multiply(11, 12)), 10, 13, false));
        return new QuadraticRootContract("unit-test", p, PrimeEvidenceCatalog.forModulus(p).orElseThrow(), program, observation);
    }

    private static QuadraticRootSearch.Certificate search(QuadraticRootContract c) {
        var found = QuadraticRootSearch.search(c, WEIGHTS, 64, LIMITS, () -> false);
        assertTrue(found.certificate().isPresent(), found.reason());
        var cert = found.certificate().orElseThrow();
        var checked = QuadraticRootVerifier.verify(c, cert, LIMITS, () -> false);
        assertTrue(checked.valid(), checked.reason());
        return cert;
    }

    @Test void sourceDrivenScheduleHasOnePowerAndNoInverseForLargeField() {
        var c = source(LARGE, EXACT_PAIR);
        var cert = search(c);
        var prefix = cert.program().prefix();
        assertEquals(1, prefix.stream().filter(s -> s.op() == QuadraticRootProgram.Op.POWER).count());
        assertTrue(prefix.stream().noneMatch(s -> s.op() == QuadraticRootProgram.Op.INVERSE));
        assertEquals(LARGE.subtract(BigInteger.valueOf(3)).shiftRight(2),
                prefix.stream().filter(s -> s.op() == QuadraticRootProgram.Op.POWER).findFirst().orElseThrow().literal());
        assertFalse(cert.program().equal().normalizeRealCharacter());
        assertTrue(cert.program().unequal().normalizeRealCharacter());
        var random = new Random(75333);
        for (int i = 0; i < 12; i++) {
            var x = new BigInteger(379, random).add(BigInteger.ONE);
            var h = new BigInteger(379, random).add(BigInteger.ONE);
            compare(c, cert.program(), x, h);
        }
    }

    @Test void everyNonrealTupleIncludingBothNormSignsAgreesAtSmallPrimes() {
        for (var p : new int[]{3, 7, 11, 19, 43}) {
            var c = source(BigInteger.valueOf(p), EXACT_PAIR);
            var cert = search(c);
            for (int x = 1; x < p; x++) for (int h = 1; h < p; h++) {
                compare(c, cert.program(), BigInteger.valueOf(x), BigInteger.valueOf(h));
            }
        }
    }

    private static void compare(QuadraticRootContract c, QuadraticRootProgram generated, BigInteger x, BigInteger h) {
        var p = c.modulus();
        var y = h.multiply(h).multiply(x.modInverse(p)).mod(p);
        var a = x.subtract(y).mod(p);
        var b = h.shiftLeft(1).mod(p);
        var g = x.add(y).mod(p);
        var e = p.add(BigInteger.ONE).shiftRight(2);
        var r = x.modPow(e, p);
        if (!r.multiply(r).mod(p).equals(x)) r = y.negate().mod(p).modPow(e, p);
        var expected = new QuadraticRootEvaluation.Pair(r, h.multiply(r.modInverse(p)).mod(p));
        assertEquals(expected, QuadraticRootEvaluation.execute(c, generated, a, b, g, LIMITS, () -> false)
                .pair().orElseThrow());
    }

    private static QuadraticRootContract denominatorSource(BigInteger p) {
        var old = source(p, EXACT_PAIR);
        var program = new QuadraticRootProgram(old.original().prefix(), 5, 7,
                new QuadraticRootProgram.Arm(List.of(constant(BigInteger.TWO), multiply(6, 8),
                        inverse(9), multiply(1, 10)), 6, 11, false),
                new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3),
                        power(9, p.add(BigInteger.ONE).shiftRight(2)), constant(BigInteger.TWO),
                        multiply(10, 11), inverse(12), multiply(1, 13)), 10, 14, false));
        return new QuadraticRootContract("different-source", p, old.primeEvidence(), program, EXACT_PAIR);
    }

    @Test void foldedDenominatorScaleAndReversedEqualityProduceOnlyLivePrefixDefinitions() {
        var source = denominatorSource(LARGE);
        var found = QuadraticRootSearch.search(source, WEIGHTS, 64, LIMITS.withMaximumWork(2_000_000), () -> false);
        assertTrue(found.certificate().isPresent(), found.reason());
        var cert = found.certificate().orElseThrow();
        var program = cert.program();
        assertTrue(QuadraticRootVerifier.verify(source, cert, LIMITS.withMaximumWork(2_000_000), () -> false).valid());
        assertTrue(program.prefix().stream().noneMatch(step -> step.op() == QuadraticRootProgram.Op.INVERSE));
        var live = new HashSet<Integer>();
        live.add(program.guardLeft());
        live.add(program.guardRight());
        for (var arm : List.of(program.equal(), program.unequal())) {
            live.add(arm.real());
            live.add(arm.imaginary());
            for (var step : arm.steps()) live.add(step.left());
        }
        for (int i = program.prefix().size() - 1; i >= 0; i--) {
            assertTrue(live.contains(i + 3), "unused prefix step " + i + ": " + program.prefix().get(i));
            live.add(program.prefix().get(i).left());
            live.add(program.prefix().get(i).right());
        }
        var path = source.original().cost(false, WEIGHTS);
        assertEquals(2, path.powerCalls());
        assertEquals(1, path.inversions());
        var generated = program.cost(false, WEIGHTS);
        assertEquals(1, generated.powerCalls());
        assertEquals(0, generated.inversions());
        assertEquals(1, generated.characterSelections());
        assertTrue(generated.weightedGroupWork() < path.weightedGroupWork());
    }

    @Test void neutralSourceDefinitionsKeepTheOriginalContractThroughReplay() {
        var c = source(BigInteger.valueOf(43), EXACT_PAIR);
        var original = c.original();
        var prefix = new ArrayList<>(original.prefix());
        prefix.add(constant(BigInteger.ONE)); // 8
        prefix.add(constant(BigInteger.ZERO)); // 9
        prefix.add(add(9, 5)); // alias the base in the guard
        prefix.add(multiply(7, 8)); // alias the square in the guard
        var equal = new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(12, 13),
                multiply(8, 6), subtract(14, 9)), 15, 16, false);
        var unequal = new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(12, 3), power(13, BigInteger.valueOf(11)),
                multiply(1, 3), inverse(14), multiply(15, 16)), 14, 17, false);
        var wrapped = new QuadraticRootProgram(prefix, 10, 11, equal, unequal);
        var variant = new QuadraticRootContract(c.sourceIdentity(), c.modulus(), c.primeEvidence(), wrapped, EXACT_PAIR);
        var cert = search(variant);
        assertEquals(wrapped, cert.contract().original());
        assertFalse(QuadraticRootVerifier.verify(c, cert, LIMITS, () -> false).valid());

        prefix.set(5, constant(BigInteger.TWO));
        var changed = new QuadraticRootContract(c.sourceIdentity(), c.modulus(), c.primeEvidence(),
                new QuadraticRootProgram(prefix, 10, 11, equal, unequal), EXACT_PAIR);
        assertTrue(QuadraticRootSearch.search(changed, WEIGHTS, 64, LIMITS, () -> false).certificate().isEmpty());
    }

    @Test void replaysExistingCertificatesWhoseBasisContainsNeutralSourceOperations() {
        var c = source(BigInteger.valueOf(43), EXACT_PAIR);
        var old = c.original();
        var original = new QuadraticRootProgram(old.prefix(), 7, 5, new QuadraticRootProgram.Arm(List.of(
                multiply(1, 3), constant(BigInteger.ZERO), add(8, 9), inverse(6), multiply(10, 11)), 6, 12, false), old.unequal());
        var contract = new QuadraticRootContract("old", c.modulus(), c.primeEvidence(), original, EXACT_PAIR);
        // A certificate produced by the earlier search, before neutral-root extraction was supported.
        var prefix = List.of(add(0, 2), constant(BigInteger.valueOf(22)), multiply(3, 4), multiply(1, 4),
                constant(BigInteger.ZERO), add(6, 7), power(5, BigInteger.TEN), multiply(9, 5), multiply(8, 9), square(10));
        var program = new QuadraticRootProgram(prefix, 12, 5, new QuadraticRootProgram.Arm(List.of(), 10, 11, false),
                new QuadraticRootProgram.Arm(List.of(negate(10)), 11, 13, true));
        var request = new SharedPowerPlan.Request(BigInteger.valueOf(42), List.of(
                new SharedPowerPlan.Output(BigInteger.valueOf(11), BigInteger.TWO),
                new SharedPowerPlan.Output(BigInteger.valueOf(-11), BigInteger.TWO)));
        var schedule = new SharedPowerPlan.Schedule(List.of(SharedPowerPlan.Step.power(BigInteger.TEN),
                SharedPowerPlan.Step.multiply(2, 1)), List.of(3, 2));
        var cert = new QuadraticRootSearch.Certificate(contract, new SharedPowerPlan.Certificate(request, schedule), program);
        var proof = QuadraticRootVerifier.verify(contract, cert, LIMITS, () -> false);
        assertTrue(proof.valid(), proof.reason());
    }

    @Test void recognizesTheGuardWhenTheSourceSquaresWithAPowerInstruction() {
        var source = source(BigInteger.valueOf(43), EXACT_PAIR);
        var old = source.original();
        var prefix = new ArrayList<>(old.prefix());
        prefix.set(4, power(6, BigInteger.TWO));
        var contract = new QuadraticRootContract("power-square", source.modulus(), source.primeEvidence(),
                new QuadraticRootProgram(prefix, old.guardLeft(), old.guardRight(), old.equal(), old.unequal()), EXACT_PAIR);
        assertTrue(QuadraticRootSearch.search(contract, WEIGHTS, 64, LIMITS, () -> false).certificate().isPresent());
    }

    @Test void ranksCompleteVerifiedProgramsAcrossPowerSchedules() {
        var source = source(BigInteger.valueOf(43), EXACT_PAIR);
        var found = QuadraticRootSearch.portfolio(source, WEIGHTS, 64, LIMITS, () -> false);
        var schedules = new HashSet<SharedPowerPlan.Schedule>();
        var programs = new HashSet<QuadraticRootProgram>();
        long previous = 0;
        for (var cert : found.certificates()) {
            assertTrue(programs.add(cert.program()));
            schedules.add(cert.power().schedule());
            assertTrue(QuadraticRootVerifier.verify(source, cert, LIMITS, () -> false).valid());
            assertTrue(cert.program().score(WEIGHTS) >= previous);
            previous = cert.program().score(WEIGHTS);
        }
        assertTrue(schedules.size() >= 2, found.reason());
        var best = found.certificates().getFirst();
        assertEquals(best, QuadraticRootSearch.search(source, WEIGHTS, 64, LIMITS, () -> false).certificate().orElseThrow());
        assertTrue(best.program().score(WEIGHTS) < source.original().score(WEIGHTS));
        assertTrue(found.certificates().stream().anyMatch(alt ->
                alt.program().score(WEIGHTS) > best.program().score(WEIGHTS)
                && alt.program().unequal().steps().size() < best.program().unequal().steps().size()),
                "An exit-only comparison would prefer an expensive power schedule");
        assertTrue(QuadraticRootSearch.portfolio(source, WEIGHTS, 64,
                LIMITS.withMaximumWork(found.work() - 1), () -> false).certificates().isEmpty());
    }

    @Test void aSmallPairBudgetCanStillExploreBothBranchesOfTheFirstPowerSchedule() {
        var c = source(BigInteger.valueOf(43), EXACT_PAIR);
        var result = QuadraticRootSearch.portfolio(c, WEIGHTS, 8, LIMITS, () -> false);
        assertFalse(result.certificates().isEmpty(), result.reason());
        assertEquals(8, result.candidates());
        var limited = new IterationLimits(LIMITS.maximumWork(), 600, LIMITS.timeoutMillis());
        assertFalse(QuadraticRootSearch.portfolio(c, WEIGHTS, 8, limited, () -> false).certificates().isEmpty());
        var stop = QuadraticRootSearch.portfolio(c, WEIGHTS, 64, limited, () -> false);
        assertEquals("TERM_LIMIT", stop.reason());
        assertTrue(stop.certificates().isEmpty());
    }

    @Test void anUnsuccessfulCandidateCapReportsUnexploredAlternatives() {
        var source = source(BigInteger.valueOf(43), EXACT_PAIR);
        var limited = QuadraticRootSearch.portfolio(source, WEIGHTS, 1, LIMITS, () -> false);
        assertTrue(limited.certificates().isEmpty());
        assertEquals(1, limited.candidates());
        assertEquals("CANDIDATE_LIMIT", limited.reason());
        assertEquals(limited.reason(), QuadraticRootSearch.search(source, WEIGHTS, 1, LIMITS, () -> false).reason());
        assertFalse(QuadraticRootSearch.portfolio(source, WEIGHTS, 8, LIMITS, () -> false).certificates().isEmpty());
    }

    @Test void invalidRootProgramsRemainProofFailuresEvenWithSmallCandidateCaps() {
        var source = source(BigInteger.valueOf(43), EXACT_PAIR);
        var original = source.original();
        var unequal = original.unequal();
        var different = new QuadraticRootProgram(original.prefix(), original.guardLeft(), original.guardRight(),
                original.equal(), new QuadraticRootProgram.Arm(unequal.steps(), unequal.real(), unequal.real(), false));
        var contract = new QuadraticRootContract("different-output", source.modulus(), source.primeEvidence(), different, EXACT_PAIR);
        for (int cap : new int[]{1, 384}) {
            var rejected = QuadraticRootSearch.portfolio(contract, WEIGHTS, cap, LIMITS, () -> false);
            assertTrue(rejected.certificates().isEmpty());
            assertEquals("SOURCE_IS_NOT_A_ROOT", rejected.reason());
            assertEquals(0, rejected.candidates());
        }
    }

    @Test void anExactCompletePairAllowanceRetainsTheVerifiedPortfolio() {
        var contract = source(BigInteger.valueOf(43), EXACT_PAIR);
        var complete = QuadraticRootSearch.portfolio(contract, WEIGHTS, 384, LIMITS, () -> false);
        assertEquals("VERIFIED", complete.reason());
        assertFalse(complete.certificates().isEmpty());
        assertTrue(complete.candidates() > 1);
        var exact = QuadraticRootSearch.portfolio(contract, WEIGHTS, complete.candidates(), LIMITS, () -> false);
        assertEquals("VERIFIED", exact.reason());
        assertEquals(complete.certificates(), exact.certificates());
        var bounded = QuadraticRootSearch.portfolio(contract, WEIGHTS, 8, LIMITS, () -> false);
        assertEquals("VERIFIED", bounded.reason());
        assertFalse(bounded.certificates().isEmpty());
    }

    @Test void anAlreadyCheapestCompleteProgramHasNoMathematicalImprovement() {
        var source = source(BigInteger.valueOf(31), EXACT_PAIR);
        var cheapInverse = new SharedPowerPlan.Weights(1, 1, 1);
        var selected = QuadraticRootSearch.portfolio(source, cheapInverse, 64, LIMITS, () -> false);
        var optimal = new QuadraticRootContract("efficient-original", source.modulus(), source.primeEvidence(),
                selected.certificates().getFirst().program(), EXACT_PAIR);
        var repeated = QuadraticRootSearch.search(optimal, cheapInverse, 64, LIMITS, () -> false);
        assertEquals("NO_COST_IMPROVEMENT", repeated.reason());
        assertTrue(repeated.certificate().isEmpty());
    }

    @Test void proofRejectsModifiedGuardCoordinatePowerCertificateAndInvalidReferences() {
        var source = denominatorSource(BigInteger.valueOf(43));
        var cert = search(source);
        var plan = cert.program();
        var arm = plan.unequal();
        var badExit = new QuadraticRootProgram.Arm(arm.steps(), arm.real(), arm.real(), arm.normalizeRealCharacter());
        for (var broken : List.of(
                new QuadraticRootProgram(plan.prefix(), plan.guardLeft(), plan.guardRight(), plan.equal(), badExit),
                new QuadraticRootProgram(plan.prefix(), 0, 1, plan.equal(), arm),
                new QuadraticRootProgram(plan.prefix(), plan.guardLeft(), plan.guardRight(), plan.equal(),
                        new QuadraticRootProgram.Arm(List.of(inverse(200)), 200, 200, false)))) {
            assertFalse(QuadraticRootVerifier.verify(source, new QuadraticRootSearch.Certificate(source, cert.power(), broken),
                    LIMITS, () -> false).valid());
        }
        var wrongPower = new SharedPowerPlan.Certificate(cert.power().source(), new SharedPowerPlan.Schedule(List.of(), List.of(0, 0)));
        assertFalse(QuadraticRootVerifier.verify(source, new QuadraticRootSearch.Certificate(source, wrongPower, plan), LIMITS, () -> false).valid());
        var invalidPrime = new QuadraticRootContract(source.sourceIdentity(), source.modulus(), new PrimeEvidence.SmallPrime(BigInteger.valueOf(21)),
                source.original(), EXACT_PAIR);
        assertFalse(QuadraticRootVerifier.verify(source, new QuadraticRootSearch.Certificate(invalidPrime, cert.power(), plan), LIMITS, () -> false).valid());
        for (int index : new int[]{0, 3}) {
            var prefix = new ArrayList<>(source.original().prefix());
            prefix.set(index, index == 0 ? constant(BigInteger.TWO) : power(5, BigInteger.TWO));
            var bad = new QuadraticRootProgram(prefix, source.original().guardLeft(), source.original().guardRight(),
                    source.original().equal(), source.original().unequal());
            var contract = new QuadraticRootContract("changed", source.modulus(), source.primeEvidence(), bad, EXACT_PAIR);
            assertTrue(QuadraticRootSearch.search(contract, WEIGHTS, 64, LIMITS, () -> false).certificate().isEmpty());
        }
    }

    @Test void guardRequiresCanonicalNonzeroImaginaryAndTheNormAndPublicEvaluationReplaysTheProof() {
        var source = denominatorSource(BigInteger.valueOf(43));
        var cert = search(source);
        var p = source.modulus();
        assertTrue(QuadraticRootContract.accepts(p, BigInteger.ZERO, BigInteger.TWO, BigInteger.TWO));
        assertTrue(QuadraticRootEvaluation.evaluate(source, cert, BigInteger.ZERO, BigInteger.TWO, BigInteger.TWO, LIMITS, () -> false).pair().isPresent());
        assertFalse(QuadraticRootContract.accepts(p, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
        assertFalse(QuadraticRootContract.accepts(p, p, BigInteger.TWO, BigInteger.TWO));
        assertFalse(QuadraticRootContract.accepts(p, BigInteger.ZERO, BigInteger.TWO.negate(), BigInteger.TWO));
        assertFalse(QuadraticRootContract.accepts(p, BigInteger.ZERO, BigInteger.TWO, p.add(BigInteger.TWO)));
        assertFalse(QuadraticRootContract.accepts(p, BigInteger.ZERO, BigInteger.TWO, BigInteger.ONE));
        var small = QuadraticRootSearch.search(source, WEIGHTS, 1, LIMITS, () -> false);
        assertEquals(1, small.candidates());
        assertTrue(small.certificate().isEmpty());
        assertTrue(QuadraticRootSearch.search(source, WEIGHTS, 64, new IterationLimits(5000, 3, 1000), () -> false).certificate().isEmpty());
    }

    @Test void relaxedProofDoesNotAuthorizeAnExactRawPair() {
        var c = source(BigInteger.valueOf(43), UP_TO_JOINT_SIGN);
        var cert = search(c);
        var exact = source(c.modulus(), EXACT_PAIR);
        assertFalse(cert.program().unequal().normalizeRealCharacter());
        assertFalse(QuadraticRootVerifier.verify(exact, cert, LIMITS, () -> false).valid());
        assertFalse(QuadraticRootVerifier.verify(exact, new QuadraticRootSearch.Certificate(exact, cert.power(),
                cert.program()), LIMITS, () -> false).valid());
    }

    @Test void changedSourceGuardAndBudgetCannotAuthorizeCode() {
        var c = source(BigInteger.valueOf(43), EXACT_PAIR);
        var original = c.original();
        var broken = new QuadraticRootProgram(original.prefix(), 0, 0, original.equal(), original.unequal());
        var changed = new QuadraticRootContract(c.sourceIdentity(), c.modulus(), c.primeEvidence(), broken, EXACT_PAIR);
        assertTrue(QuadraticRootSearch.search(changed, WEIGHTS, 64, LIMITS, () -> false).certificate().isEmpty());
        var cert = search(c);
        assertFalse(QuadraticRootVerifier.verify(new QuadraticRootContract("different", c.modulus(), c.primeEvidence(),
                original, EXACT_PAIR), cert, LIMITS, () -> false).valid());
        assertTrue(QuadraticRootSearch.search(c, WEIGHTS, 64, LIMITS.withMaximumWork(4), () -> false).certificate().isEmpty());
        assertFalse(QuadraticRootVerifier.verify(c, cert, LIMITS, () -> true).valid());
        assertTrue(QuadraticRootEvaluation.evaluate(c, cert, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
                LIMITS, () -> false).pair().isEmpty());
    }

    @Test void retainedCharacterLanesAndLiveExitRegistersShareOneTermBudget() {
        var source = denominatorSource(BigInteger.valueOf(43));
        var certificate = search(source);
        var program = certificate.program();
        var extra = new ArrayList<>(program.equal().steps());
        for (int i = 0; i < 80; i++) extra.add(constant(BigInteger.ONE));
        var arm = new QuadraticRootProgram.Arm(extra, program.equal().real(), program.equal().imaginary(),
                program.equal().normalizeRealCharacter());
        var extended = new QuadraticRootProgram(program.prefix(), program.guardLeft(), program.guardRight(), arm, program.unequal());
        var evidence = new QuadraticRootSearch.Certificate(source, certificate.power(), extended);
        assertTrue(QuadraticRootVerifier.verify(source, evidence, LIMITS, () -> false).valid());
        var restricted = QuadraticRootVerifier.verify(source, evidence, new IterationLimits(100_000, 400, 1000), () -> false);
        assertEquals("TERM_LIMIT", restricted.reason());
    }

    @Test void nestedPowerTablesAndTheirFieldBasisShareOneStateAllowance() {
        var source = denominatorSource(BigInteger.valueOf(43));
        var certificate = search(source);
        var power = certificate.power();
        var padded = new ArrayList<>(power.schedule().steps());
        while (padded.size() < 256) padded.add(SharedPowerPlan.Step.multiply(0, 0));
        var schedule = new SharedPowerPlan.Schedule(padded, power.schedule().outputs());
        var extended = new SharedPowerPlan.Certificate(power.source(), schedule);
        var evidence = new QuadraticRootSearch.Certificate(source, extended, certificate.program());
        int standaloneCells = 3 * (256 + 2) + 3 * 2;
        var tight = new IterationLimits(100_000, standaloneCells, 1000);
        assertTrue(SharedPowerVerifier.verify(power.source(), extended, WEIGHTS, tight, () -> false).valid());
        assertTrue(QuadraticRootVerifier.verify(source, evidence, LIMITS, () -> false).valid());
        assertEquals("TERM_LIMIT", QuadraticRootVerifier.verify(source, evidence, tight, () -> false).reason());
    }

    @Test void foldedPowerStepsStillNeedReferencesDuringInstantiation() {
        var source = denominatorSource(BigInteger.valueOf(43));
        var power = search(source).power();
        var padded = new ArrayList<>(power.schedule().steps());
        while (padded.size() < 256) padded.add(SharedPowerPlan.Step.multiply(0, 0));
        var schedule = new SharedPowerPlan.Schedule(padded, power.schedule().outputs());
        var work = new CertificateWork(new IterationLimits(100_000, 258, 1000), () -> false);
        var basis = new QuadraticRootBasis(source, work);
        var stop = assertThrows(CertificateWork.Stop.class, () -> basis.instantiate(schedule));
        assertEquals("TERM_LIMIT", stop.getMessage());
    }

    @Test void branchProofStopsWithinItsBudgetAndReportsEachCompletedCharge() {
        var source = source(BigInteger.valueOf(43), EXACT_PAIR);
        var polls = new AtomicInteger();
        long ceiling = 1000;
        var limited = QuadraticRootSearch.portfolio(source, WEIGHTS, 8, LIMITS.withMaximumWork(ceiling), () -> {
            polls.incrementAndGet();
            return false;
        });
        assertEquals("WORK_LIMIT", limited.reason());
        assertTrue(limited.certificates().isEmpty());

        // Stop a generously budgeted replay at the same charge. Its receipt measures the work
        // actually completed before that boundary, independently of the exhausted child ledger.
        var replayPolls = new AtomicInteger();
        var cancelled = QuadraticRootSearch.portfolio(source, WEIGHTS, 8, LIMITS,
                () -> replayPolls.incrementAndGet() == polls.get());
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.certificates().isEmpty());
        assertEquals(cancelled.work(), limited.work());
        assertTrue(cancelled.work() <= ceiling);

        var deadlinePolls = new AtomicInteger();
        int timeoutMillis = 50;
        var timed = QuadraticRootSearch.portfolio(source, WEIGHTS, 8,
                new IterationLimits(LIMITS.maximumWork(), LIMITS.maximumTerms(), timeoutMillis), () -> {
                    if (deadlinePolls.incrementAndGet() == polls.get()) {
                        assertDoesNotThrow(() -> Thread.sleep(2L * timeoutMillis));
                    }
                    return false;
                });
        assertEquals("TIME_LIMIT", timed.reason());
        assertTrue(timed.certificates().isEmpty());
        assertTrue(timed.work() <= cancelled.work());
    }

    @Test void verificationAndSearchRequireTheirEntireReportedWork() {
        var source = denominatorSource(BigInteger.valueOf(43));
        var found = QuadraticRootSearch.search(source, WEIGHTS, 64, LIMITS, () -> false);
        var cert = found.certificate().orElseThrow();
        assertTrue(QuadraticRootSearch.search(source, WEIGHTS, 64, LIMITS.withMaximumWork(found.work()), () -> false)
                .certificate().isPresent());
        assertEquals("WORK_LIMIT", QuadraticRootSearch.search(source, WEIGHTS, 64, LIMITS.withMaximumWork(found.work() - 1),
                () -> false).reason());
        var checked = QuadraticRootVerifier.verify(source, cert, LIMITS, () -> false);
        assertTrue(QuadraticRootVerifier.verify(source, cert, LIMITS.withMaximumWork(checked.work()), () -> false).valid());
        assertEquals("WORK_LIMIT", QuadraticRootVerifier.verify(source, cert, LIMITS.withMaximumWork(checked.work() - 1),
                () -> false).reason());
    }

    @Test void binaryJacobiAgreesWithEulerAndHandlesCompositeDenominators() {
        for (int p : new int[]{3, 5, 7, 11, 19, 31, 43}) {
            var m = BigInteger.valueOf(p);
            for (int i = -p; i < p; i++) {
                var a = BigInteger.valueOf(i);
                var euler = a.modPow(m.shiftRight(1), m);
                assertEquals(euler.equals(m.subtract(BigInteger.ONE)) ? -1 : euler.intValueExact(),
                        QuadraticCharacter.jacobi(a, m));
            }
        }
        assertEquals(-1, QuadraticCharacter.jacobi(BigInteger.TWO, BigInteger.valueOf(21)));
        assertEquals(0, QuadraticCharacter.jacobi(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        assertEquals(1, QuadraticCharacter.jacobi(BigInteger.ZERO, BigInteger.ONE));
    }
}
