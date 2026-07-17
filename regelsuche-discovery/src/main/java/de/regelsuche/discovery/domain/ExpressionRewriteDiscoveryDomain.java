package de.regelsuche.discovery.domain;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateContext;
import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateEvaluator;
import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateExtractor;
import de.regelsuche.discovery.domain.DiscoveryDomain.CanonicalCodec;
import de.regelsuche.discovery.domain.DiscoveryDomain.CertificateRenderer;
import de.regelsuche.discovery.domain.DiscoveryDomain.CertificateRendering;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleGenerator;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DiscoveryDomain.DomainPayload;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.EvidenceAdapter;
import de.regelsuche.discovery.domain.DiscoveryDomain.Invariant;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Objective;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.PathStep;
import de.regelsuche.discovery.domain.DiscoveryDomain.StateGenerator;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.domain.DiscoveryDomain.TransitionOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Adapter that exposes the existing AST rewrite machinery through the generic domain contract. */
public final class ExpressionRewriteDiscoveryDomain implements DiscoveryDomain<
    ExpressionRewriteDiscoveryDomain.ExpressionState,
    ExpressionRewriteDiscoveryDomain.ExpressionCandidate,
    ExpressionRewriteDiscoveryDomain.ExpressionTraceCertificate
> {
    public static final String DOMAIN_ID = "expression-rewrite";
    public static final String REVISION = "v1";

    private final String expectedTarget;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final AstRewriteTransformationEngine rewriteEngine;

    private final StateGenerator<ExpressionState> generator = new StateGenerator<>() {
        @Override
        public String id() {
            return "expression-seed-generator/v1";
        }

        @Override
        public List<ExpressionState> generate(DiscoverySeed seed) {
            return List.of(new ExpressionState(format(seed.payload())));
        }
    };

    private final CanonicalCodec<ExpressionState> stateCodec = new CanonicalCodec<>() {
        @Override
        public String id() {
            return "expression-syntax-codec/v1";
        }

        @Override
        public String canonicalForm(ExpressionState value) {
            return format(value.expression());
        }
    };

    private final List<Invariant<ExpressionState>> invariants = List.of(
        new Invariant<>() {
            @Override
            public String id() {
                return "expression-parseable/v1";
            }

            @Override
            public InvariantResult check(ExpressionState state) {
                try {
                    parser.parseTerm(state.expression());
                    return InvariantResult.pass();
                } catch (IllegalArgumentException exception) {
                    return InvariantResult.fail("unparseable-expression");
                }
            }
        },
        new Invariant<>() {
            @Override
            public String id() {
                return "expression-bounded-size/v1";
            }

            @Override
            public InvariantResult check(ExpressionState state) {
                return canonicalizer.astNodeCount(state.expression()) <= 256
                    ? InvariantResult.pass()
                    : InvariantResult.fail("ast-node-count>256");
            }
        }
    );

    private final List<TransitionOperator<ExpressionState>> operators = List.of(
        new TransitionOperator<>() {
            @Override
            public String id() {
                return "existing-ast-rewrite-engine/v1";
            }

            @Override
            public List<Successor<ExpressionState>> apply(ExpressionState state) {
                return rewriteEngine.transform(state.expression()).stream()
                    .map(ExpressionRewriteDiscoveryDomain.this::toSuccessor)
                    .toList();
            }
        }
    );

    private final Objective<ExpressionState> objective = new Objective<>() {
        @Override
        public String id() {
            return "expected-expression-objective/v1";
        }

        @Override
        public ObjectiveAssessment assess(ExpressionState state) {
            String canonicalState = stateCodec.canonicalForm(state);
            boolean ready = canonicalState.equals(expectedTarget);
            int weighted = scorer.score(canonicalState).weightedTotal();
            int score = ready ? 1_000_000 : 100_000 - weighted;
            return new ObjectiveAssessment(
                score,
                ready,
                Map.of(
                    "expectedTarget", expectedTarget,
                    "weightedExpressionScore", Integer.toString(weighted),
                    "syntacticTargetMatch", Boolean.toString(ready)));
        }
    };

    private final CandidateExtractor<ExpressionState, ExpressionCandidate> candidateExtractor =
        new CandidateExtractor<>() {
            @Override
            public String id() {
                return "expression-path-candidate-extractor/v1";
            }

            @Override
            public ExpressionCandidate extract(CandidateContext<ExpressionState> context) {
                List<String> rules = context.path().stream()
                    .map(PathStep::actionId)
                    .toList();
                List<String> assumptions = context.path().stream()
                    .flatMap(step -> step.assumptions().stream())
                    .distinct()
                    .sorted()
                    .toList();
                boolean preserving = context.path().stream()
                    .allMatch(PathStep::semanticsPreserving);
                return new ExpressionCandidate(
                    context.initialState().expression(),
                    context.currentState().expression(),
                    rules,
                    assumptions,
                    preserving);
            }
        };

    private final CanonicalCodec<ExpressionCandidate> candidateCodec = new CanonicalCodec<>() {
        @Override
        public String id() {
            return "expression-candidate-codec/v1";
        }

        @Override
        public String canonicalForm(ExpressionCandidate value) {
            return "source=" + format(value.sourceExpression())
                + "\ntarget=" + format(value.targetExpression())
                + "\nrules=" + DomainCanonical.canonicalList(value.ruleIds())
                + "\nassumptions=" + DomainCanonical.canonicalList(value.assumptions())
                + "\nsemanticsPreserving=" + value.allSemanticsPreserving();
        }
    };

    private final CounterexampleGenerator<ExpressionCandidate> counterexampleGenerator =
        new CounterexampleGenerator<>() {
            @Override
            public String id() {
                return "expression-transition-audit/v1";
            }

            @Override
            public CounterexampleResult search(
                ExpressionCandidate candidate,
                int attemptBudget
            ) {
                if (!candidate.allSemanticsPreserving()) {
                    return CounterexampleResult.found(
                        1,
                        "path contains a transition not marked semantics-preserving",
                        Map.of("auditedRules", "1"));
                }
                if (candidate.ruleIds().isEmpty()) {
                    return CounterexampleResult.inconclusive(
                        0,
                        "candidate has no retained rewrite step",
                        Map.of("auditedRules", "0"));
                }
                if (candidate.ruleIds().size() > attemptBudget) {
                    return CounterexampleResult.inconclusive(
                        attemptBudget,
                        "transition audit budget is smaller than the retained path",
                        Map.of(
                            "auditedRules", Integer.toString(attemptBudget),
                            "requiredRules", Integer.toString(candidate.ruleIds().size())));
                }
                return CounterexampleResult.noneFound(
                    candidate.ruleIds().size(),
                    Map.of(
                        "auditedRules", Integer.toString(candidate.ruleIds().size()),
                        "auditKind", "transition-metadata"));
            }
        };

    private final CandidateEvaluator<ExpressionCandidate, ExpressionTraceCertificate> evaluator =
        new CandidateEvaluator<>() {
            @Override
            public String id() {
                return "expression-canonical-equivalence-evaluator/v1";
            }

            @Override
            public Evaluation<ExpressionTraceCertificate> evaluate(
                ExpressionCandidate candidate
            ) {
                if (!candidate.allSemanticsPreserving()) {
                    return Evaluation.refuted(
                        "retained path contains a non-preserving transition",
                        Map.of());
                }
                String sourceCanonical = canonicalizer.canonicalize(
                    candidate.sourceExpression());
                String targetCanonical = canonicalizer.canonicalize(
                    candidate.targetExpression());
                if (!sourceCanonical.equals(targetCanonical)) {
                    return Evaluation.inconclusive(
                        "strong canonical forms differ",
                        Map.of(
                            "sourceCanonical", sourceCanonical,
                            "targetCanonical", targetCanonical));
                }
                ExpressionTraceCertificate certificate = new ExpressionTraceCertificate(
                    canonicalizer.stableHash(candidate.sourceExpression()),
                    canonicalizer.stableHash(candidate.targetExpression()),
                    candidate.ruleIds(),
                    candidate.assumptions(),
                    "VALIDATION_EVIDENCE_NOT_FORMAL_PROOF");
                return Evaluation.confirmed(
                    certificate,
                    "existing AST rewrite path reaches a canonically equivalent target",
                    Map.of(
                        "sourceCanonical", sourceCanonical,
                        "targetCanonical", targetCanonical,
                        "ruleCount", Integer.toString(candidate.ruleIds().size())));
            }
        };

    private final CanonicalCodec<ExpressionTraceCertificate> certificateCodec =
        new CanonicalCodec<>() {
            @Override
            public String id() {
                return "expression-trace-certificate-codec/v1";
            }

            @Override
            public String canonicalForm(ExpressionTraceCertificate value) {
                return "sourceHash=" + value.sourceCanonicalHash()
                    + "\ntargetHash=" + value.targetCanonicalHash()
                    + "\nrules=" + DomainCanonical.canonicalList(value.ruleIds())
                    + "\nassumptions=" + DomainCanonical.canonicalList(value.assumptions())
                    + "\nstrength=" + value.evidenceStrength();
            }
        };

    private final CertificateRenderer<ExpressionTraceCertificate> certificateRenderer =
        new CertificateRenderer<>() {
            @Override
            public String id() {
                return "expression-trace-certificate-renderer/v1";
            }

            @Override
            public CertificateRendering render(ExpressionTraceCertificate certificate) {
                String text = "source=" + certificate.sourceCanonicalHash()
                    + "\ntarget=" + certificate.targetCanonicalHash()
                    + "\nrules=" + String.join(" -> ", certificate.ruleIds())
                    + "\nassumptions=" + String.join(", ", certificate.assumptions())
                    + "\nstrength=" + certificate.evidenceStrength();
                return new CertificateRendering(
                    "CANONICAL_EQUIVALENCE_TRACE",
                    "text/plain",
                    text);
            }
        };

    private final EvidenceAdapter<ExpressionState, ExpressionCandidate, ExpressionTraceCertificate>
        evidenceAdapter = new EvidenceAdapter<>() {
            @Override
            public String id() {
                return "expression-domain-evidence-adapter/v1";
            }

            @Override
            public DomainPayload adapt(
                Optional<ExpressionState> initialState,
                Optional<ExpressionCandidate> candidate,
                Optional<ExpressionTraceCertificate> certificate
            ) {
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                values.put("initialExpression", initialState
                    .map(ExpressionState::expression)
                    .orElse("NOT_AVAILABLE"));
                values.put("expectedTarget", expectedTarget);
                values.put("candidateStatus", candidate.isPresent()
                    ? "SELECTED"
                    : "NOT_SELECTED");
                values.put("candidateTarget", candidate
                    .map(ExpressionCandidate::targetExpression)
                    .orElse("NOT_AVAILABLE"));
                values.put("ruleCount", candidate
                    .map(value -> Integer.toString(value.ruleIds().size()))
                    .orElse("0"));
                values.put("allSemanticsPreserving", candidate
                    .map(value -> Boolean.toString(value.allSemanticsPreserving()))
                    .orElse("NOT_EVALUATED"));
                values.put("certificateStrength", certificate
                    .map(ExpressionTraceCertificate::evidenceStrength)
                    .orElse("NOT_EVALUATED"));
                values.put("formalProofStatus", "NOT_EVALUATED");
                return new DomainPayload("expression-rewrite-evidence/v1", values);
            }
        };

    public ExpressionRewriteDiscoveryDomain(String expectedTarget) {
        this(expectedTarget, new AstRewriteTransformationEngine());
    }

    public ExpressionRewriteDiscoveryDomain(
        String expectedTarget,
        AstRewriteTransformationEngine rewriteEngine
    ) {
        this.expectedTarget = format(
            DomainCanonical.requireText(expectedTarget, "expectedTarget"));
        this.rewriteEngine = Objects.requireNonNull(rewriteEngine, "rewriteEngine");
    }

    @Override
    public String domainId() {
        return DOMAIN_ID;
    }

    @Override
    public String revision() {
        return REVISION;
    }

    @Override
    public String stateType() {
        return "expression-state/v1";
    }

    @Override
    public String candidateType() {
        return "expression-rewrite-candidate/v1";
    }

    @Override
    public String certificateType() {
        return "expression-trace-certificate/v1";
    }

    @Override
    public StateGenerator<ExpressionState> generator() {
        return generator;
    }

    @Override
    public CanonicalCodec<ExpressionState> stateCodec() {
        return stateCodec;
    }

    @Override
    public List<Invariant<ExpressionState>> invariants() {
        return invariants;
    }

    @Override
    public List<TransitionOperator<ExpressionState>> operators() {
        return operators;
    }

    @Override
    public Objective<ExpressionState> objective() {
        return objective;
    }

    @Override
    public CandidateExtractor<ExpressionState, ExpressionCandidate> candidateExtractor() {
        return candidateExtractor;
    }

    @Override
    public CanonicalCodec<ExpressionCandidate> candidateCodec() {
        return candidateCodec;
    }

    @Override
    public CounterexampleGenerator<ExpressionCandidate> counterexampleGenerator() {
        return counterexampleGenerator;
    }

    @Override
    public CandidateEvaluator<ExpressionCandidate, ExpressionTraceCertificate> evaluator() {
        return evaluator;
    }

    @Override
    public CanonicalCodec<ExpressionTraceCertificate> certificateCodec() {
        return certificateCodec;
    }

    @Override
    public CertificateRenderer<ExpressionTraceCertificate> certificateRenderer() {
        return certificateRenderer;
    }

    @Override
    public EvidenceAdapter<ExpressionState, ExpressionCandidate, ExpressionTraceCertificate>
            evidenceAdapter() {
        return evidenceAdapter;
    }

    private Successor<ExpressionState> toSuccessor(Transformation transformation) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("rewriteKind", transformation.kind().name());
        metadata.put("applicationKey", transformation.applicationKey());
        metadata.put("packId", transformation.packId());
        metadata.put("license", transformation.license());
        metadata.put("mayIncreaseComplexity",
            Boolean.toString(transformation.mayIncreaseComplexity()));
        return new Successor<>(
            transformation.rule(),
            new ExpressionState(format(transformation.transformedExpression())),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            transformation.assumptions(),
            metadata);
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    public record ExpressionState(String expression) {
        public ExpressionState {
            DomainCanonical.requireText(expression, "expression");
        }
    }

    public record ExpressionCandidate(
        String sourceExpression,
        String targetExpression,
        List<String> ruleIds,
        List<String> assumptions,
        boolean allSemanticsPreserving
    ) {
        public ExpressionCandidate {
            DomainCanonical.requireText(sourceExpression, "sourceExpression");
            DomainCanonical.requireText(targetExpression, "targetExpression");
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
            for (String ruleId : ruleIds) {
                DomainCanonical.requireIdentifier(ruleId, "ruleId");
            }
            assumptions = DomainCanonical.sortedDistinct(assumptions);
        }
    }

    public record ExpressionTraceCertificate(
        String sourceCanonicalHash,
        String targetCanonicalHash,
        List<String> ruleIds,
        List<String> assumptions,
        String evidenceStrength
    ) {
        public ExpressionTraceCertificate {
            DomainCanonical.requireSha256(
                sourceCanonicalHash, "sourceCanonicalHash");
            DomainCanonical.requireSha256(
                targetCanonicalHash, "targetCanonicalHash");
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
            assumptions = DomainCanonical.sortedDistinct(assumptions);
            DomainCanonical.requireIdentifier(evidenceStrength, "evidenceStrength");
        }
    }
}
