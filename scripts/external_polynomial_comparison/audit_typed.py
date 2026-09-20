"""Offline mathematical/lineage audit, separate from timed solver execution.

Reuses the frozen independent rational-polynomial judge. Checks selected macro
intermediates, not just their final endpoints. This does not authenticate an
execution, replay registered Java rules, or constitute a Lean kernel proof.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
from fractions import Fraction
from .polynomial import LIMITS, polynomial
from .run import canonical


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _natural(value) -> int:
    _require(type(value) is int and value >= 0, 'nonnegative integer work required')
    return value


def _object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, 'duplicate JSON field')
        result[key] = value
    return result


def _load(text: str):
    _require(isinstance(text, str), 'JSON text required')
    return json.loads(text, object_pairs_hook=_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite JSON number')))


def _typed_polynomial(document: str):
    """Interpret data-only AST tags in the existing bounded independent judge."""
    _require(isinstance(document, str) and len(document) <= 1_048_576, 'typed document limit')
    root = _load(document)
    _require(isinstance(root, dict) and set(root) == {'schema', 'expression'}
             and root['schema'] == 'regelsuche.typed-move-expression/v1', 'unknown typed schema')
    visited = 0
    operators = {'ADD': '+', 'SUB': '-', 'MUL': '*', 'DIV': '/', 'POW': '**'}

    def emit(node, depth=0):
        nonlocal visited
        visited += 1
        _require(visited <= LIMITS['astNodes'] and depth <= 128, 'typed AST limit')
        _require(isinstance(node, dict), 'typed AST node required')
        kind = node.get('type')
        if kind == 'number':
            _require(set(node) == {'type', 'value'}, 'number fields')
            text = node['value']
            _require(isinstance(text, str) and len(text) <= 1300
                     and re.fullmatch(r'-?(0|[1-9][0-9]*)(/[1-9][0-9]*)?', text) is not None,
                     'canonical rational required')
            value = Fraction(text)
            _require(str(value) == text, 'noncanonical rational')
            _require(max(abs(value.numerator).bit_length(), value.denominator.bit_length()) <= LIMITS['coefficientBits'],
                     'rational bit limit')
            return '(' + text + ')'
        if kind == 'variable':
            _require(set(node) == {'type', 'name'} and isinstance(node['name'], str)
                     and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', node['name']) is not None,
                     'variable fields')
            return node['name']
        _require(kind == 'binary' and set(node) == {'type', 'operator', 'left', 'right'}, 'unsupported typed node')
        _require(isinstance(node['operator'], str) and node['operator'] in operators, 'unsupported typed operator')
        return '(' + emit(node['left'], depth + 1) + operators[node['operator']] + emit(node['right'], depth + 1) + ')'

    # Parentheses preserve the producer grouping; this is interpretation, not a rewrite.
    # The unchanged judge also enforces input, variable, exponent and polynomial limits.
    return polynomial(emit(root['expression']))


def audit_response(source: str, response: dict) -> dict:
    """Check one Java candidate, including diagnostic over-budget candidates."""
    _require(response.get('status') == 'CANDIDATE', 'candidate response required')
    _require(response.get('input') == source and response.get('target') == ''
             and response.get('targetReached') is False, 'source-only request binding')
    search = _load(response['search'])
    _require(search.get('reached') is False, 'source-only target hit')
    metrics = search['metrics']
    search_work = sum(_natural(metrics[key]) for key in ('primitiveWork', 'searchWork', 'verificationWork'))
    _require(search_work == _natural(search['totalWork']), 'search work partition mismatch')
    _require(search_work + _natural(response['selectionWork']) + _natural(response['selectedReplayWork'])
             == _natural(response['totalWork']), 'query work partition mismatch')
    _require(isinstance(response['witness'], list), 'witness list required')
    _require(len(response['witness']) <= 32, 'selected path bound')
    expected = polynomial(source)
    cache = {}

    def check_expression(document):
        if document not in cache:
            cache[document] = _typed_polynomial(document)
        _require(cache[document] == expected, 'invalid intermediate polynomial identity')

    cursor = response['typedInput']
    check_expression(cursor)
    check_expression(response['typedOutput'])
    _require(polynomial(response['output']) == expected, 'output/typed endpoint mismatch')
    counts = {'selectedEdges': 0, 'primitiveSteps': 0, 'learnedEdges': 0}
    replay_work = 0
    state = None
    for step in response['witness']:
        _require(step['source'] == cursor, 'disconnected selected path')
        move, receipt = step['move'], step['verification']
        _require(receipt.get('accepted') is True and isinstance(receipt.get('receipts'), list)
                 and bool(receipt['receipts']) and all(isinstance(r, str) and r for r in receipt['receipts']),
                 'missing accepted replay receipt')
        replay_work += _natural(receipt['work'])
        _require(move['sourceKind'] in ('PRIMITIVE', 'LEARNED'), 'unsupported move source')
        _require(move['assumptions'] == [] and move['transformation']['assumptions'] == [],
                 'unproved dynamic premises in unconditional pilot')
        expansion = move['primitiveExpansion']
        _require(isinstance(expansion, list) and 1 <= len(expansion) <= 8, 'primitive expansion required')
        if move['sourceKind'] == 'PRIMITIVE':
            _require(len(expansion) == 1, 'primitive edge has multiple steps')
        _require(move['transformation']['primitiveRuleIds'] == [item['rule'] for item in expansion],
                 'primitive rule lineage mismatch')
        for primitive in expansion:
            _require(primitive['assumptions'] == [], 'unproved primitive premises')
            _require(primitive['primitiveRuleIds'] == [primitive['rule']], 'nonprimitive expansion item')
            cursor = primitive['transformedExpression']
            check_expression(cursor)
        _require(cursor == step['target'] == move['transformation']['transformedExpression'],
                 'primitive expansion endpoint mismatch')
        # Bind to an actually admitted edge and its exact predecessor state; identical
        # expressions at different depths or with different premises are not enough.
        admitted = [event for event in search['events']
                    if event['decision'] == 'ENQUEUED'
                    and event['source']['expression'] == step['source']
                    and event['target']['expression'] == step['target']
                    and event['move'] == move and event['verification'] == receipt
                    and (event['source'] == state if state is not None else
                         event['source']['searchDepth'] == event['source']['primitiveDepth'] == 0)]
        _require(bool(admitted), 'selected path not admitted by search')
        edge = admitted[0]
        _require(edge['source']['assumptions'] == edge['target']['assumptions'] == [], 'unexpected state premises')
        _require(edge['target']['searchDepth'] == edge['source']['searchDepth'] + 1
                 and edge['target']['primitiveDepth'] == edge['source']['primitiveDepth'] + len(expansion),
                 'selected depth accounting mismatch')
        state = edge['target']
        counts['selectedEdges'] += 1
        counts['primitiveSteps'] += len(expansion)
        counts['learnedEdges'] += move['sourceKind'] == 'LEARNED'
    _require(cursor == response['typedOutput'], 'witness does not reach typed output')
    _require(replay_work == _natural(response['selectedReplayWork']), 'selected replay work mismatch')
    return counts


def audit_directory(output: Path) -> dict:
    # Preserve the existing manifest, full matrix, frozen models, eligibility and
    # timing verification. The additional audit never rewrites retained evidence.
    from .run_typed import verify
    verify(output)
    rows = _load((output / 'rows.json').read_text(encoding='utf-8'))
    metadata = _load((output / 'metadata.json').read_text(encoding='utf-8'))
    profiles = {}
    for row in rows:
        if row['profile'].startswith('SYMPY_') or row['response']['status'] != 'CANDIDATE':
            continue
        try:
            counts = audit_response(row['source'], row['response'])
        except (ValueError, KeyError, TypeError, RecursionError) as error:
            raise ValueError(f"{row['case']}/{row['profile']}/{row['repetition']}: {error}") from error
        profile = profiles.setdefault(row['profile'], dict(candidates=0, selectedEdges=0, primitiveSteps=0,
                                                          learnedEdges=0, queryWork=0))
        profile['candidates'] += 1
        profile['queryWork'] += row['response']['totalWork']
        for key, value in counts.items():
            profile[key] += value
    for name, profile in profiles.items():
        profile['trainingWork'] = metadata['setup'][name]['training']['trainingWork']
        profile['queryPlusTrainingWork'] = profile['queryWork'] + profile['trainingWork']
    return {'schema': 'regelsuche.typed-selected-path-audit/v1', 'revision': metadata['revision'],
            'scope': 'OFFLINE_EXACT_POLYNOMIAL_AND_SELECTED_LINEAGE_AUDIT;NOT_TIMED_QUERY_WORK;NOT_EXECUTION_AUTHENTICATION',
            'rows': len(rows), 'profiles': profiles}


def main() -> None:
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(canonical(audit_directory(args.output)).decode('utf-8'), end='')


if __name__ == '__main__':
    main()
