package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkReplacementAccountingTest {
    @Test void delegatedInclusiveReceiptsChargeEachOperationOnceAcrossEightPhases() {
        var child = receipt("proof", RULE_FORMATION_PROOF, 3, 3, List.of());
        var parent = receipt("train", TRAINING_SEARCH, 2, 5, List.of("proof"));
        var account = new LifecycleWorkAccount(List.of(parent, child), List.of("train"));
        assertEquals(5, account.totalWork());
        assertEquals(2, account.work(TRAINING_SEARCH));
        assertEquals(3, account.work(RULE_FORMATION_PROOF));
        assertEquals(8, account.byPhase().size());
        assertEquals("raw:train", account.receipts().getFirst().rawReceipt());
    }
    @Test void missingRepeatedOrUnownedDelegatesCannotDisappearInTheAccount() {
        var leaf = receipt("leaf", QUERY, 2, 2, List.of());
        assertThrows(IllegalArgumentException.class, () -> new LifecycleWorkAccount(
            List.of(receipt("parent", QUERY, 1, 3, List.of("missing"))), List.of("parent")));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleWorkAccount(
            List.of(receipt("parent", QUERY, 1, 5, List.of("leaf", "leaf")), leaf), List.of("parent")));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleWorkAccount(List.of(leaf), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleWorkAccount(
            List.of(receipt("parent", QUERY, 1, 4, List.of("leaf")), leaf), List.of("parent")));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleWorkAccount(List.of(leaf, leaf), List.of("leaf")));
    }
    @Test void negativeAndUnexplainedZeroWorkAreRejectedAndOverflowIsExplicit() {
        assertThrows(IllegalArgumentException.class, () -> receipt("negative", QUERY, -1, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> receipt("zero", QUERY, 0, 0, List.of()));
        assertThrows(ArithmeticException.class, () -> new LifecycleWorkAccount(List.of(
            receipt("huge", QUERY, Long.MAX_VALUE, Long.MAX_VALUE, List.of()),
            receipt("extra", OUTPUT, 1, 1, List.of())), List.of("huge", "extra")));
        var skipped = LifecycleWorkAccount.Receipt.skipped("none", COMPILATION, "no compilation requested");
        assertEquals(0, LifecycleWorkAccount.of(skipped).totalWork());
    }
    private static LifecycleWorkAccount.Receipt receipt(String id, LifecycleWorkAccount.Phase phase,
            long own, long inclusive, List<String> children) {
        return new LifecycleWorkAccount.Receipt(id, phase, own, inclusive, children, "legacy/v1", "raw:" + id, "");
    }
}
