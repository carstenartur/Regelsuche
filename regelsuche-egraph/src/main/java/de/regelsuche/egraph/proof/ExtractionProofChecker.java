package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionCertificate.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.RewriteRule;

import java.util.*;

/** Detached inference interpreter; does not search for equality or consult a graph. */
public final class ExtractionProofChecker {
    private ExtractionProofChecker() {}

    public enum Status {
        ACCEPTED,
        REJECTED,
        LIMIT_EXCEEDED
    }

    public static final class CheckedCertificate {
        private final ExtractionCertificate certificate;
        private final Expr input, output;
        private final long work;

        private CheckedCertificate(ExtractionCertificate c, Expr in, Expr out, long work) {
            certificate = c;
            input = in;
            output = out;
            this.work = work;
        }

        public ExtractionCertificate certificate() {
            return certificate;
        }

        public Expr input() {
            return input;
        }

        public Expr output() {
            return output;
        }

        public long workUsed() {
            return work;
        }
    }

    public static final class CheckResult {
        private final Status status;
        private final String detail;
        private final long work;
        private final CheckedCertificate checked;

        private CheckResult(Status status, String detail, long work, CheckedCertificate checked) {
            this.status = status;
            this.detail = detail;
            this.work = work;
            this.checked = checked;
        }

        static CheckResult denied(Status status, String detail, long work) {
            if (status == Status.ACCEPTED) throw new IllegalArgumentException("checker token required");
            return new CheckResult(status, detail, work, null);
        }

        CheckResult includingPrelude(long prelude) {
            long total = Math.addExact(prelude, work);
            var token = checked == null ? null : new CheckedCertificate(
                    checked.certificate, checked.input, checked.output, total);
            return new CheckResult(status, detail, total, token);
        }

        public Status status() {
            return status;
        }

        public String detail() {
            return detail;
        }

        public long workUsed() {
            return work;
        }

        public Optional<CheckedCertificate> checked() {
            return Optional.ofNullable(checked);
        }
    }

    private record Equality(int left, int right) {}

    public static CheckResult check(
            Expr expectedInput,
            Expr expectedOutput,
            ExtractionCertificate certificate,
            Collection<? extends RewriteRule> trustedRules,
            ProofLimits limits) {
        return check(expectedInput, expectedOutput, certificate, trustedRules, limits, false);
    }

    static CheckResult check(Expr expectedInput, Expr expectedOutput, ExtractionCertificate certificate,
            Collection<? extends RewriteRule> trustedRules, ProofLimits limits, boolean literalVariables) {
        var s = new ProofSupport(limits, literalVariables);
        try {
            Objects.requireNonNull(certificate);
            s.work(1);
            require(certificate.schemaVersion() == ExtractionCertificate.SCHEMA_VERSION, "schema");
            s.registry(trustedRules);
            validateTerms(s, certificate, expectedInput, expectedOutput);
            var mapped = mapRules(s, certificate);
            s.size(certificate.steps().size(), limits.maxProofSteps(), "proof steps");
            var checked = new ArrayList<Equality>();
            for (Step step : certificate.steps()) {
                s.work(1);
                checked.add(replayStep(s, step, checked, mapped));
            }
            var conclusion = premise(checked, certificate.conclusion());
            require(
                    conclusion.left == certificate.inputTerm()
                            && conclusion.right == certificate.outputTerm(),
                    "conclusion endpoints");
            return new CheckResult(
                    Status.ACCEPTED,
                    "accepted",
                    s.work,
                    new CheckedCertificate(certificate, expectedInput, expectedOutput, s.work));
        } catch (ExtractionProofBuilder.LimitExceeded e) {
            return new CheckResult(Status.LIMIT_EXCEEDED, e.getMessage(), s.work, null);
        } catch (IllegalArgumentException | NullPointerException e) {
            return new CheckResult(
                    Status.REJECTED,
                    e.getMessage() == null ? "malformed certificate" : e.getMessage(),
                    s.work,
                    null);
        }
    }

    private static void validateTerms(
            ProofSupport s,
            ExtractionCertificate certificate,
            Expr expectedInput,
            Expr expectedOutput) {
        s.size(certificate.terms().size(), s.limits.maxTerms(), "terms limit");
        for (int i = 0; i < certificate.terms().size(); i++) {
            s.work(1);
            require(s.intern(certificate.terms().get(i)) == i, "duplicate term");
        }
        int termCount = s.terms.size();
        s.requireTerm(certificate.inputTerm());
        s.requireTerm(certificate.outputTerm());
        require(s.expression(expectedInput) == certificate.inputTerm(), "expected input mismatch");
        require(
                s.expression(expectedOutput) == certificate.outputTerm(),
                "expected output mismatch");
        require(s.terms.size() == termCount, "endpoint absent");
    }

    private static List<Integer> mapRules(ProofSupport s, ExtractionCertificate certificate) {
        s.size(certificate.rules().size(), s.limits.maxTerms(), "rules limit");
        var mapped = new ArrayList<Integer>();
        var seen = new HashSet<String>();
        for (var identity : certificate.rules()) {
            s.work(1);
            s.chars(identity.id());
            require(seen.add(identity.id()), "duplicate rule ID");
            Integer rule = s.ruleIds.get(identity.id());
            require(rule != null, "untrusted rule ID");
            var r = s.rules.get(rule);
            require(
                    s.pattern(identity.source()) == r.source()
                            && s.pattern(identity.target()) == r.target(),
                    "different rule definition");
            mapped.add(rule);
        }
        return mapped;
    }

    private static Equality replayStep(
            ProofSupport s, Step step, List<Equality> checked, List<Integer> mapped) {
        return switch (step) {
            case Reflexivity r -> {
                s.refs(1);
                int t = s.requireTerm(r.term());
                yield new Equality(t, t);
            }
            case Symmetry r -> {
                s.refs(1);
                var p = premise(checked, r.premise());
                yield new Equality(p.right, p.left);
            }
            case Transitivity r -> {
                s.refs(2);
                var p = premise(checked, r.first());
                var q = premise(checked, r.second());
                require(p.right == q.left, "transitive middle");
                yield new Equality(p.left, q.right);
            }
            case Congruence r -> checkCongruence(s, r, checked);
            case PatternInstance r -> checkInstance(s, r, mapped);
        };
    }

    private static Equality checkCongruence(ProofSupport s, Congruence r, List<Equality> checked) {
        s.refs(r.childProofs().size() + 2L);
        var a = s.terms.get(s.requireTerm(r.left()));
        var b = s.terms.get(s.requireTerm(r.right()));
        s.work(a.payload().length());
        require(
                a.kind() == b.kind()
                        && a.payload().equals(b.payload())
                        && a.children().size() == b.children().size()
                        && a.children().size() == r.childProofs().size(),
                "congruence symbol or arity");
        for (int k = 0; k < a.children().size(); k++) {
            s.work(1);
            var p = premise(checked, r.childProofs().get(k));
            require(
                    p.left == a.children().get(k) && p.right == b.children().get(k),
                    "congruence child endpoints");
        }
        return new Equality(r.left(), r.right());
    }

    private static Equality checkInstance(ProofSupport s, PatternInstance r, List<Integer> mapped) {
        s.refs(r.bindings().size() + 2L);
        require(r.rule() >= 0 && r.rule() < mapped.size(), "rule reference");
        var rule = s.rules.get(mapped.get(r.rule()));
        s.bindings(rule, r.bindings());
        for (String name : r.bindings().keySet()) s.chars(name);
        require(
                s.instantiate(rule.source(), r.bindings(), false) == r.before()
                        && s.instantiate(rule.target(), r.bindings(), false) == r.after(),
                "pattern instance endpoints");
        return new Equality(r.before(), r.after());
    }

    private static Equality premise(List<Equality> checked, int index) {
        require(index >= 0 && index < checked.size(), "forward or missing premise");
        return checked.get(index);
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
