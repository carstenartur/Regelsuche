package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Collectors;

final class NormalizedNode {
    enum Kind {
        NUMBER,
        VARIABLE,
        ADD,
        MUL,
        POW,
        PLACEHOLDER
    }

    private final Kind kind;
    private final Integer number;
    private final String name;
    private final List<NormalizedNode> children;

    private NormalizedNode(Kind kind, Integer number, String name, List<NormalizedNode> children) {
        this.kind = kind;
        this.number = number;
        this.name = name;
        this.children = List.copyOf(children);
    }

    static NormalizedNode number(int value) {
        return new NormalizedNode(Kind.NUMBER, value, null, List.of());
    }

    static NormalizedNode variable(String name) {
        return new NormalizedNode(Kind.VARIABLE, null, name, List.of());
    }

    static NormalizedNode placeholder(String name) {
        return new NormalizedNode(Kind.PLACEHOLDER, null, name, List.of());
    }

    static NormalizedNode pow(NormalizedNode base, NormalizedNode exponent) {
        return new NormalizedNode(Kind.POW, null, null, List.of(base, exponent));
    }

    static NormalizedNode add(List<NormalizedNode> terms) {
        List<NormalizedNode> flattened = new ArrayList<>();
        int numeric = 0;
        for (NormalizedNode term : terms) {
            if (term.kind == Kind.ADD) {
                flattened.addAll(term.children);
            } else if (term.kind == Kind.NUMBER) {
                numeric += term.number;
            } else {
                flattened.add(term);
            }
        }
        if (numeric != 0) {
            flattened.add(number(numeric));
        }
        List<NormalizedNode> normalized = flattened.stream()
            .filter(term -> term.kind != Kind.NUMBER || term.number != 0)
            .sorted(Comparator.comparing(NormalizedNode::sortKey))
            .toList();
        if (normalized.isEmpty()) {
            return number(0);
        }
        if (normalized.size() == 1) {
            return normalized.getFirst();
        }
        return new NormalizedNode(Kind.ADD, null, null, normalized);
    }

    static NormalizedNode multiply(List<NormalizedNode> factors) {
        List<NormalizedNode> flattened = new ArrayList<>();
        int numeric = 1;
        for (NormalizedNode factor : factors) {
            if (factor.kind == Kind.MUL) {
                flattened.addAll(factor.children);
            } else if (factor.kind == Kind.NUMBER) {
                numeric *= factor.number;
            } else {
                flattened.add(factor);
            }
        }
        if (numeric == 0) {
            return number(0);
        }
        if (numeric != 1 || flattened.isEmpty()) {
            flattened.add(number(numeric));
        }
        List<NormalizedNode> normalized = flattened.stream()
            .filter(factor -> factor.kind != Kind.NUMBER || factor.number != 1 || flattened.size() == 1)
            .sorted(Comparator.comparing(NormalizedNode::factorSortKey))
            .toList();
        if (normalized.size() == 1) {
            return normalized.getFirst();
        }
        return new NormalizedNode(Kind.MUL, null, null, normalized);
    }

    Kind kind() {
        return kind;
    }

    Integer number() {
        return number;
    }

    String name() {
        return name;
    }

    List<NormalizedNode> children() {
        return children;
    }

    OptionalInt integerValue() {
        return kind == Kind.NUMBER ? OptionalInt.of(number) : OptionalInt.empty();
    }

    String canonicalString() {
        return switch (kind) {
            case NUMBER -> Integer.toString(number);
            case VARIABLE, PLACEHOLDER -> name;
            case ADD -> formatAdd();
            case MUL -> formatMul();
            case POW -> parenthesize(children.get(0), Kind.POW) + "^" + parenthesize(children.get(1), Kind.POW);
        };
    }

    String skeletonString() {
        return skeletonString(false);
    }

    private String skeletonString(boolean exponentPosition) {
        return switch (kind) {
            case NUMBER -> exponentPosition ? Integer.toString(number) : (number < 0 ? "-#" : "#");
            case VARIABLE -> name;
            case PLACEHOLDER -> "#";
            case ADD -> children.stream().map(child -> child.skeletonString(false)).collect(Collectors.joining("+", "add(", ")"));
            case MUL -> children.stream().map(child -> child.skeletonString(false)).collect(Collectors.joining("*", "mul(", ")"));
            case POW -> "pow(" + children.get(0).skeletonString(false) + "," + children.get(1).skeletonString(true) + ")";
        };
    }

    boolean sameShape(NormalizedNode other) {
        if (kind != other.kind || children.size() != other.children.size()) {
            return false;
        }
        if (kind == Kind.VARIABLE) {
            return name.equals(other.name);
        }
        if (kind == Kind.NUMBER || kind == Kind.PLACEHOLDER) {
            return true;
        }
        for (int i = 0; i < children.size(); i++) {
            if (!children.get(i).sameShape(other.children.get(i))) {
                return false;
            }
        }
        return true;
    }

    private String formatAdd() {
        StringBuilder builder = new StringBuilder();
        for (NormalizedNode child : children) {
            String value = child.canonicalString();
            if (builder.isEmpty()) {
                builder.append(value);
            } else if (value.startsWith("-")) {
                builder.append(" - ").append(value.substring(1));
            } else {
                builder.append(" + ").append(value);
            }
        }
        return builder.toString();
    }

    private String formatMul() {
        if (children.getFirst().kind == Kind.NUMBER && children.getFirst().number == -1) {
            return "-" + children.subList(1, children.size()).stream()
                .map(child -> parenthesize(child, Kind.MUL))
                .collect(Collectors.joining("*"));
        }
        return children.stream()
            .map(child -> parenthesize(child, Kind.MUL))
            .collect(Collectors.joining("*"));
    }

    private static String parenthesize(NormalizedNode node, Kind parent) {
        if ((parent == Kind.MUL || parent == Kind.POW) && node.kind == Kind.ADD) {
            return "(" + node.canonicalString() + ")";
        }
        return node.canonicalString();
    }

    private String sortKey() {
        String canonical = canonicalString();
        if (canonical.equals("x") || canonical.matches("x\\^\\d+")) {
            return "0:" + canonical;
        }
        if (canonical.contains("x")) {
            return "1:" + canonical.replaceFirst("^-", "");
        }
        return switch (kind) {
            case POW -> "3:" + canonical;
            case MUL -> "4:" + canonical.replaceFirst("^-", "");
            case VARIABLE, PLACEHOLDER -> "2:" + canonical;
            case NUMBER -> "9:" + Math.abs(number);
            case ADD -> "5:" + canonical;
        };
    }

    private String factorSortKey() {
        return kind == Kind.NUMBER ? "0:" + Math.abs(number) : "1:" + canonicalString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NormalizedNode node)) {
            return false;
        }
        return kind == node.kind
            && Objects.equals(number, node.number)
            && Objects.equals(name, node.name)
            && Objects.equals(children, node.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, number, name, children);
    }
}
