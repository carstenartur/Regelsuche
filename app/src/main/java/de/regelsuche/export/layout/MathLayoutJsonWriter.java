package de.regelsuche.export.layout;

import de.regelsuche.json.JsonWriter;

/**
 * Emits {@link MathLayout} instances through the project's tiny
 * {@link JsonWriter} without allocating intermediate JSON strings.
 */
public final class MathLayoutJsonWriter {

    private MathLayoutJsonWriter() {
    }

    public static void write(JsonWriter writer, String key, MathLayout layout) {
        if (layout == null) {
            writer.nullProperty(key);
            return;
        }
        writer.object(key, inner -> {
            inner.property("kind", layout.kind().name());
            inner.array("nodes", nodes -> layout.nodes().forEach(node ->
                nodes.objectValue(nodeWriter -> writeNode(nodeWriter, node))));
            inner.property("aria", layout.ariaLabel());
        });
    }

    private static void writeNode(JsonWriter writer, MathLayoutNode node) {
        writer.property("kind", node.kind().name());
        if (!node.text().isEmpty()) {
            writer.property("text", node.text());
        }
        if (!node.children().isEmpty()) {
            writer.array("children", children -> node.children().forEach(child ->
                children.objectValue(childWriter -> writeNode(childWriter, child))));
        }
        if (!node.attributes().isEmpty()) {
            writer.object("attributes", attributes -> node.attributes().forEach(attributes::property));
        }
    }
}
