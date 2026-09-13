package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.Measurement;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationPlan.Configuration;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.transform.MeasuredTransformationEngine;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Actual authorized-program replay and native search under the complete selected work configuration. */
public record LearnedSelectedProgramReplayEvidence(String schema, Configuration configuration,
    LearnedSelectedProgramAuthorizationBundle bundle, Map<String, String> leafAuthorizationHashes,
    List<Case> cases, String contentHash) {
    public static final String SCHEMA = "regelsuche.learned-selected-program-runtime-replay/v1";

    public LearnedSelectedProgramReplayEvidence {
        if (!SCHEMA.equals(schema)) { throw new IllegalArgumentException("unsupported selected-program replay"); }
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(bundle, "bundle");
        if (!bundle.selectedConfigurationHash().equals(configuration.contentHash())) {
            throw new IllegalArgumentException("replay bundle differs from complete selected configuration");
        }
        leafAuthorizationHashes = Map.copyOf(Objects.requireNonNull(leafAuthorizationHashes, "leafAuthorizationHashes"));
        leafAuthorizationHashes.values().forEach(value -> EvolutionGenome.requireSha256(value, "leaf authorization hash"));
        if (!leafAuthorizationHashes.keySet().equals(configuration.candidate().genome().rewrites().stream()
                .map(EvolutionGenome.RewriteGene::geneId).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("replay must retain authority for every executing genome gene");
        }
        cases = Objects.requireNonNull(cases, "cases").stream().sorted(Comparator.comparing(item -> item.input().caseId())).toList();
        if (cases.isEmpty() || cases.stream().map(item -> item.input().caseId()).distinct().count() != cases.size()) {
            throw new IllegalArgumentException("selected-program replay requires unique nonempty inputs");
        }
        for (var item : cases) {
            item.search().requireBudget(configuration.effectiveBudget());
            var pathBudget = item.programReplay().pathBudget();
            if (!pathBudget.present() || pathBudget.primitiveRewriteUnits() != configuration.effectiveBudget().maxPrimitiveSteps()
                    || pathBudget.exactTheoryWorkUnits() != 0) {
                throw new IllegalArgumentException("program replay differs from the selected primitive/theory allowance");
            }
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(configuration, bundle, leafAuthorizationHashes, cases));
    }

    static LearnedSelectedProgramReplayEvidence capture(EvolutionRewriteProgramFinalTestPlan plan,
        LearnedSelectedProgramAuthorizationBundle bundle, Map<String, String> leafHashes,
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled, MeasuredTransformationEngine engine,
        List<Input> inputs) {
        var configuration = plan.selectedConfiguration();
        var budget = configuration.effectiveBudget();
        var evaluator = new NativeEvolutionRewriteProgramValidationEvaluator();
        var interpreter = new RewriteProgramInterpreter();
        var cases = List.copyOf(Objects.requireNonNull(inputs, "inputs")).stream().map(input -> {
            var program = LearnedRewriteProgramReplayEvidence.ReplayCase.capture(input.caseId(), input.inputExpression(),
                interpreter.executeWithWorkBudget(compiled.program(), input.inputExpression(), new PathBudget(budget.maxPrimitiveSteps(), 0)));
            var search = evaluator.evaluateSide(engine, input.inputExpression(), input.targetExpression(), input.assumptions(), budget);
            return new Case(input, program, search);
        }).sorted(Comparator.comparing(item -> item.input().caseId())).toList();
        return new LearnedSelectedProgramReplayEvidence(SCHEMA, configuration, bundle, leafHashes, cases,
            EvolutionProgramValidationJson.hash(material(configuration, bundle, leafHashes, cases)));
    }

    public List<Input> inputs() { return cases.stream().map(Case::input).toList(); }
    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static LearnedSelectedProgramReplayEvidence fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, LearnedSelectedProgramReplayEvidence.class);
    }

    public record Input(String caseId, String inputExpression, String targetExpression, List<String> assumptions) {
        public Input {
            EvolutionValidationArtifactSupport.requireText(caseId, "caseId");
            inputExpression = ExpressionFormatter.format(new ExpressionParser().parseTerm(inputExpression));
            targetExpression = ExpressionFormatter.format(new ExpressionParser().parseTerm(targetExpression));
            assumptions = AssumptionSignature.ofExpressions(Objects.requireNonNull(assumptions, "assumptions")).normalizedAssumptions();
        }
    }

    public record Case(Input input, LearnedRewriteProgramReplayEvidence.ReplayCase programReplay, Measurement search) {
        public Case {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(programReplay, "programReplay");
            Objects.requireNonNull(search, "search");
            if (!input.caseId().equals(programReplay.caseId()) || !input.inputExpression().equals(programReplay.inputExpression())
                    || (search.reached() && (!search.path().getFirst().equals(input.inputExpression())
                        || !search.path().getLast().equals(input.targetExpression())))) {
                throw new IllegalArgumentException("selected replay paths differ from their committed runtime input");
            }
        }

        /** Separate replay challenge plus search work; incomplete observations cannot claim a complete total. */
        public Long totalReplayWorkUnits() {
            return !programReplay.complete() || !search.complete() ? null : EvolutionRewriteProgramValidationEvidence.sum(
                programReplay.workMetrics().totalWorkUnits(), search.totalWorkUnits());
        }
    }

    private static Map<String, Object> material(Configuration configuration, LearnedSelectedProgramAuthorizationBundle bundle,
        Map<String, String> leaves, List<Case> cases) {
        return EvolutionProgramValidationJson.material(SCHEMA, "configuration", configuration, "bundle", bundle,
            "leafAuthorizationHashes", new TreeMap<>(leaves), "cases", cases);
    }
}
