package de.regelsuche.solver.portfolio;

import java.util.ArrayList;
import java.util.List;

/** Bounded native wire syntax; no mathematical or proof authority. */
final class Z3ProofSyntax {
    private Z3ProofSyntax() { }

    record Node(String token, List<Node> children) {
        String atom() { if (token == null) throw new Invalid(); return token; }
        boolean is(String value) { return value.equals(token); }
        boolean head(String value) { return children != null && !children.isEmpty() && children.getFirst().is(value); }
        String first() { if (children == null || children.isEmpty()) throw new Invalid(); return children.getFirst().atom(); }
        List<Node> items(int arity) { if (children == null || children.size() != arity) throw new Invalid(); return children; }
    }

    static Node parse(String text) { return new Parser(text).read(); }

    private static final class Parser {
        private final String text;
        private int offset, nodes;
        Parser(String text) { this.text = text; }
        Node read() {
            Node result = read(0);
            space();
            if (offset != text.length()) throw new Invalid();
            return result;
        }
        private Node read(int depth) {
            space();
            if (++nodes > 8192 || depth > 128 || offset == text.length()) throw new Invalid();
            if (text.charAt(offset) != '(') {
                int begin = offset;
                while (offset < text.length() && !delimiter(text.charAt(offset))) offset++;
                if (begin == offset || offset - begin > 256) throw new Invalid();
                return new Node(text.substring(begin, offset), null);
            }
            offset++;
            var children = new ArrayList<Node>();
            space();
            while (offset < text.length() && text.charAt(offset) != ')') {
                children.add(read(depth + 1));
                space();
            }
            if (offset == text.length()) throw new Invalid();
            offset++;
            return new Node(null, List.copyOf(children));
        }
        private static boolean delimiter(char c) { return c == '(' || c == ')' || Character.isWhitespace(c); }
        private void space() { while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++; }
    }

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
