"""Evaluate a previously committed public recurrence freeze without running synthesis."""
import argparse
import hashlib
from fractions import Fraction
from pathlib import Path

from .audit_typed import _load
from .historical_judge import audit_law, check_task, polynomial, require
from .run import canonical

SOURCE_HASH = 'cf7b3c01c5f83768ac8f8552d82a502e839bc5f78e9504343e7c477a0f28f7ad'
FREEZE_HASH = '342018a587b904af0c02efd7bd3aa09a6292ae7206f2185625993ab21d2f5711'
REFERENCE = {'polynomial': 'x0*(x0+x1)-x1^2', 'evolution': 'ALTERNATING', 'initialValue': '-1'}


def correspondence(request, law, reference_request, reference):
    """Replay the independent step/base before comparing a scaled historical representative."""
    audit_law(request, law)
    for field in ('linear', 'offset', 'initial'):
        if request[field] != reference_request[field]:
            return {'decision': 'DIFFERENT_SOURCE'}
    if law['evolution'] != reference['evolution']:
        return {'decision': 'DIFFERENT_EVOLUTION'}
    observed = polynomial(law['polynomial'])
    expected = polynomial(reference['polynomial'])
    if observed.keys() == expected.keys() and expected:
        first = next(iter(expected))
        scale = observed[first] / expected[first]
        if scale and all(observed[m] == scale * c for m, c in expected.items()):
            if Fraction(law['initialValue']) == scale * Fraction(reference['initialValue']):
                return {'decision': 'MATCH', 'scale': str(scale)}
    return {'decision': 'DIFFERENT_REPRESENTATION_OR_BASE'}


def evaluate(source_bytes, freeze_bytes):
    require(hashlib.sha256(source_bytes).hexdigest() == SOURCE_HASH, 'source commitment mismatch')
    require(hashlib.sha256(freeze_bytes).hexdigest() == FREEZE_HASH, 'candidate freeze commitment mismatch')
    source, freeze = (_load(raw.decode('utf-8')) for raw in (source_bytes, freeze_bytes))
    require(freeze['sourceHash'] == 'sha256:' + SOURCE_HASH and freeze['version'] == source['version'] == 1,
            'wrong source/version binding')
    rows = []
    reference_request = source['cases'][0]['request']
    audit_law(reference_request, REFERENCE)
    for given, frozen in zip(source['cases'], freeze['cases'], strict=True):
        request = given['request']
        check_task(request)
        require(given['id'] == frozen['id'] and request == frozen['request'], 'frozen row rebound to a different source')
        work = frozen['consumedWork']
        require(type(work) is int and 0 <= work <= request['workBudget'], 'formation work outside allowance')
        status, laws = frozen['status'], frozen['laws']
        require(status in ('COMPLETE', 'BUDGET_INCONCLUSIVE', 'UNSUPPORTED') and (status == 'COMPLETE' or not laws),
                'invalid terminal formation')
        candidates = [dict(index=i, law=law, replay='VERIFIED',
                           **correspondence(request, law, reference_request, REFERENCE)) for i, law in enumerate(laws)]
        rows.append(dict(id=given['id'], formation=status, consumedWork=work, candidates=candidates))
    return dict(version=1, sourceHash=SOURCE_HASH, freezeHash=FREEZE_HASH, reference=REFERENCE,
                referenceSource=source['cases'][0]['id'], rows=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sources', type=Path)
    parser.add_argument('freeze', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.write_bytes(canonical(evaluate(args.sources.read_bytes(), args.freeze.read_bytes())))


if __name__ == '__main__':
    main()
