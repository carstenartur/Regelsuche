package example;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.ServiceLoader;

/** One exact algebraic identity, exposed through the public plugin API. */
public final class HelloRule implements RegelsuchePlugin {
    public String id() { return "hello-rule"; }
    public String name() { return "Add zero"; }
    public String version() { return "1.0.0"; }

    public void registerRules(RuleRegistry rules) {
        var a = PatternExpr.var("a");
        rules.register(new PatternRewriteRule("hello-add-zero",
            PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a));
    }

    public static RuleRegistry registry() {
        var rules = new RuleRegistry();
        ServiceLoader.load(RegelsuchePlugin.class).forEach(plugin -> plugin.registerRules(rules));
        return rules;
    }

    public static void main(String[] args) {
        var rule = registry().enabledRules().getFirst();
        Expr before = new ExpressionParser().parseTerm("x + 0");
        if (!rule.matches(before)) throw new AssertionError("expected an exact match");
        Expr after = rule.apply(before);
        // Replay the retained rule and input; equality alone does not prove arbitrary plugins.
        if (!after.equals(rule.apply(before))) throw new AssertionError("replay mismatch");
        System.out.println("rule=" + rule.id());
        System.out.println("before=" + ExpressionFormatter.format(before));
        System.out.println("after=" + ExpressionFormatter.format(after));
        System.out.println("replay=VERIFIED");
    }
}
