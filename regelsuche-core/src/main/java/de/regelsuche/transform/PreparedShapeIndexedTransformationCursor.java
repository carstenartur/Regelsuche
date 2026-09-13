package de.regelsuche.transform;

final class PreparedShapeIndexedTransformationCursor extends PreparedTransformationCursor
        implements ShapeIndexedTransformationCursor {
    PreparedShapeIndexedTransformationCursor(PreparedAstRewriteTransformationEngine engine, String source, Definition definition) {
        super(engine, source, definition, true);
    }
    @Override public IndexReceipt indexReceipt() { return retainedIndexReceipt(); }
}
