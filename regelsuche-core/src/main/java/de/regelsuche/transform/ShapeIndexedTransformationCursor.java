package de.regelsuche.transform;

import java.util.List;

/** Explicit root/shape selection; full rule definitions remain in the ordinary cursor snapshot. */
public interface ShapeIndexedTransformationCursor extends TransformationCursor {
    String INDEX_REVISION = "regelsuche.native-rule-root-shape-index/v1";
    String SELECTION_REVISION = "regelsuche.native-occurrence-preorder-root-shape-selection/v1";
    String WORK_REVISION = "regelsuche.native-transformation-cursor-shape-work/v1";

    /** Logical root exclusions are not counted as executed predicate operations. */
    record Selection(List<Integer> path, int rootExcludedRules) {
        public Selection { path = List.copyOf(path); }
    }
    record IndexReceipt(String indexRevision, Snapshot cursor, List<Selection> selections) {
        public IndexReceipt { selections = List.copyOf(selections); }
    }
    IndexReceipt indexReceipt();
}
