package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.reachability.BoundedReachabilityOracle;
import de.regelsuche.search.reachability.ReversibleFactorSearch;
import de.regelsuche.search.reachability.ReversibleFactorSearch.Mode;
import de.regelsuche.search.reachability.ReversibleFactorSearch.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Runs the entire preselected #620 syntax-exact panel, including complete negative closures. */
class ReversibleFactorComparisonTest {
    private static final AssumptionSignature EMPTY = AssumptionSignature.ofExpressions(List.of());
    private static final int CALL_LIMIT = 10_000;
    private final ReversibleFactorSearch search = new ReversibleFactorSearch();

    @Test void compareAgainstEveryReachableStateInEachDeclaredFiniteClosure() throws Exception {
        var corpus = HistoricalRediscoveryCorpus.load();
        assertEquals("53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422", corpus.contentSha256());
        var report = new StringBuilder("case\tdepth\tshortest\tclosure states\tclosure calls/proposals\tBFS forward/replay\tmeet forward/reverse/replay\tmeeting witness\n");
        var iterativeReport = new StringBuilder("case\tdepth\tshortest\tclosure calls/proposals\tBFS calls/proposals\tmeet calls/proposals\titerative forward calls/proposals\titerative replay calls/proposals\titerative status\titerative witness\n");
        var cases = corpus.cases().stream().filter(c -> c.targetRelation() == HistoricalRediscoveryCorpus.TargetRelation.SYNTAX_EXACT).toList();
        assertEquals(13, cases.size());
        for (var c : cases) compare(report, iterativeReport, c.id(), c.source(), c.target(), c.oracleMaxDepth(), c.oracleMaxVisitedStates());
        compare(report, iterativeReport, "grouped-three-step", "a*x+a*y+(b*x+b*y)", "(a+b)*(x+y)", 3, 400);
        compare(report, iterativeReport, "independent-two-step", "pair(a*x+a*y,b*x+b*y)", "pair(a*(x+y),b*(x+y))", 2, 400);
        var path = Path.of("build/reports/reversible-factor-search/comparison.tsv");
        Files.createDirectories(path.getParent());
        Files.writeString(path, report);
        var iterativePath = path.resolveSibling("iterative-deepening.tsv");
        Files.writeString(iterativePath, iterativeReport);
        assertEquals("17c4453bbbd6686ac9b4e0f54f6681ac103c354f3dfad49c435b610aabbf51b5",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(iterativePath))));
    }

    private void compare(StringBuilder out, StringBuilder iterativeReport, String id, String source, String target, int depth, int maxStates) {
        var limits = new BoundedReachabilityOracle.Budget(depth, depth, maxStates, 100_000);
        var closure = search.closure(source, EMPTY, limits, CALL_LIMIT);
        assertTrue(closure.oracle().closureComplete(), () -> id + " " + closure.oracle().detailCode());
        String endpoint = ExpressionFormatter.format(new ExpressionParser().parseTerm(target));
        OptionalInt distance = closure.oracle().states().stream().filter(s -> s.expression().equals(endpoint))
            .mapToInt(BoundedReachabilityOracle.RetainedState::primitivePathWork).min();
        var bfs = search.search(source, target, EMPTY, depth, CALL_LIMIT, Mode.ONE_SIDED);
        var meet = search.search(source, target, EMPTY, depth, CALL_LIMIT, Mode.BIDIRECTIONAL);
        var iterative = search.search(source, target, EMPTY, depth, CALL_LIMIT, Mode.ITERATIVE_DEEPENING);
        for (var policy : List.of(bfs, meet, iterative)) {
            assertEquals(distance.isPresent() ? Status.REACHED : Status.NO_PATH_WITHIN_DEPTH, policy.status(), id);
            if (distance.isPresent()) {
                assertEquals(distance.getAsInt(), policy.witness().size(), id);
                assertTrue(search.replays(source, target, EMPTY, policy.witness()), id);
            }
        }
        iterativeReport.append(String.join("\t", id, Integer.toString(depth), Integer.toString(distance.orElse(-1)),
            count(closure.work()), total(bfs.work()), total(meet.work()), count(iterative.work().forward()),
            count(iterative.work().replay()), iterative.status().name(),
            iterative.witness().stream().map(step -> step.primitive().rule() + " -> " + step.to().expression()).toList().toString())).append('\n');
        out.append(String.join("\t", id, Integer.toString(depth), Integer.toString(distance.orElse(-1)),
            Integer.toString(closure.oracle().states().size()), count(closure.work()),
            count(bfs.work().forward()) + " " + count(bfs.work().replay()),
            count(meet.work().forward()) + " " + count(meet.work().reverse()) + " " + count(meet.work().replay()),
            meet.witness().stream().map(s -> s.primitive().rule() + " -> " + s.to().expression()).toList().toString())).append('\n');
    }

    private static String total(ReversibleFactorSearch.Work work) {
        return work.totalCalls() + "/" + (work.forward().generated() + work.reverse().generated() + work.replay().generated());
    }

    private static String count(ReversibleFactorSearch.Count count) { return count.calls() + "/" + count.generated(); }
}
