package de.regelsuche.qualification;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.plugin.*;
import de.regelsuche.transform.*;
import java.util.Optional;

/** Fixed public contract inputs, loaded only in the qualification working directory. */
public final class QualificationPlugin implements RegelsuchePlugin {
    public String id() { return "public-runtime-qualification"; }
    public String name() { return "Public runtime qualification contract"; }
    public String version() { return "1.0.0"; }
    public void registerTransformations(TransformationRegistry registry) {
        registry.register(new ContextAddZero());
        registry.register(new TechnicalFailure());
    }
    public void registerVisitors(AstVisitorRegistry registry) {
        registry.register(new AstVisitorPlugin() {
            public String id() { return "qualification-context-marker"; }
            public AstVisitorPhase phase() { return AstVisitorPhase.DURING_SEARCH; }
            public void visit(Expr root, AstVisitorContext context) { context.putMetadata(root, "enabled", true); }
        });
    }
    private static final class ContextAddZero implements PatternTransformation {
        private final RewriteRule addZero = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
        public String id() { return "qualification_context_add_zero"; }
        public boolean matches(Expr node, TransformationMatchContext context) {
            return Boolean.TRUE.equals(context.metadata().get("enabled")) && addZero.matches(node);
        }
        public Expr transform(Expr node, TransformationContext context) { return addZero.apply(node); }
        public Optional<RewriteApplicabilitySchema> explicitApplicabilitySchema() {
            return Optional.of(new RewriteApplicabilitySchema("public-context-contract/v1", this,
                PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("x"), PatternExpr.num(0)), RecognitionProfile.exact()));
        }
    }
    private static final class TechnicalFailure implements PatternTransformation {
        public String id() { return "qualification_technical_failure"; }
        public boolean matches(Expr node, TransformationMatchContext context) {
            throw new IllegalStateException("PUBLIC_QUALIFICATION_EXPECTED_TECHNICAL_FAILURE");
        }
        public Expr transform(Expr node, TransformationContext context) { throw new AssertionError("unreachable"); }
    }
}
