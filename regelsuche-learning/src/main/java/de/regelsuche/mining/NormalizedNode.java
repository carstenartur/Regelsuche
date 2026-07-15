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
        FUNCTION,
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

    static NormalizedNode function(String name, List<NormalizedNode> arguments) {
        return new NormalizedNode(Kind.FUNCTION, null, name, arguments);
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
            .sorted(Comparator.comparing(
                NormalizedNode::sortKey,
                NormalizedNode::compareNaturalText))
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
            .sorted(Comparator.comparing(
                NormalizedNode::factorSortKey,
                NormalizedNode::compareNaturalText))
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
            case FUNCTION -> name + "(" + children.stream()
                .map(NormalizedNode::canonicalString)
                .collect(Collectors.joining(",")) + ")";
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
            case FUNCTION -> name + "(" + children.stream()
                .map(child -> child.skeletonString(false))
                .collect(Collectors.joining(",")) + ")";
        };
    }

    boolean sameShape(NormalizedNode other) {
        if (kind != other.kind || children.size() != other.children.size()) {
            return false;
        }
        if (kind == Kind.VARIABLE) {
            return name.equals(other.name);
        }
        if (kind == Kind.FUNCTION) {
            if (!name.equals(other.name)) {
                return false;
            }
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).sameShape(other.children.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (kind == Kind.NUMBER || kind == Kind.PLHOLDER) {
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
            case FUNCTION -> "6:" + canonical;
        };
    }

    private String factorSortKey() {
        return kind == Kind.NUMBER ? "0:" + Math.abs(number) : "1:" + canonicalString();
    }

    /**
     * Compares embedded decimal runs by numeric magnitude while retaining the
     * existing textual order for all non-numeric characters. This prevents
     * canonical commutative ordering from placing 10*x before 8*x merely
     * because '1' sorts before '8'.
     */
    private static int compareNaturalText(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftEnd = digitEnd(left, leftIndex);
                int rightEnd = digitEnd(right, rightIndex);
                int leftSignificant = significantDigitStart(left, leftIndex, leftEnd);
                int rightSignificant = significantDigitStart(right, rightIndex, rightEnd);
                int leftDigits = leftEnd - leftSignificant;
                int rightDigits = rightEnd - rightSignificant;
                int lengthComparison = Integer.compare(leftDigits, rightDigits);
                if (lengthComparison != 0) {
                    return lengthComparison;
                }
                int digitsComparison = left.regionMatches(
                    leftSignificant,
                    right,
                    rightSignificant,
                    leftDigits)
                    ? 0
                    : left.substring(leftSignificant, leftEnd)
                        .compareTo(right.substring(rightSignificant, rightEnd));
                if (digitsComparison != 0) {
                    return digitsComparison;
                }
                int rawLengthComparison = Integer.compare(
                    leftEnd - leftIndex,
                    rightEnd - rightIndex);
                if (rawLengthComparison != 0) {
                    return rawLengthComparison;
                }
                leftIndex = leftEnd;
                rightIndex = rightEnd;
                continue;
            }
            int characterComparison = Character.compare(leftChar, rightChar);
            if (characterComparison != 0) {
                return characterComparison;
            }
            leftIndex++;
            rightIndex++;
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static int digitEnd(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int significantDigitStart(String value, int start, int end) {
        int index = start;
        while (index + 1 < end && value.charAt(index) == '0') {
            index++;
        }
        return index;
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
