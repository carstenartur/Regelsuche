package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionCertificate.*;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Versioned storage for detached certificate data. Only the proof checker can authorize it. */
public final class ExtractionCertificateCodec {
    private static final int MAGIC = 0x45515343; // EQSC
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final List<Kind> TERM_KINDS = List.of(Kind.NUMBER, Kind.VARIABLE, Kind.BINARY, Kind.FUNCTION);
    private static final List<String> PATTERN_TAGS = List.of("P", "NUMBER", "BINARY", "FUNCTION");

    private ExtractionCertificateCodec() {}

    /** Encodes data that fits the same limits when decoded. */
    public static byte[] encode(ExtractionCertificate certificate, ProofLimits limits) {
        Objects.requireNonNull(certificate, "certificate");
        var writer = new Writer(limits);
        writer.certificate(certificate);
        return writer.output.toByteArray();
    }

    public static ExtractionCertificate decode(byte[] bytes, ProofLimits limits) {
        Objects.requireNonNull(bytes, "bytes");
        var reader = new Reader(bytes, limits);
        return reader.certificate();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalArgumentException(detail);
    }

    private static int reference(int index, int size) {
        require(index >= 0 && index < size, "missing or forward reference");
        return index;
    }

    private static long patternOrderWork(ProofSupport.PNode node) {
        return 1L + node.label().length() + node.children().size();
    }

    private static final class Writer {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final ProofSupport syntax;

        Writer(ProofLimits limits) {
            syntax = new ProofSupport(limits);
        }

        void certificate(ExtractionCertificate value) {
            require(value.schemaVersion() == SCHEMA_VERSION, "unsupported certificate schema");
            integer(MAGIC);
            integer(FORMAT_VERSION);
            terms(value.terms());
            rules(value.rules());
            syntax.size(value.steps().size(), syntax.limits.maxProofSteps(), "proof steps");
            integer(value.steps().size());
            for (int index = 0; index < value.steps().size(); index++) {
                step(value.steps().get(index), index, value.rules().size());
            }
            integer(reference(value.inputTerm(), syntax.terms.size()));
            integer(reference(value.outputTerm(), syntax.terms.size()));
            integer(reference(value.conclusion(), value.steps().size()));
        }

        private void terms(List<Term> terms) {
            syntax.size(terms.size(), syntax.limits.maxTerms(), "terms limit");
            integer(terms.size());
            for (int index = 0; index < terms.size(); index++) {
                Term term = terms.get(index);
                require(syntax.intern(term) == index, "duplicate term");
                tag(TERM_KINDS.indexOf(term.kind()));
                text(term.payload());
                indices(term.children(), index);
            }
        }

        private void rules(List<RuleIdentity> rules) {
            syntax.size(rules.size(), syntax.limits.maxTerms(), "rules limit");
            var ids = new HashSet<String>();
            var roots = new ArrayList<Integer>();
            for (RuleIdentity rule : rules) {
                syntax.work(1);
                syntax.chars(rule.id());
                require(!rule.id().isBlank() && ids.add(rule.id()), "invalid or duplicate rule ID");
                roots.add(syntax.pattern(rule.source()));
                roots.add(syntax.pattern(rule.target()));
            }
            integer(syntax.patterns.size());
            for (int index = 0; index < syntax.patterns.size(); index++) {
                var pattern = syntax.patterns.get(index);
                // The reader additionally checks canonical order and parses each numeric label once.
                syntax.work(patternOrderWork(pattern));
                if (pattern.tag().equals("NUMBER")) syntax.work(pattern.label().length());
                tag(PATTERN_TAGS.indexOf(pattern.tag()));
                text(pattern.label());
                indices(pattern.children(), index);
            }
            integer(rules.size());
            for (int index = 0; index < rules.size(); index++) {
                text(rules.get(index).id());
                integer(roots.get(2 * index));
                integer(roots.get(2 * index + 1));
            }
        }

        private void step(Step value, int priorSteps, int ruleCount) {
            syntax.refs(switch (value) {
                case Reflexivity _, Symmetry _ -> 1L;
                case Transitivity _ -> 2L;
                case Congruence step -> step.childProofs().size() + 2L;
                case PatternInstance step -> step.bindings().size() + 2L;
            });
            switch (value) {
                case Reflexivity step -> {
                    tag(0);
                    termReference(step.term());
                }
                case Symmetry step -> {
                    tag(1);
                    integer(reference(step.premise(), priorSteps));
                }
                case Transitivity step -> {
                    tag(2);
                    integer(reference(step.first(), priorSteps));
                    integer(reference(step.second(), priorSteps));
                }
                case Congruence step -> {
                    tag(3);
                    termReference(step.left());
                    termReference(step.right());
                    indices(step.childProofs(), priorSteps);
                }
                case PatternInstance step -> {
                    tag(4);
                    integer(reference(step.rule(), ruleCount));
                    termReference(step.before());
                    termReference(step.after());
                    bindings(step.bindings());
                }
            }
        }

        private void bindings(Map<String, Integer> bindings) {
            for (String name : bindings.keySet()) {
                syntax.chars(name);
                require(!name.isBlank(), "blank binding name");
            }
            integer(bindings.size());
            new TreeMap<>(bindings).forEach((name, term) -> {
                text(name);
                termReference(term);
            });
        }

        private void termReference(int term) {
            integer(reference(term, syntax.terms.size()));
        }

        private void indices(List<Integer> references, int upperBound) {
            integer(references.size());
            for (int index : references) integer(reference(index, upperBound));
        }

        private void text(String value) {
            integer(value.length());
            reserve((long) value.length() * Character.BYTES);
            for (int index = 0; index < value.length(); index++) {
                char unit = value.charAt(index);
                output.write(unit >>> 8);
                output.write(unit);
            }
        }

        private void tag(int value) {
            reserve(1);
            output.write(value);
        }

        private void integer(int value) {
            reserve(Integer.BYTES);
            for (int shift = 24; shift >= 0; shift -= 8) output.write(value >>> shift);
        }

        private void reserve(long count) {
            syntax.size(output.size() + (long) count, MAX_BYTES, "certificate byte limit");
            syntax.work(count);
        }
    }

    private static final class Reader {
        private final ByteBuffer input;
        private final ProofSupport syntax;
        private long readCharacters;

        Reader(byte[] bytes, ProofLimits limits) {
            syntax = new ProofSupport(limits);
            syntax.size(bytes.length, MAX_BYTES, "certificate byte limit");
            syntax.work(bytes.length);
            input = ByteBuffer.wrap(bytes.clone());
        }

        ExtractionCertificate certificate() {
            require(integer() == MAGIC && integer() == FORMAT_VERSION, "unsupported certificate encoding");
            readTerms();
            var rules = readRules();
            int count = count(syntax.limits.maxProofSteps(), 5, "proof steps");
            var steps = new ArrayList<Step>();
            for (int index = 0; index < count; index++) steps.add(step(index, rules.size()));
            int start = termReference();
            int end = termReference();
            int conclusion = reference(integer(), count);
            require(!input.hasRemaining(), "trailing certificate data");
            return new ExtractionCertificate(SCHEMA_VERSION, syntax.terms, rules, steps, start, end, conclusion);
        }

        private void readTerms() {
            int count = count(syntax.limits.maxTerms(), 9, "terms limit");
            for (int index = 0; index < count; index++) {
                Kind kind = TERM_KINDS.get(reference(tag(), TERM_KINDS.size()));
                String label = text(kind == Kind.NUMBER ? 4096 : Long.MAX_VALUE);
                long remaining = syntax.limits.maxReferences() - syntax.references;
                int arity = switch (kind) {
                    case NUMBER, VARIABLE -> 0;
                    case BINARY -> 2;
                    case FUNCTION -> -1;
                };
                var children = indices(index, remaining, arity);
                require(syntax.intern(new Term(kind, label, children)) == index, "duplicate term");
            }
        }

        private List<RuleIdentity> readRules() {
            var patterns = readPatterns();
            int count = count(syntax.limits.maxTerms(), 12, "rules limit");
            var rules = new ArrayList<RuleIdentity>();
            var ids = new HashSet<String>();
            for (int index = 0; index < count; index++) {
                syntax.work(1);
                String id = text();
                syntax.chars(id);
                require(!id.isBlank() && ids.add(id), "invalid or duplicate rule ID");
                int source = reference(integer(), patterns.size());
                int target = reference(integer(), patterns.size());
                PatternExpr before = patterns.get(source).expression();
                PatternExpr after = patterns.get(target).expression();
                require(syntax.pattern(before) == source && syntax.pattern(after) == target, "noncanonical rule root");
                rules.add(new RuleIdentity(id, before, after));
            }
            require(syntax.patterns.size() == patterns.size(), "unused or duplicate pattern");
            for (int index = 0; index < patterns.size(); index++) {
                var wire = patterns.get(index).wire();
                syntax.work(patternOrderWork(wire));
                require(syntax.patterns.get(index).equals(wire), "noncanonical rule pattern order");
            }
            return rules;
        }

        private record PatternNode(ProofSupport.PNode wire, PatternExpr expression, int depth, long expanded) {}

        private List<PatternNode> readPatterns() {
            int count = count(syntax.limits.maxTerms(), 9, "pattern nodes");
            var patterns = new ArrayList<PatternNode>();
            long remaining = syntax.limits.maxReferences() - syntax.references;
            for (int index = 0; index < count; index++) {
                String tag = PATTERN_TAGS.get(reference(tag(), PATTERN_TAGS.size()));
                String label = text(tag.equals("NUMBER") ? 4096 : Long.MAX_VALUE);
                int arity = switch (tag) {
                    case "P", "NUMBER" -> 0;
                    case "BINARY" -> 2;
                    default -> -1;
                };
                List<Integer> children = indices(index, remaining, arity);
                remaining -= children.size();
                patterns.add(pattern(tag, label, children, patterns));
            }
            return patterns;
        }

        private PatternNode pattern(String tag, String label, List<Integer> children, List<PatternNode> prior) {
            int depth = 1;
            long expanded = 1;
            var arguments = new ArrayList<PatternExpr>();
            for (int child : children) {
                var node = prior.get(child);
                depth = Math.max(depth, Math.addExact(node.depth(), 1));
                expanded += node.expanded();
                syntax.size(expanded, syntax.limits.maxExpandedNodes(), "expanded pattern");
                arguments.add(node.expression());
            }
            syntax.size(expanded, syntax.limits.maxExpandedNodes(), "expanded pattern");
            syntax.size(depth, syntax.limits.maxDepth(), "pattern depth");
            PatternExpr value = switch (tag) {
                case "P" -> PatternExpr.var(label);
                case "NUMBER" -> PatternExpr.num(syntax.number(label));
                case "BINARY" -> PatternExpr.op(BinaryOperator.valueOf(label), arguments.get(0), arguments.get(1));
                case "FUNCTION" -> new PatternExpr.Function(label, arguments);
                default -> throw new IllegalArgumentException("unsupported pattern");
            };
            return new PatternNode(new ProofSupport.PNode(tag, label, children), value, depth, expanded);
        }

        private Step step(int priorSteps, int ruleCount) {
            return switch (tag()) {
                case 0 -> {
                    syntax.refs(1);
                    yield new Reflexivity(termReference());
                }
                case 1 -> {
                    syntax.refs(1);
                    yield new Symmetry(reference(integer(), priorSteps));
                }
                case 2 -> {
                    syntax.refs(2);
                    yield new Transitivity(reference(integer(), priorSteps), reference(integer(), priorSteps));
                }
                case 3 -> congruence(priorSteps);
                case 4 -> instance(ruleCount);
                default -> throw new IllegalArgumentException("unknown proof step");
            };
        }

        private Congruence congruence(int priorSteps) {
            int left = termReference();
            int right = termReference();
            syntax.refs(2);
            var proofs = indices(priorSteps, syntax.limits.maxReferences() - syntax.references, -1);
            syntax.refs(proofs.size());
            return new Congruence(left, right, proofs);
        }

        private PatternInstance instance(int ruleCount) {
            int rule = reference(integer(), ruleCount);
            int before = termReference();
            int after = termReference();
            syntax.refs(2);
            int count = count(syntax.limits.maxReferences() - syntax.references, 8, "binding limit");
            syntax.refs(count);
            var bindings = new HashMap<String, Integer>();
            String previous = "";
            for (int index = 0; index < count; index++) {
                String name = text();
                syntax.chars(name);
                require(!name.isBlank() && previous.compareTo(name) < 0, "noncanonical binding order");
                bindings.put(name, termReference());
                previous = name;
            }
            return new PatternInstance(rule, bindings, before, after);
        }

        private int termReference() {
            return reference(integer(), syntax.terms.size());
        }

        private List<Integer> indices(int upperBound, long limit, int requiredArity) {
            int count = count(limit, Integer.BYTES, "reference limit");
            require(requiredArity < 0 || requiredArity == count, "invalid node arity");
            var references = new ArrayList<Integer>();
            for (int index = 0; index < count; index++) references.add(reference(integer(), upperBound));
            return references;
        }

        private String text() {
            return text(Long.MAX_VALUE);
        }

        private String text(long labelLimit) {
            long limit = Math.min(labelLimit, syntax.limits.maxCharacters() - readCharacters);
            int length = count(limit, Character.BYTES, "character limit");
            readCharacters += length;
            char[] units = new char[length];
            for (int index = 0; index < length; index++) units[index] = input.getChar();
            return new String(units);
        }

        private int count(long limit, int minimumBytes, String detail) {
            int count = integer();
            require(count >= 0, "negative certificate count");
            syntax.size(count, limit, detail);
            require((long) count * minimumBytes <= input.remaining(), "truncated certificate");
            return count;
        }

        private int tag() {
            require(input.hasRemaining(), "truncated certificate");
            return Byte.toUnsignedInt(input.get());
        }

        private int integer() {
            require(input.remaining() >= Integer.BYTES, "truncated certificate");
            return input.getInt();
        }
    }
}
