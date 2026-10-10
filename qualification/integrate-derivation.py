from pathlib import Path
import shutil
import sys
root = Path(sys.argv[1])
control = Path(__file__).parent
package = root / 'regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization'
for name in ('SearchDerivations.java', 'ComputationExplanations.java'):
    shutil.copyfile(control / name, package / name)
shutil.copyfile(control / 'SearchDerivationExplanationTest.java', root / 'regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization/SearchDerivationExplanationTest.java')
p = package / 'ComputationOptimizer.java'
s = p.read_text()
before = '''            return new OptimizationResult.Candidate(found.plan(),found.prepared(),evidence(request,found.plan(),proof,obligations),obligations,cost,
                OptimizationResult.SearchCompletion.IMPROVEMENT_FOUND,total);'''
after = '''            var receipt = evidence(request,found.plan(),proof,obligations);
            long beforeDerivation = work.used();
            var derivation = SearchDerivations.capture(request, receipt, found, work);
            total = Math.addExact(total, work.used() - beforeDerivation);
            if (total > budget.maximumWork()) return new OptimizationResult.BudgetExceeded("DERIVATION_CAPTURE_BUDGET_EXCEEDED", total);
            return new OptimizationResult.Candidate(found.plan(),found.prepared(),receipt,obligations,cost,
                OptimizationResult.SearchCompletion.IMPROVEMENT_FOUND,total,Optional.of(derivation));'''
assert s.count(before) == 1
s = s.replace(before, after)
before = '''        return proof;
    }
    /** Reference policy evaluator'''
after = '''        try {
            if (candidate.derivation().isPresent())
                SearchDerivations.replay(request, candidate, candidate.derivation().orElseThrow(), work);
        } catch (VerificationWork.Stopped stopped) {
            return stopped.cancelled ? new VerificationResult.Cancelled("CANCELLED")
                : new VerificationResult.BudgetExceeded("DERIVATION_REPLAY_BUDGET_EXCEEDED", work.used());
        } catch (IllegalArgumentException invalid) {
            return new VerificationResult.Unsupported(diagnostic(invalid));
        }
        return proof;
    }
    /** Reference policy evaluator'''
assert s.count(before) == 1
p.write_text(s.replace(before, after))
p = root / 'examples/external-consumers/java-optimization-java25/src/main/java/example/JavaOptimization.java'
s = p.read_text()
before = '        System.out.println("optimization=VERIFIED");'
after = '''        System.out.println("optimization=VERIFIED");
        var explained = ComputationExplanations.describe(preserve, candidate, CancellationToken.NONE);
        var derivation = explained.explanation().orElseThrow().derivation().orElseThrow();
        if (derivation.steps().isEmpty()
                || !derivation.steps().getFirst().before().equals(source.expression())
                || !derivation.steps().getLast().after().equals(candidate.plan().expression()))
            throw new AssertionError("selected search path lost in standalone distribution");
        System.out.println("derivation=REPLAYED_SELECTED_PATH");'''
assert s.count(before) == 1
p.write_text(s.replace(before, after))
p = root / 'regelsuche-optimization-sdk/README.md'
p.write_text(p.read_text() + '''\n## Recorded selected search paths\n\nOptimizer-produced candidates expose `derivation()`. It contains the exact retained\n`JointPlanSearch` witness, with rule identifiers and typed before/after output envelopes.\nThere is no second search and no source-name-specific explanation. A compound proposal\nis one recorded edge; it is not expanded into invented primitive identities.\n\n`reverify` and `ComputationExplanations.describe` replay a present derivation: evidence\nbinding, contiguous endpoints, regeneration of each claimed rule/target from the original\nbounded candidate inventory, and independent numeric checking of every edge are required.\nThey use the same work allowance/deadline as the final source-to-target proof. Cancellation,\nbudget exhaustion or invalid history produces no partial successful explanation. The original\nsource evaluation trace and numerical policy remain authoritative for Java behavior.\n\nThe previous Candidate constructor remains available and explicitly means unrecorded\nhistory. An absent history differs from a recorded zero-edge path (an unchanged value\ngraph with a different prepared schedule). Neither is padded with a fabricated rewrite.\nThese immutable records are replayable data, not a signed audit log or a timing result.\n''')
print('Staged selected-witness capture, independent replay and presentation; mathematical rules unchanged.')
