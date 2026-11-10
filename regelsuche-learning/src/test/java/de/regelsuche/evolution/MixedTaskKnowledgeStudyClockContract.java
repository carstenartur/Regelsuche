package de.regelsuche.evolution;

import java.util.concurrent.atomic.AtomicLong;

/** Exercises the production study timestamps and elapsed accounting with controlled clock origins. */
final class MixedTaskKnowledgeStudyClockContract {
    static void verify(long origin) {
        var wall = new AtomicLong(origin);
        var cpu = new AtomicLong(200);
        var study = new MixedTaskKnowledgeStudy(TaskKnowledgeSelfImprover.systemMeter(wall::get, cpu::get));
        var start = study.now();
        require(start.wallNanos() == 0 && start.cpuNanos() == 200, "origin-relative start");
        wall.addAndGet(10); // Java subtraction must retain this duration even across MAX_VALUE -> MIN_VALUE.
        cpu.addAndGet(7);
        var paid = study.elapsed(start);
        require(paid.wallNanos() == 10 && paid.cpuNanos() == 7, "exact positive elapsed cost");
        var next = study.now();
        wall.addAndGet(5);
        cpu.set(-1);
        var unavailable = study.elapsed(next);
        require(unavailable.wallNanos() == 5 && unavailable.cpuNanos() == -1, "unavailable CPU remains unknown");
    }

    public static void main(String[] args) {
        verify(-100);
        verify(Long.MAX_VALUE - 4);
        System.out.println("PASS: study clock negative origin, wraparound and unknown CPU");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
