package de.regelsuche.math.algorithms.certificates;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CertificateWorkTest {
    @Test void completedNestedWorkSurvivesCancellationAtReturn() {
        var work = new CertificateWork(new IterationLimits(7, 10, 10_000), () -> true);
        work.chargeCompleted(6);
        assertThrows(CertificateWork.Stop.class, () -> work.charge(0));
        assertThrows(CertificateWork.Stop.class, () -> work.chargeCompleted(2));
        assertEquals(6, work.used());
    }

    @Test void exhaustionDoesNotGrantAdditionalWork() {
        var work = new CertificateWork(new IterationLimits(3, 10, 10_000), () -> false);
        work.charge(2);
        assertEquals(1, work.remainingLimits().maximumWork());
        work.charge(1);
        assertEquals("WORK_LIMIT", assertThrows(CertificateWork.Stop.class,
            work::remainingLimits).getMessage());
        assertThrows(CertificateWork.Stop.class, () -> work.charge(1));
        assertEquals(3, work.used());
    }

    @Test void cancellationAndInvalidChargesAreVisibleBeforeArithmetic() {
        var work = new CertificateWork(new IterationLimits(10, 10, 10_000), () -> true);
        assertEquals("CANCELLED", assertThrows(CertificateWork.Stop.class,
            () -> work.charge(0)).getMessage());
        var active = new CertificateWork(new IterationLimits(10, 10, 10_000), () -> false);
        assertThrows(IllegalArgumentException.class, () -> active.charge(-1));
        assertThrows(IllegalArgumentException.class, () -> active.cells(-1));
        assertEquals(0, active.used());
    }

    @Test void shapeAndBitLimitsAreChecked() {
        var work = new CertificateWork(new IterationLimits(100_000, 4, 10_000), () -> false);
        assertEquals("TERM_LIMIT", assertThrows(CertificateWork.Stop.class,
            () -> work.cells(5)).getMessage());
        assertEquals("RUNTIME_BIT_LIMIT", assertThrows(CertificateWork.Stop.class,
            () -> work.check(BigInteger.ONE.shiftLeft(65_536))).getMessage());
        assertEquals(ExactRational.integer(2), work.div(ExactRational.integer(6), ExactRational.integer(3)));
        assertEquals(ExactRational.integer(9), work.mul(ExactRational.integer(3), ExactRational.integer(3)));
        assertEquals(ExactRational.integer(5), work.add(ExactRational.integer(2), ExactRational.integer(3)));
        assertEquals(ExactRational.integer(-1), work.sub(ExactRational.integer(2), ExactRational.integer(3)));
    }
}
