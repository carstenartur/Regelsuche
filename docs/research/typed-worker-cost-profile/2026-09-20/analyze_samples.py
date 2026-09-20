from __future__ import annotations
from collections import Counter, defaultdict
import csv
from datetime import datetime
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent

def method_names(stack):
    return [re.sub(r'\$\$Lambda\.0x[0-9a-f]+', '$$Lambda', f.rsplit(':', 1)[0]) for f in stack.split(';') if f]

def contains(names, text): return any(text in name for name in names)

def mechanism(names):
    # Mutually exclusive; fine-grained mechanisms take precedence over their broad callers.
    if contains(names, 'TypedPrimitiveCandidateCache'): return 'cache bookkeeping'
    if contains(names, 'CompiledAstReplayCodec') or contains(names, 'AstReplayJson'): return 'typed JSON codec'
    if contains(names, 'LearnedSchedulingArtifacts'): return 'result/model/response JSON serialization'
    if contains(names, 'canonicalAstNodeCount') or contains(names, 'PolynomialNormalizer') or contains(names, 'ExpressionCanonicalizer'):
        return 'canonicalization and polynomial normalization'
    if any(name.startswith('de.regelsuche.transform.PatternExpr') and (name.endswith('.match') or name.endswith('.instantiate')) for name in names):
        return 'mathematical pattern match/instantiate'
    if contains(names, 'lambda$primitiveReplay') or contains(names, 'TypedProgramMoveProvider.lambda$verifier') or contains(names, 'CompiledAstRewriteProgram.replay'):
        return 'remaining replay/verification'
    if contains(names, 'AstRewriteTransport.generate') or contains(names, 'TypedMoveSearch$1.candidates') or contains(names, 'TypedProgramMoveProvider.candidates'):
        return 'remaining candidate generation'
    if any(contains(names, s) for s in ('MoveSearch.', 'MoveSearch$', 'TypedSourceOnlySearch.', 'StagedMovePicker.')):
        return 'remaining search/path/incumbent management'
    if contains(names, 'ExactPolynomialAnalysis.alphaIdentity') or contains(names, 'TypedExternalPolynomialComparisonWorker.modelJson'):
        return 'source exclusion/frozen-model checks'
    return 'other Java/runtime/boundary'

def purpose(names):
    if contains(names, 'TypedExternalPolynomialComparisonWorker.modelJson'): return 'per-query frozen-model reconstruction/check'
    if contains(names, 'LearnedSchedulingArtifacts.resultJson'): return 'search evidence export'
    if contains(names, 'TypedProgramMoveProvider.lambda$verifier'): return 'learned macro verification/replay'
    if contains(names, 'lambda$primitiveReplay'): return 'primitive verification/replay'
    if contains(names, 'TypedProgramMoveProvider.candidates'): return 'learned macro generation'
    if contains(names, 'TypedMoveSearch$1.candidates'): return 'primitive generation'
    if contains(names, 'TypedSourceOnlySearch.search'): return 'path/state/incumbent management'
    if contains(names, 'ExactPolynomialAnalysis.alphaIdentity'): return 'TRAIN exclusion identity check'
    if contains(names, 'LearnedSchedulingArtifacts.json'): return 'worker JSON response serialization'
    return 'other Java/runtime/boundary'

FEATURES = {
    'typed JSON codec': lambda n: contains(n, 'CompiledAstReplayCodec') or contains(n, 'AstReplayJson'),
    'candidate generation (primitive or macro)': lambda n: contains(n, 'TypedMoveSearch$1.candidates') or contains(n, 'TypedProgramMoveProvider.candidates'),
    'primitive generation': lambda n: contains(n, 'TypedMoveSearch$1.candidates'),
    'macro generation': lambda n: contains(n, 'TypedProgramMoveProvider.candidates'),
    'primitive verification/replay': lambda n: contains(n, 'lambda$primitiveReplay'),
    'macro verification/replay': lambda n: contains(n, 'TypedProgramMoveProvider.lambda$verifier') or contains(n, 'CompiledAstRewriteProgram.replay'),
    'typed state decoding': lambda n: contains(n, 'TypedMoveSearch$State.decode'),
    'canonical size check': lambda n: contains(n, 'canonicalAstNodeCount'),
    'mathematical pattern matching': lambda n: any(x.startswith('de.regelsuche.transform.PatternExpr') and x.endswith('.match') for x in n),
    'JSON artifact/response serialization': lambda n: contains(n, 'LearnedSchedulingArtifacts.json'),
    'per-query frozen-model reconstruction/check': lambda n: contains(n, 'TypedExternalPolynomialComparisonWorker.modelJson'),
    'cache bookkeeping': lambda n: contains(n, 'TypedPrimitiveCandidateCache'),
    'numeric text regular-expression validation': lambda n: contains(n, 'ExactRational.fromCanonicalText') and contains(n, 'java.util.regex.Pattern'),
}

def iso_nanos(value):
    # Runtime events need only phase-level discrimination; sub-microsecond rounding is immaterial here.
    return round(datetime.fromisoformat(value).timestamp() * 1e9)

def seconds(value):
    assert value.startswith('PT') and value.endswith('S'), value
    return float(value[2:-1])

def counter_rows(counter, denominator):
    return [{'name': name, 'value': value, 'percent': 100*value/denominator if denominator else 0}
            for name, value in counter.most_common()]

def analyze(profile):
    base = ROOT / profile
    meta = json.loads((base / 'metadata.json').read_text())
    phases = {row['phase']: row for row in meta['phases']}
    phase_counters = defaultdict(lambda: {'execution': 0, 'allocation': 0, 'weight': 0})
    mech_cpu, mech_alloc, purpose_cpu, purpose_alloc = Counter(), Counter(), Counter(), Counter()
    inc_cpu, inc_alloc, types, leaves, project_stacks, threads = Counter(), Counter(), Counter(), Counter(), Counter(), Counter()
    nested = defaultdict(lambda: Counter())
    truncated = 0
    with gzip.open(base / 'samples.tsv.gz', 'rt') as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            epoch = int(row['epochNanos'])
            phase = next((name for name,p in phases.items() if p['startEpochNanos'] <= epoch <= p['endEpochNanos']), 'between-phases')
            execution = row['event'] == 'jdk.ExecutionSample'
            weight = int(row['weight'])
            phase_counters[phase]['execution' if execution else 'allocation'] += 1
            if not execution: phase_counters[phase]['weight'] += weight
            if phase != 'queries': continue
            names = method_names(row['stack'])
            mech, purp = mechanism(names), purpose(names)
            if row['truncated'] == 'true': truncated += 1
            if execution:
                mech_cpu[mech] += 1; purpose_cpu[purp] += 1
                leaves[names[0] if names else '<no stack>'] += 1
                project_stacks[' > '.join(n for n in names if n.startswith('de.regelsuche'))] += 1
                threads[row['thread']] += 1
            else:
                mech_alloc[mech] += weight; purpose_alloc[purp] += weight
                types[row['allocatedClass']] += weight
            for feature, check in FEATURES.items():
                if check(names):
                    (inc_cpu if execution else inc_alloc)[feature] += 1 if execution else weight
                    if execution: nested[feature][mech] += 1
    query = phase_counters['queries']; sample_total=query['execution']; weight_total=query['weight']
    runtime=json.loads((base/'runtime-events.json').read_text())['recording']['events']
    query_events=[event for event in runtime if phases['queries']['startEpochNanos'] <= iso_nanos(event['values']['startTime']) <= phases['queries']['endEpochNanos']]
    gc=[e['values'] for e in query_events if e['type']=='jdk.GarbageCollection']
    gc_cpu=[e['values'] for e in query_events if e['type']=='jdk.GCCPUTime']
    heaps=[e['values']['heapUsed'] for e in query_events if e['type']=='jdk.GCHeapSummary']
    result={'profile':profile, 'phases':dict(phase_counters), 'queryTruncatedSamples':truncated,
        'queryExecutionThreads':dict(threads),
        'mechanismExecution':counter_rows(mech_cpu,sample_total), 'mechanismAllocationWeight':counter_rows(mech_alloc,weight_total),
        'purposeExecution':counter_rows(purpose_cpu,sample_total), 'purposeAllocationWeight':counter_rows(purpose_alloc,weight_total),
        'inclusiveExecution':counter_rows(inc_cpu,sample_total), 'inclusiveAllocationWeight':counter_rows(inc_alloc,weight_total),
        'inclusiveMechanismBreakdown':{feature:counter_rows(counter,inc_cpu[feature]) for feature,counter in nested.items()},
        'topLeafMethods':counter_rows(leaves,sample_total)[:30], 'topProjectStacks':counter_rows(project_stacks,sample_total)[:25],
        'topAllocationClasses':counter_rows(types,weight_total)[:20],
        'queryGarbageCollections':len(gc), 'queryGcPauseSeconds':sum(seconds(v['sumOfPauses']) for v in gc),
        'queryGcCpuSeconds':sum(seconds(v['userTime'])+seconds(v['systemTime']) for v in gc_cpu),
        'maxObservedHeapUsedBytes':max(heaps,default=None),
        'sampledAllocationWeightPerQuery':weight_total/meta['rows'],
        'method':'ExecutionSample counts and ObjectAllocationSample weights in controller-recorded query phase. Inclusive rows overlap. Exclusive mechanism rows use listed precedence; purpose rows classify broad caller. Weights estimate allocated bytes, not exact cumulative bytes, object counts or retained heap. No conversion of Java sample percentages into whole-process CPU seconds.'}
    (base/'analysis.json').write_text(json.dumps(result,indent=2)+'\n')
    return result

def main():
    results=[analyze(profile) for profile in ('BASE','LEARNED_NAIVE')]
    (ROOT/'analysis.json').write_text(json.dumps(results,indent=2)+'\n')
    for value in results:
        print(json.dumps({k:value[k] for k in ('profile','phases','mechanismExecution','mechanismAllocationWeight','purposeExecution','queryGarbageCollections','queryGcPauseSeconds','queryGcCpuSeconds','sampledAllocationWeightPerQuery')},indent=2))

if __name__=='__main__': main()
