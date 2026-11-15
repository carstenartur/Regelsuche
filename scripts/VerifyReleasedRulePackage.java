import de.regelsuche.plugin.RuleFileParser;
import de.regelsuche.plugin.RuleFileParser.Entry;
import de.regelsuche.plugin.RuleFileParser.ProfileDefinition;
import de.regelsuche.plugin.RuleFileParser.RuleDefinition;
import de.regelsuche.plugin.RuleFileParser.RuleDirection;
import de.regelsuche.plugin.RuleFileParser.Severity;
import java.nio.file.Path;
import java.util.List;

/** Standalone Java 25 check of the data package through the published parser API. */
public final class VerifyReleasedRulePackage {
    public static void main(String[] args) {
        var parser = new RuleFileParser();
        var pack = parser.parse(Path.of(args[0]));
        require(pack.diagnostics().isEmpty(), "unexpected package diagnostics: " + pack.diagnostics());
        var entries = pack.entries();
        require(entries.size() == 3, "expected exactly two rules and an activation profile");
        requireRule(entries.get(0), "extract_common_factor", "A*X + B*X", "(A + B)*X");
        requireRule(entries.get(1), "difference_of_squares", "A^2 - B^2", "(A - B)*(A + B)");
        require(entries.get(2) instanceof ProfileDefinition profile
            && profile.id().equals("factorization_basics")
            && profile.enableTags().equals(List.of("factorization", "distributive"))
            && profile.disableTags().equals(List.of("complex_analysis")), "unexpected activation profile");

        var exportPack = parser.parse(Path.of(args[1]));
        require(exportPack.diagnostics().isEmpty() && exportPack.entries().size() == 1,
            "expected one portable export profile");
        require(exportPack.entries().getFirst() instanceof ProfileDefinition exportProfile
            && exportProfile.id().equals("factorization_export")
            && exportProfile.enableTags().equals(List.of("community-factorization-template"))
            && exportProfile.whitelist().equals(List.of("extract_common_factor", "difference_of_squares")),
            "unexpected export profile");

        var incomplete = parser.parse(Path.of(args[2]));
        require(incomplete.hasErrors() && incomplete.entries().isEmpty()
            && incomplete.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == Severity.ERROR
                    && diagnostic.message().equals("Missing required property 'replace'")),
            "rule without a replacement was not rejected");
        System.out.println("rule-package=factorization.regelsuche");
        System.out.println("rules=extract_common_factor,difference_of_squares");
        System.out.println("profile=factorization_basics");
        System.out.println("export-profile=factorization_export");
        System.out.println("negative=REJECTED");
    }

    private static void requireRule(Entry entry, String id, String pattern, String replacement) {
        require(entry instanceof RuleDefinition rule && rule.id().equals(id)
            && rule.pattern().equals(pattern) && rule.replace().equals(replacement)
            && rule.direction() == RuleDirection.BOTH, "unexpected rule: " + entry);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
