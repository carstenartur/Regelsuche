#!/usr/bin/env python3
"""Independently check hashes, row parity, work sums and witness accounting (not a substitute for Java exact replay)."""
import hashlib
import json
from pathlib import Path
import sys

if not __debug__:
    raise RuntimeError("verify-learned-scheduling.py must not run with Python assertions disabled")

root = Path(sys.argv[1])
manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
assert manifest['schema'] == 'regelsuche.learned-scheduling-manifest/v1'
for name, digest in manifest['files'].items():
    assert name != 'walltime-diagnostic.csv'
    assert 'sha256:' + hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
protocol = json.loads((root / 'protocol.json').read_text(encoding='utf-8'))
summary = json.loads((root / 'summary.json').read_text(encoding='utf-8'))
rows = [json.loads(path.read_text(encoding='utf-8')) for path in sorted((root / 'runs').glob('*.json'))]
expected = {(case['id'], config['id'], int(budget)) for case in protocol['cases']
            for config in protocol['configurations'] for budget in protocol['budgets']}
actual = {(row['caseId'], row['configuration'], row['budget']) for row in rows}
assert actual == expected and len(rows) == len(expected) == summary['rows']
model = json.loads((root / 'model.json').read_text(encoding='utf-8'))
frozen_ids = {trace['id'] for trace in model['traces']}
by_id = {row['id']: row for row in rows}
for row in rows:
    search = row['search']
    if search is None:
        assert row['status'] == 'UNSUPPORTED_POLYNOMIAL_FRAGMENT'
        continue
    metrics = search['metrics']
    assert search['totalWork'] == sum(metrics[name] for name in ('primitiveWork', 'searchWork', 'verificationWork'))
    assert metrics['generatedSuccessors'] == metrics['consumedSuccessors'] + metrics['unconsumedSuccessors']
    assert metrics['consumedSuccessors'] == len(search['events'])
    assert metrics['discardedSuccessors'] == sum(event['decision'] != 'ENQUEUED' for event in search['events'])
    for event in search['events']:
        assert event['target']['expression'] == event['move']['transformation']['transformedExpression']
        assert event['target']['searchDepth'] == event['source']['searchDepth'] + 1
    if search['reached']:
        assert search['totalWork'] <= row['budget']
        assert len(search['witness']) == metrics['firstHitDepth']
        primitive_steps = 0
        for step in search['witness']:
            move, proof = step['move'], step['verification']
            assert proof['accepted'] and not move['assumptions']
            assert len(proof['receipts']) == len(move['primitiveExpansion'])
            assert step['target']['expression'] == move['transformation']['transformedExpression']
            primitive_steps += len(move['primitiveExpansion'])
        assert primitive_steps == metrics['firstHitPrimitiveDepth']
for identity in summary['newlyReachableWitnesses']:
    row = by_id[identity]
    assert row['search']['reached']
    assert any(step['move']['ruleId'] in frozen_ids for step in row['search']['witness'])
    baseline = by_id[f"{row['caseId']}-BASE-{row['budget']}"]
    assert not baseline['search']['reached']
assert summary['productionQualified'] is False
assert model['trainingWorkAccountingComplete'] is True
assert model['trainingWork'] == sum(model['trainingWorkComponents'].values()) == summary['trainingWork']
assert all(model['trainingWorkComponents'][name] > 0 for name in ('activityTraining', 'historyTraining', 'utilityTraining'))
saved, count = summary['amortizationPairedWorkSaved'], summary['amortizationCommonCases']
expected_payback = None if saved <= 0 else (model['trainingWork'] * count + saved - 1) // saved
assert summary['amortizationPoint'] == expected_payback
print(f"Verified {len(rows)} matched rows, all artifact hashes and retained work/witness accounting.")
