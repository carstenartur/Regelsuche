from pathlib import Path
import sys
root = Path(sys.argv[1])
package = root / 'regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization'
def replace(path, old, new, count=1):
    text = path.read_text()
    assert text.count(old) == count, (path, old, text.count(old))
    path.write_text(text.replace(old, new))
replace(package/'SearchDerivation.java', 'int candidateLimit, List<Step> steps)', 'int candidateLimit, long generationBudget, List<Step> steps)')
replace(package/'SearchDerivation.java', 'candidateLimit > 1024)', 'candidateLimit > 1024 || generationBudget < 1)')
replace(package/'SearchDerivations.java', 'request.budget().maximumCandidates(), steps)', 'request.budget().maximumCandidates(), request.budget().maximumWork(), steps)')
replace(package/'SearchDerivations.java', 'new JavaCandidateGenerator(request, work)', 'new JavaCandidateGenerator(request, work, derivation.generationBudget())')
replace(package/'JavaCandidateGenerator.java', '''    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work) {
        this.request = request; this.work = work; backend = new JavaNumericBackend(request.plan().inputs());
        algebra = new JavaAlgebraCandidates(work, request.budget().maximumWork());
    }''', '''    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work) {
        this(request, work, request.budget().maximumWork());
    }
    /** Replay keeps the recorded generation configuration, not a fresh work allowance. */
    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work, long generationBudget) {
        if (generationBudget < 1) throw new IllegalArgumentException("POSITIVE_GENERATION_BUDGET_REQUIRED");
        this.request = request; this.work = work; backend = new JavaNumericBackend(request.plan().inputs());
        algebra = new JavaAlgebraCandidates(work, generationBudget);
    }''')
tests = root / 'regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization'
p = tests / 'SearchDerivationTest.java'
text = p.read_text()
assert text.count('new SearchDerivation(f.candidate.evidence(), 64,') == 7
p.write_text(text.replace('new SearchDerivation(f.candidate.evidence(), 64,', 'new SearchDerivation(f.candidate.evidence(), 64, f.request.budget().maximumWork(),'))
p = tests / 'SearchDerivationExplanationTest.java'
replace(p, '    private static OptimizationResult.Candidate legacy', '''    @Test void callerAllowanceDoesNotChangeTheRecordedGenerationConfiguration() {
        var f = ComputationExplanationsTest.fixture();
        var r = f.request();
        long previous = -1;
        for (long limit : new long[]{r.budget().maximumWork(), 2 * r.budget().maximumWork()}) {
            var changed = new OptimizationRequest(r.plan(), r.sourceTrace(), r.selectedKinds(), r.semanticsRevision(),
                    r.assumptions(), r.safetyProfile(), r.goal(), new OptimizationBudget(limit, 20_000, 64, 5000), r.checkedPolicy());
            var work = new VerificationWork(changed, CancellationToken.NONE);
            assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverifyWithin(changed, f.candidate(), work));
            if (previous >= 0) assertEquals(previous, work.used());
            previous = work.used();
        }
    }
    private static OptimizationResult.Candidate legacy''')
print('Replay inventory configuration is recorded separately; every replay operation still consumes the one caller allowance.')
