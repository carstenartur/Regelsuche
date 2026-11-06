"""Fresh recurrence knowledge lifecycles with exact, independent law audits.

Public developmental probes, never a sealed holdout. Whole-worker CPU/RSS is
measured by wait4; logical work and controller CPU have separate scopes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time
from fractions import Fraction

from .audit_typed import _load, _require
from .polynomial import LIMITS, _add, _multiply, polynomial
from .run import canonical
from .run_schema import (AccountedSession, CPU_SCOPE, _file_inventory,
                         _java_provenance, snapshot_runtime, source_provenance,
                         verify_runtime)

PROTOCOL_HASH = '14d05fdfd24541ffcba67474d4e948d344e54c228793252db4fb684e62ca8ba5'
PROTOCOL_PATH = 'config/benchmarks/recurrence-knowledge-lifecycle-v1.json'
REVISION = 'regelsuche.recurrence-knowledge-worker/v1'
JAVA_WORKER = 'de.regelsuche.evolution.RecurrenceKnowledgeComparisonWorker'
PROFILES = ['BASELINE', 'EXACT_FROZEN', 'FAMILY']
COSTS = {'lessons', 'formation', 'preparation', 'validation', 'selection',
         'restoration', 'splitChecking', 'total'}
SUCCESS = {'initialize': 'INITIALIZED', 'learn': 'LEARNED', 'generalize': 'GENERALIZED',
           'save': 'SAVED', 'restore': 'RESTORED', 'run': 'RESULT'}


def natural(value):
    _require(type(value) is int and value >= 0, 'expected nonnegative integer')
    return value


def rational(value):
    _require(isinstance(value, str) and len(value) <= 100, 'invalid rational string')
    try:
        result = Fraction(value)
    except (ValueError, ZeroDivisionError) as error:
        raise ValueError('invalid rational') from error
    _require(str(result) == value, 'noncanonical rational')
    _require(max(abs(result.numerator).bit_length(), result.denominator.bit_length()) <= 128,
             'rational representation limit')
    return result


def check_task(task):
    _require(isinstance(task, dict) and set(task) == {'linear', 'offset', 'initial', 'degree', 'workBudget'},
             'task field mismatch')
    n = len(task['initial'])
    _require(1 <= n <= 3 and len(task['linear']) == n and len(task['offset']) == n,
             'task dimension mismatch')
    _require(type(task['degree']) is int and 1 <= task['degree'] <= 2, 'task degree limit')
    _require(0 < natural(task['workBudget']) <= 100000, 'task budget limit')
    for row in task['linear']:
        _require(isinstance(row, list) and len(row) == n, 'matrix dimension mismatch')
        for value in row:
            rational(value)
    for value in task['initial'] + task['offset']:
        rational(value)


def load_protocol(path: Path) -> dict:
    data = path.read_bytes()
    _require(hashlib.sha256(data).hexdigest() == PROTOCOL_HASH,
             'protocol differs from pre-execution registered bytes')
    p = _load(data.decode('utf-8'))
    _require(set(p) == {'revision', 'informationRegime', 'profiles', 'sequenceLengths', 'repetitions',
                        'setupTimeoutSeconds', 'queryTimeoutSeconds', 'acquisitionBudget', 'queryBudget',
                        'javaOptions', 'warmups', 'lessons', 'familyValidation', 'queries'}, 'protocol fields')
    _require(p['revision'] == 'regelsuche.recurrence-knowledge-lifecycle/v1'
             and p['informationRegime'] == 'PUBLIC_DEVELOPMENT' and p['profiles'] == PROFILES
             and p['sequenceLengths'] == [1, 8, 64] and p['repetitions'] == 3
             and p['setupTimeoutSeconds'] == 120 and p['queryTimeoutSeconds'] == 30
             and p['acquisitionBudget'] == 500000 and p['queryBudget'] == 100000
             and p['javaOptions'] == ['-Xmx512m'] and p['warmups'] == [], 'protocol limits')
    _require(len(p['lessons']) == 2 and len(p['familyValidation']) == 2 and len(p['queries']) == 64,
             'protocol acquisition/query shape')
    ids = []
    for query in p['queries']:
        _require(set(query) == {'id', 'task'} and isinstance(query['id'], str), 'query fields')
        ids.append(query['id']); check_task(query['task'])
    _require(len(set(ids)) == len(ids), 'duplicate query id')
    for lesson in p['lessons']:
        _require(set(lesson) == {'training', 'validation'} and len(lesson['training']) == len(lesson['validation']) == 1,
                 'lesson fields')
        for task in lesson['training'] + lesson['validation']:
            check_task(task)
    for task in p['familyValidation']:
        check_task(task)
    _require(LIMITS == {'inputCharacters': 2048, 'astNodes': 256, 'variables': 4,
                        'maxExponent': 12, 'maxTerms': 4096, 'coefficientBits': 2048}, 'judge limits changed')
    return p


def schedule(protocol):
    for repetition in range(protocol['repetitions']):
        for index, length in enumerate(protocol['sequenceLengths']):
            offset = (repetition + index) % len(protocol['profiles'])
            for profile in protocol['profiles'][offset:] + protocol['profiles'][:offset]:
                yield profile, length, repetition


def cost_total(costs):
    _require(isinstance(costs, dict) and set(costs) == COSTS, 'cost ledger fields')
    total = natural(costs['total'])
    _require(total == sum(natural(costs[k]) for k in COSTS - {'total'}), 'cost ledger partition mismatch')
    return total


def check_common(response, profile, status=None, generation=None):
    _require(isinstance(response, dict) and response.get('revision') == REVISION, 'worker revision mismatch')
    _require(response.get('profile') == profile, 'worker profile mismatch')
    _require(status is None or response.get('status') == status, 'worker operation failed')
    actual = response.get('generationId')
    _require(isinstance(actual, str) and bool(actual), 'missing generation identity')
    _require(generation is None or generation == actual, 'generation identity mismatch')
    costs = response.get('acquisitionCosts')
    _require(natural(response['acquisitionWork']) == cost_total(costs), 'acquisition work mismatch')
    for name in ('sessionSetupWork', 'recurrenceCount', 'recurrenceFamilyCount'):
        natural(response[name])
    if profile == 'BASELINE':
        _require(response['acquisitionWork'] == response['recurrenceCount'] == response['recurrenceFamilyCount'] == 0,
                 'baseline acquired knowledge')
    if profile == 'EXACT_FROZEN':
        _require(response['recurrenceFamilyCount'] == 0, 'exact profile contains family knowledge')
    return actual


def audit_response(query, response, profile, generation):
    """Verify a reported law with sparse Fraction arithmetic, no Java oracle."""
    try:
        check_task(query['task'])
        check_common(response, profile, 'RESULT', generation)
        _require(response.get('id') == query['id'] and response.get('task') == query['task'],
                 'request identity/task mismatch')
        task = query['task']
        _require(natural(response['totalWork']) <= task['workBudget'], 'query work budget exceeded')
        solved, reused = response.get('solved'), response.get('reused')
        _require(type(solved) is bool and type(reused) is bool, 'outcome flags are not boolean')
        outcome = response.get('outcome')
        _require(outcome in {'VERIFIED', 'NO_LAW_IN_ANSATZ', 'UNSUPPORTED', 'BUDGET_INCONCLUSIVE'},
                 'unknown recurrence outcome')
        _require(solved == (outcome == 'VERIFIED') and (not reused or solved), 'incoherent outcome flags')
        _require(profile != 'BASELINE' or not reused, 'baseline claims knowledge reuse')
        law = response.get('law')
        if not solved:
            _require(law is None, 'unsolved result contains a law')
            return {'valid': True, 'solved': False, 'outcome': outcome}
        _require(isinstance(law, dict) and set(law) == {'polynomial', 'evolution', 'initialValue'}, 'law fields')
        poly = polynomial(law['polynomial'])
        n = len(task['initial']); variables = {'x' + str(i) for i in range(n)}
        _require(any(m for m in poly), 'law must be nonconstant')
        _require(all(name in variables for monomial in poly for name, _ in monomial), 'law variable outside task')
        _require(all(sum(power for _, power in monomial) <= task['degree'] for monomial in poly), 'law degree exceeds task')
        sign = {'FIXED': 1, 'ALTERNATING': -1}.get(law['evolution'])
        _require(sign is not None, 'unknown evolution')
        images = {}
        for i in range(n):
            image = {(): rational(task['offset'][i])} if rational(task['offset'][i]) else {}
            for j, value in enumerate(task['linear'][i]):
                image = _add(image, {((f'x{j}', 1),): rational(value)})
            images[f'x{i}'] = image
        transformed = {}
        initial_value = Fraction(0)
        for monomial, coefficient in poly.items():
            term = {(): coefficient}; initial_term = coefficient
            for variable, power in monomial:
                for _ in range(power):
                    term = _multiply(term, images[variable])
                initial_term *= rational(task['initial'][int(variable[1:])]) ** power
            transformed = _add(transformed, term)
            initial_value += initial_term
        _require(transformed == {m: sign*c for m, c in poly.items()}, 'law does not satisfy exact affine identity')
        # Initial values can grow beyond input coefficient limits but remain bounded by judge arithmetic.
        reported = law['initialValue']
        _require(isinstance(reported, str) and len(reported) <= 2048
                 and str(Fraction(reported)) == reported and Fraction(reported) == initial_value,
                 'law initial value mismatch')
        return {'valid': True, 'solved': True, 'outcome': outcome}
    except (ValueError, KeyError, TypeError, ZeroDivisionError, RecursionError, OverflowError) as error:
        return {'valid': False, 'solved': False, 'error': f'{type(error).__name__}: {error}'}


class ReceiptSession(AccountedSession):
    """Publish close receipts even when Session.__init__ fails before READY."""
    def __init__(self, command, stderr, timeout, sink, stage):
        self._receipt_sink, self._receipt_stage, self._published = sink, stage, False
        super().__init__(command, stderr, timeout)

    def close(self, graceful=False):
        super().close(graceful=graceful)
        if self.receipt is not None and not self._published:
            self._receipt_sink.append(dict(self.receipt, stage=self._receipt_stage))
            self._published = True


def operation_plan(protocol, profile):
    if profile == 'BASELINE':
        return ['queryStartup', 'initialize', 'queryShutdown']
    return (['producerStartup', 'initialize'] + [f'learn-{i}' for i in range(len(protocol['lessons']))]
            + (['generalize'] if profile == 'FAMILY' else [])
            + ['save', 'producerShutdown', 'queryStartup', 'restore', 'queryShutdown'])


def run_sequence(protocol, profile, length, repetition, command, output):
    start, cpu_start = time.perf_counter_ns(), time.process_time_ns()
    name = f'{profile}.n{length}.r{repetition}'
    sequence = {'id': name, 'profile': profile, 'length': length, 'repetition': repetition,
                'command': command, 'operations': [], 'rows': [], 'processes': [], 'errors': []}
    operations = {}; workers = []; generation = None; snapshot = None; query_worker = None

    def phase(name, fn):
        begin = time.perf_counter_ns()
        record = {'name': name}; operations[name] = record
        try:
            value = fn(record)
            record['status'] = 'COMPLETE'
            return value
        except Exception as error:
            record.update(status='ERROR', error=f'{type(error).__name__}: {error}')
            raise
        finally:
            record['wallNanos'] = time.perf_counter_ns() - begin

    def launch(stage, record):
        # Retain failed constructor instances as well as their receipts.
        owned = ReceiptSession.__new__(ReceiptSession); workers.append((stage, owned))
        ReceiptSession.__init__(owned, command, output / f'{sequence["id"]}.{stage}.stderr.txt',
                                protocol['setupTimeoutSeconds'], sequence['processes'], stage)
        record.update(pid=owned.process.pid, ready=owned.ready)
        return owned

    def request(owned, payload, record, expected_generation=None):
        record['request'] = payload
        response, elapsed = owned.request(payload, protocol['setupTimeoutSeconds'])
        record.update(response=response, transportWallNanos=elapsed)
        check_common(response, profile, SUCCESS[payload['command']], expected_generation)
        if payload['command'] in ('learn', 'generalize'):
            _require(type(response.get('promoted')) is bool and isinstance(response.get('reason'), str), 'learning receipt fields')
            _require(cost_total(response['work']) <= payload['budget'], 'acquisition operation budget exceeded')
        return response

    try:
        if profile == 'BASELINE':
            query_worker = phase('queryStartup', lambda r: launch('query', r))
            initial = phase('initialize', lambda r: request(query_worker, {'command': 'initialize', 'profile': profile}, r))
            generation = initial['generationId']
        else:
            producer = phase('producerStartup', lambda r: launch('producer', r))
            phase('initialize', lambda r: request(producer, {'command': 'initialize', 'profile': profile}, r))
            for i, lesson in enumerate(protocol['lessons']):
                phase(f'learn-{i}', lambda r, lesson=lesson: request(producer,
                      dict(lesson, command='learn', budget=protocol['acquisitionBudget']), r))
            if profile == 'FAMILY':
                phase('generalize', lambda r: request(producer, {'command': 'generalize',
                      'validation': protocol['familyValidation'], 'budget': protocol['acquisitionBudget']}, r))
            directory = (output / 'snapshots' / name).resolve()
            directory.mkdir(parents=True)
            saved = phase('save', lambda r: request(producer, {'command': 'save', 'directory': str(directory)}, r))
            snapshot = Path(saved['snapshot']).resolve()
            _require(snapshot.is_file() and snapshot.parent == directory, 'snapshot outside sequence directory')
            snapshot_hash = 'sha256:' + hashlib.sha256(snapshot.read_bytes()).hexdigest()
            _require(saved['snapshotHash'] == snapshot_hash, 'saved snapshot bytes/hash mismatch')
            sequence['snapshot'] = {'file': snapshot.relative_to(output.resolve()).as_posix(), 'sha256': snapshot_hash}
            generation = saved['generationId']
            phase('producerShutdown', lambda r: producer.close(graceful=True))
            _require(producer.receipt['exitCode'] == 0 and producer.receipt['termination'] == 'GRACEFUL_EOF', 'producer shutdown failed')
            query_worker = phase('queryStartup', lambda r: launch('query', r))
            restored = phase('restore', lambda r: request(query_worker, {'command': 'restore', 'profile': profile,
                             'snapshot': str(snapshot)}, r, generation))
            _require(restored['snapshotHash'] == snapshot_hash and restored['snapshot'] == str(snapshot), 'restore snapshot mismatch')
    except Exception as error:
        sequence['errors'].append(f'{type(error).__name__}: {error}')
        query_worker = None
    try:
        for index, query in enumerate(protocol['queries'][:length]):
            begin = time.perf_counter_ns()
            response, elapsed = ({'status': 'BLOCKED_WORKER_UNAVAILABLE'}, 0) if query_worker is None else query_worker.request(
                dict(query, command='run'), protocol['queryTimeoutSeconds'])
            audit_start = time.perf_counter_ns()
            audit = audit_response(query, response, profile, generation)
            sequence['rows'].append({'index': index, 'id': query['id'], 'task': query['task'],
                                     'response': response, 'generationId': generation,
                                     'independentAudit': audit, 'transportWallNanos': elapsed,
                                     'auditWallNanos': time.perf_counter_ns() - audit_start,
                                     'wallNanos': time.perf_counter_ns() - begin})
    finally:
        for stage, owned in workers:
            key = 'producerShutdown' if stage == 'producer' else 'queryShutdown'
            if key not in operations and hasattr(owned, 'process'):
                try:
                    phase(key, lambda r, owned=owned: owned.close(graceful=True))
                except Exception as error:
                    sequence['errors'].append(f'{type(error).__name__}: {error}')
        for key in operation_plan(protocol, profile):
            sequence['operations'].append(operations.get(key, {'name': key, 'status': 'BLOCKED', 'wallNanos': 0}))
        sequence['wallNanos'] = time.perf_counter_ns() - start
        sequence['controllerCpuNanos'] = time.process_time_ns() - cpu_start
    sequence['snapshotInventory'] = _file_inventory(output / 'snapshots' / name) if (output / 'snapshots' / name).exists() else {}
    sequence['accounting'] = account_sequence(protocol, sequence)
    return sequence


def account_sequence(protocol, sequence):
    """Recompute qualification; missing work never becomes a zero-cost success."""
    profile, length = sequence['profile'], sequence['length']
    operations, rows, processes = sequence['operations'], sequence['rows'], sequence['processes']
    expected = operation_plan(protocol, profile)
    names = [op['name'] for op in operations]
    operation_complete = names == expected and all(op['status'] == 'COMPLETE' for op in operations)
    errors = []
    generation = None; acquisition = None; restore_work = 0 if profile == 'BASELINE' else None
    common_binding = None; previous_work = 0; saved_generation = None; saved_hash = None
    for op in operations:
        natural(op['wallNanos'])
        _require(op['status'] in {'COMPLETE', 'ERROR', 'BLOCKED'}, 'unknown operation status')
        if op['status'] != 'COMPLETE' or op['name'] in {'producerStartup', 'queryStartup', 'producerShutdown', 'queryShutdown'}:
            continue
        try:
            _require({'request', 'response', 'transportWallNanos'} <= set(op),
                     'complete protocol operation is missing request/response/transport evidence: ' + op['name'])
            request, response = op['request'], op['response']
            _require(isinstance(request, dict) and isinstance(response, dict), 'protocol operation evidence must be objects')
            command = request['command']
            _require(command == op['name'].split('-')[0], 'operation/request command mismatch')
            current = check_common(response, profile, SUCCESS[command])
            _require(op['wallNanos'] >= natural(op['transportWallNanos']), 'operation omits transport time')
            if command != 'restore':
                _require(response['sessionSetupWork'] == 0, 'producer acquired unexplained restoration costs')
            if command == 'initialize':
                _require(request == {'command': command, 'profile': profile}, 'initialize request changed')
                _require(response['acquisitionWork'] == response['sessionSetupWork'] == 0, 'initialization has historic costs')
            elif command == 'learn':
                index = int(op['name'].split('-')[1])
                _require(request == dict(protocol['lessons'][index], command='learn', budget=protocol['acquisitionBudget']), 'lesson request changed')
            elif command == 'generalize':
                _require(profile == 'FAMILY' and request == {'command': command, 'validation': protocol['familyValidation'],
                          'budget': protocol['acquisitionBudget']}, 'generalization request changed')
            if command in {'learn', 'generalize'}:
                work = cost_total(response['work'])
                _require(work <= request['budget'] and response['acquisitionWork'] == previous_work + work,
                         'acquisition ledger does not include actual paid operation exactly once')
                _require(type(response['promoted']) is bool and isinstance(response['reason'], str), 'missing learning outcome')
                training = response.get('training')
                _require(isinstance(training, list), 'missing training receipts')
                for receipt in training:
                    _require(receipt['outcome'] in {'VERIFIED', 'NO_LAW_IN_ANSATZ', 'UNSUPPORTED', 'BUDGET_INCONCLUSIVE'}
                             and type(receipt['solved']) is bool and type(receipt['reused']) is bool
                             and receipt['solved'] == (receipt['outcome'] == 'VERIFIED')
                             and (not receipt['reused'] or receipt['solved']), 'invalid training receipt')
                    natural(receipt['totalWork'])
            if command == 'save':
                _require(current == generation, 'save changed generation identity')
                saved_generation, saved_hash = current, response['snapshotHash']
                _require(response['acquisitionWork'] == previous_work, 'save changed acquisition costs')
            if command == 'restore':
                _require(current == saved_generation and response['snapshotHash'] == saved_hash
                         and request == {'command': command, 'profile': profile, 'snapshot': response['snapshot']}, 'restore binding mismatch')
                _require(response['acquisitionWork'] == previous_work, 'restore double charged historic costs')
                restore_work = response['sessionSetupWork']
            previous_work = response['acquisitionWork']
            generation = current; acquisition = previous_work
            if command in {'initialize', 'restore'}:
                common_binding = {k: response[k] for k in ('acquisitionWork', 'acquisitionCosts', 'sessionSetupWork', 'recurrenceCount', 'recurrenceFamilyCount')}
        except (ValueError, KeyError, TypeError, IndexError) as error:
            errors.append(str(error))
    row_complete = len(rows) == length
    quality, query_work, audited = [], 0, 0
    reuse_accepted = True
    for index, row in enumerate(rows):
        try:
            query = protocol['queries'][index]
            _require(index < length and row['index'] == index and row['id'] == query['id'] and row['task'] == query['task'], 'query row binding mismatch')
            _require(row['generationId'] == generation, 'row generation binding mismatch')
            audit = audit_response(query, row['response'], profile, generation)
            _require(row['independentAudit'] == audit, 'independent audit mismatch')
            _require(natural(row['wallNanos']) >= natural(row['transportWallNanos']) + natural(row['auditWallNanos']), 'row omits transport or audit time')
            if audit['valid']:
                _require(common_binding is not None and all(row['response'].get(k) == v for k, v in common_binding.items()), 'query changed knowledge/setup ledger')
                audited += 1; query_work += row['response']['totalWork']
                if profile == 'EXACT_FROZEN' and row['response']['reused']:
                    reuse_accepted = False
                if profile == 'FAMILY' and audit['solved'] and not row['response']['reused']:
                    reuse_accepted = False
            quality.append(audit['solved'] if audit['valid'] else None)
        except (ValueError, KeyError, TypeError, IndexError) as error:
            errors.append(str(error)); quality.append(None)
    process_complete = len(processes) == (1 if profile == 'BASELINE' else 2)
    stages = ['query'] if profile == 'BASELINE' else ['producer', 'query']
    process_complete &= [p['stage'] for p in processes] == stages
    _require(len({p['pid'] for p in processes}) == len(processes), 'producer/consumer process reused')
    for process in processes:
        receipt = process.get('cpu')
        if receipt is None or process.get('peakRssBytes') is None or process.get('receiptError'):
            process_complete = False; continue
        _require(natural(receipt['totalNanos']) == natural(receipt['userNanos']) + natural(receipt['systemNanos'])
                 and receipt['scope'] == CPU_SCOPE, 'CPU receipt scope/partition mismatch')
        natural(process['peakRssBytes'])
        startup = next((op for op in operations if op['name'] == ('producerStartup' if process['stage'] == 'producer' else 'queryStartup')), {})
        if startup.get('status') == 'COMPLETE':
            _require(startup.get('pid') == process['pid'] and startup.get('ready', {}).get('status') == 'READY', 'startup/receipt binding mismatch')
    good_shutdown = all(p.get('exitCode') == 0 and p.get('termination') == 'GRACEFUL_EOF' for p in processes)
    elapsed = natural(sequence['wallNanos'])
    measured = sum(op['wallNanos'] for op in operations) + sum(r['wallNanos'] for r in rows)
    _require(elapsed >= measured, 'lifecycle omits paid operations or queries')
    complete = (operation_complete and row_complete and process_complete and good_shutdown and not sequence['errors']
                and not errors and audited == length and generation is not None)
    logical_complete = operation_complete and row_complete and not errors and audited == length and acquisition is not None and restore_work is not None
    cpu = ({k: sum(p['cpu'][k] for p in processes) for k in ('userNanos', 'systemNanos', 'totalNanos')}
           if process_complete else None)
    if cpu is not None:
        cpu['scope'] = CPU_SCOPE
    return {'complete': bool(complete), 'completeProcessReceipts': bool(process_complete),
            'lifecycleWallNanos': elapsed, 'controllerCpuNanos': natural(sequence['controllerCpuNanos']),
            'wholeWorkerCpu': cpu,
            'fullLifecycleCpuNanos': cpu['totalNanos'] + sequence['controllerCpuNanos'] if cpu is not None else None,
            'fullLifecycleCpuScope': 'SUM_OF_OWNED_WORKER_ALL_THREAD_CPU_AND_CONTROLLER_PROCESS_CPU_DURING_SEQUENCE',
            'registeredQualityAccepted': reuse_accepted and quality == [not q['id'].endswith('-negative') for q in protocol['queries'][:length]] and len(rows) == length,
            'registeredReuseAccepted': reuse_accepted and row_complete and audited == length,
            'peakWorkerRssBytes': max((p['peakRssBytes'] for p in processes if p.get('peakRssBytes') is not None), default=None),
            'rssScope': 'MAXIMUM_WORKER_WAIT4_PEAK_RSS;NOT_SUM_OR_INTRINSIC_ALGORITHM_MEMORY',
            'plannedQueries': length, 'retainedQueries': len(rows), 'auditedQueries': audited,
            'solvedVector': quality, 'solvedQueries': sum(x is True for x in quality),
            'acquisitionWork': acquisition, 'restoreWork': restore_work,
            'queryWork': query_work if row_complete and audited == length else None,
            'totalLogicalWork': acquisition + restore_work + query_work if logical_complete else None,
            'logicalWorkScope': 'HISTORIC_ACQUISITION_ONCE_PLUS_ACTUAL_RESTORE_PLUS_ALL_QUERY_WORK',
            'qualificationErrors': errors}


def paired_comparisons(protocol, sequences):
    indexed = {(s['profile'], s['length'], s['repetition']): s for s in sequences}
    result = []
    for profile in ('EXACT_FROZEN', 'FAMILY'):
        for length in protocol['sequenceLengths']:
            for repetition in range(protocol['repetitions']):
                learned = indexed.get((profile, length, repetition)); baseline = indexed.get(('BASELINE', length, repetition))
                a = account_sequence(protocol, learned) if learned else None
                b = account_sequence(protocol, baseline) if baseline else None
                equal = bool(a and b and a['complete'] and b['complete'] and a['solvedVector'] == b['solvedVector'])
                accepted = bool(equal and a['registeredQualityAccepted'] and b['registeredQualityAccepted'])
                def ratio(key):
                    return b[key] / a[key] if accepted and a[key] else None
                result.append({'profile': profile, 'comparator': 'BASELINE', 'length': length, 'repetition': repetition,
                               'equalAuditedQuality': equal, 'registeredQualityAccepted': accepted,
                               'fullLifecycleCpuSpeedup': ratio('fullLifecycleCpuNanos'),
                               'fullLifecycleWallSpeedup': ratio('lifecycleWallNanos'),
                               'wholeWorkerCpuSpeedup': b['wholeWorkerCpu']['totalNanos'] / a['wholeWorkerCpu']['totalNanos']
                               if accepted and a['wholeWorkerCpu'] and a['wholeWorkerCpu']['totalNanos'] else None,
                               'logicalWorkSpeedup': ratio('totalLogicalWork'),
                               'learned': a, 'baseline': b})
    return result


def summarize(protocol, sequences):
    result = {}
    for profile in protocol['profiles']:
        accounts = [account_sequence(protocol, s) for s in sequences if s['profile'] == profile]
        result[profile] = {'lifecycles': len(accounts), 'completeLifecycles': sum(a['complete'] for a in accounts),
                           'registeredQualityAcceptedLifecycles': sum(a['complete'] and a['registeredQualityAccepted'] for a in accounts),
                           'controllerCpuNanos': sum(a['controllerCpuNanos'] for a in accounts),
                           'fullLifecycleCpuNanos': sum(a['fullLifecycleCpuNanos'] for a in accounts) if all(a['fullLifecycleCpuNanos'] is not None for a in accounts) else None,
                           'plannedQueries': sum(a['plannedQueries'] for a in accounts),
                           'retainedQueries': sum(a['retainedQueries'] for a in accounts),
                           'auditedQueries': sum(a['auditedQueries'] for a in accounts),
                           'solvedQueries': sum(a['solvedQueries'] for a in accounts),
                           'lifecycleWallNanos': sum(a['lifecycleWallNanos'] for a in accounts),
                           'workerCpuNanos': sum(a['wholeWorkerCpu']['totalNanos'] for a in accounts)
                           if all(a['wholeWorkerCpu'] is not None for a in accounts) else None,
                           'peakWorkerRssBytes': max((a['peakWorkerRssBytes'] for a in accounts if a['peakWorkerRssBytes'] is not None), default=None)}
    return result


def write_manifest(output):
    manifest = {name: item['sha256'] for name, item in _file_inventory(output).items()
                if name not in {'manifest.json', 'command-receipt.json'}}
    (output / 'manifest.json').write_bytes(canonical(manifest))


def verify_manifest(output):
    manifest = _load((output / 'manifest.json').read_text())
    inventory = _file_inventory(output)
    _require(set(manifest) == set(inventory) - {'manifest.json', 'command-receipt.json'}, 'manifest inventory mismatch')
    for name, digest in manifest.items():
        _require(not Path(name).is_absolute() and '..' not in Path(name).parts
                 and inventory[name]['sha256'] == digest, 'manifest digest mismatch')


def child_command(protocol_path, classpath_path, output, executable):
    """Canonical original invocation; verification never resolves moved input paths."""
    return [executable, '-m', 'scripts.external_polynomial_comparison.run_recurrence_knowledge',
            '--execute-child', '--protocol', str(protocol_path), '--classpath', str(classpath_path),
            '--output', str(output)]


def execute(protocol_path: Path, classpath_path: Path, output: Path, *, invocation=None):
    """Measured child: runtime snapshot, actual sequences, reports and manifest."""
    protocol = load_protocol(protocol_path)
    repository = Path(__file__).resolve().parents[2]
    source = source_provenance(repository)
    registered = subprocess.check_output(['git', 'show', 'HEAD:' + PROTOCOL_PATH], cwd=repository)
    _require(hashlib.sha256(registered).hexdigest() == PROTOCOL_HASH, 'protocol is not committed before execution')
    protocol_path, classpath_path, output = protocol_path.resolve(), classpath_path.resolve(), output.resolve()
    invocation = list(sys.orig_argv if invocation is None else invocation)
    _require(invocation == child_command(protocol_path, classpath_path, output, sys.executable),
             'execute-child invocation does not match its actual input/output arguments')
    protocol_bytes, classpath_bytes = protocol_path.read_bytes(), classpath_path.read_bytes()
    _require(hashlib.sha256(protocol_bytes).hexdigest() == PROTOCOL_HASH, 'protocol bytes changed before retention')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'protocol.json').write_bytes(protocol_bytes)
    (output / 'classpath-input.txt').write_bytes(classpath_bytes)
    classpath = classpath_bytes.decode('utf-8').strip()
    for entry in classpath.split(os.pathsep):
        _require('test-classes' not in Path(entry).parts and 'test' not in Path(entry).parts,
                 'test runtime is prohibited')
    runtime = snapshot_runtime(classpath, output)
    java = _java_provenance()
    _require(re.search(r'version "25(?:\.|\")', java['version']) is not None, 'native Java 25 required')
    command = [java['executable'], *protocol['javaOptions'], '-cp',
               os.pathsep.join(str(output / entry) for entry in runtime['classpathEntries']), JAVA_WORKER]
    metadata = dict(source, protocolHash=PROTOCOL_HASH, revision=protocol['revision'],
                    outputDirectoryAtExecution=str(output), runtimeBytesHash=runtime['runtimeBytesHash'],
                    java=java, command=command, python=platform.python_version(),
                    executeChild={'command': invocation, 'pythonExecutable': sys.executable,
                                  'protocolPath': str(protocol_path), 'classpathPath': str(classpath_path),
                                  'classpathHash': 'sha256:' + hashlib.sha256(classpath_bytes).hexdigest()},
                    informationRegime='PUBLIC_DEVELOPMENT', warmups=[],
                    sequenceWallScope='FIRST_WORKER_START_THROUGH_LAST_SHUTDOWN_INCLUDING_IO_AND_EXACT_EXTERNAL_AUDIT',
                    workerCpuScope=CPU_SCOPE)
    (output / 'metadata.json').write_bytes(canonical(metadata))
    sequences = []
    for profile, length, repetition in schedule(protocol):
        sequence = run_sequence(protocol, profile, length, repetition, command, output)
        sequences.append(sequence)
        # A completed prefix remains inspectable if an unrelated controller failure occurs.
        (output / 'sequences.json').write_bytes(canonical(sequences))
    rows = [r for s in sequences for r in s['rows']]
    (output / 'rows.json').write_bytes(canonical(rows))
    summary = summarize(protocol, sequences); comparisons = paired_comparisons(protocol, sequences)
    (output / 'summary.json').write_bytes(canonical(summary))
    (output / 'paired-comparisons.json').write_bytes(canonical(comparisons))
    report = ['# Recurrence knowledge lifecycle evidence', '',
              'PUBLIC_DEVELOPMENT: known development family; no sealed holdout or general CAS claim.', '',
              '| Profile | Complete / planned lifecycles | Audited / retained queries | Solved | Total lifecycle wall (s) | Whole worker CPU (s) | Maximum worker RSS (bytes) |',
              '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for profile, a in summary.items():
        report.append(f'| {profile} | {a["completeLifecycles"]} / {a["lifecycles"]} | {a["auditedQueries"]} / {a["retainedQueries"]} | {a["solvedQueries"]} | {a["lifecycleWallNanos"]/1e9:.6f} | {a["workerCpuNanos"]/1e9 if a["workerCpuNanos"] is not None else None} | {a["peakWorkerRssBytes"]} |')
    report += ['', 'Every prefix and repetition starts fresh workers; learned profiles acquire, save, shut down,',
               'then restore/reprove in a distinct consumer. Baseline pays its own empty initialization.',
               'All 657 planned requests, including negative outcomes and blocked failures, remain in rows.',
               'Sequence wall includes controller IO and exact law audits through worker shutdown.',
               'Worker CPU includes every owned JVM thread from startup through shutdown; controller CPU is separate.',
               'RSS is the maximum worker process peak across sequential workers, not a sum or intrinsic heap requirement.',
               'Logical work counts historic acquisition once, actual restore, and query work; elapsed time is never reconstructed from it.',
               'Positive laws are independently proved; coherent negative receipts are retained without certifying the absence of any law.',
               'Ratios require complete operations/receipts/rows, equal complete audited solved vectors, and registered development acceptance (1, 7, 56 solved for lengths 1, 8, 64).',
               'Registered arm acceptance additionally requires FAMILY to reuse every solved request and EXACT_FROZEN to reuse none.',
               'Full lifecycle CPU adds the separately measured controller process CPU (including exact audit) to all worker CPU.', '',
               '| Learned profile | Length | Repetition | Qualified | Wall speedup | Worker CPU speedup | Full CPU speedup | Logical work speedup |',
               '| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |']
    for pair in comparisons:
        report.append('| ' + ' | '.join(str(pair[k]) for k in ('profile', 'length', 'repetition', 'registeredQualityAccepted', 'fullLifecycleWallSpeedup', 'wholeWorkerCpuSpeedup', 'fullLifecycleCpuSpeedup', 'logicalWorkSpeedup')) + ' |')
    report += ['', 'A speedup below one is a measured regression. No performance acceptance is predeclared.',
               'The outer supervisor measures this entire child through reports and manifest writing.',
               'command-receipt.json is written after that measured boundary; its own write is excluded.']
    (output / 'report.md').write_text('\n'.join(report) + '\n')
    write_manifest(output)
    return summary


def verify(output: Path):
    """Offline: reject inventory tampering, then recompute all semantic audits."""
    verify_manifest(output)
    protocol = load_protocol(output / 'protocol.json')
    metadata = _load((output / 'metadata.json').read_text())
    _require(metadata['protocolHash'] == PROTOCOL_HASH and metadata['informationRegime'] == 'PUBLIC_DEVELOPMENT'
             and metadata['revision'] == protocol['revision'] and metadata['warmups'] == [], 'metadata protocol binding mismatch')
    _require(re.fullmatch('[0-9a-f]{40,64}', metadata['sourceRevision']) is not None
             and re.fullmatch('git:[0-9a-f]{40,64}', metadata['sourceTreeHash']) is not None, 'source provenance missing')
    invocation = metadata.get('executeChild')
    _require(isinstance(invocation, dict) and set(invocation) ==
             {'command', 'pythonExecutable', 'protocolPath', 'classpathPath', 'classpathHash'},
             'execute-child invocation/input binding missing')
    _require(all(isinstance(value, str) and Path(value).is_absolute() and os.path.normpath(value) == value
                 for value in [invocation['pythonExecutable'], invocation['protocolPath'],
                               invocation['classpathPath'], metadata['outputDirectoryAtExecution']]),
             'execute-child original paths must be absolute and normalized')
    expected_invocation = child_command(invocation['protocolPath'], invocation['classpathPath'],
                                        metadata['outputDirectoryAtExecution'], invocation['pythonExecutable'])
    _require(invocation['command'] == expected_invocation, 'execute-child command/input binding mismatch')
    classpath_bytes = (output / 'classpath-input.txt').read_bytes()
    _require(invocation['classpathHash'] == 'sha256:' + hashlib.sha256(classpath_bytes).hexdigest(),
             'retained classpath input byte/hash mismatch')
    runtime = _load((output / 'runtime-inventory.json').read_text()); verify_runtime(output, runtime)
    _require(len(classpath_bytes.decode('utf-8').strip().split(os.pathsep)) == len(runtime['originalClasspathEntries']),
             'classpath input/runtime entry count mismatch')
    _require(runtime['runtimeBytesHash'] == metadata['runtimeBytesHash'], 'runtime provenance mismatch')
    expected_command = [metadata['java']['executable'], *protocol['javaOptions'], '-cp',
                        os.pathsep.join(str(Path(metadata['outputDirectoryAtExecution']) / p) for p in runtime['classpathEntries']), JAVA_WORKER]
    _require(metadata['command'] == expected_command, 'command does not launch retained production runtime')
    _require(metadata['workerCpuScope'] == CPU_SCOPE and metadata['sequenceWallScope'] ==
             'FIRST_WORKER_START_THROUGH_LAST_SHUTDOWN_INCLUDING_IO_AND_EXACT_EXTERNAL_AUDIT', 'measurement scope changed')
    _require(re.search(r'version "25(?:\.|\")', metadata['java']['version']) is not None
             and all(re.fullmatch('[0-9a-f]{64}', metadata['java'][key] or '') is not None
                     for key in ('executableSha256', 'releaseSha256')), 'native Java provenance missing')
    sequences = _load((output / 'sequences.json').read_text())
    actual = [(s['profile'], s['length'], s['repetition']) for s in sequences]
    _require(actual == list(schedule(protocol)) and len(set(actual)) == 27, 'incomplete/duplicate/reordered lifecycle schedule')
    _require(sum(len(s['rows']) for s in sequences) == 657, 'planned query inventory incomplete')
    expected_stderr, expected_snapshots = set(), set()
    for sequence in sequences:
        _require(sequence['id'] == f'{sequence["profile"]}.n{sequence["length"]}.r{sequence["repetition"]}'
                 and sequence['command'] == expected_command, 'sequence identity/command mismatch')
        _require([op['name'] for op in sequence['operations']] == operation_plan(protocol, sequence['profile']), 'missing planned operation')
        _require(len(sequence['rows']) == sequence['length'], 'missing planned query row')
        for op in sequence['operations']:
            if op['name'] in {'producerStartup', 'queryStartup'} and op['status'] != 'BLOCKED':
                stage = 'producer' if op['name'] == 'producerStartup' else 'query'
                expected_stderr.add(f'{sequence["id"]}.{stage}.stderr.txt')
        snapshot_inventory = sequence.get('snapshotInventory', {})
        _require(snapshot_inventory == _file_inventory(output / 'snapshots' / sequence['id']), 'sequence snapshot inventory mismatch')
        expected_snapshots.update('snapshots/' + sequence['id'] + '/' + p for p in snapshot_inventory)
        accounting = account_sequence(protocol, sequence)
        snapshot = sequence.get('snapshot')
        if sequence['profile'] in {'EXACT_FROZEN', 'FAMILY'} and accounting['complete']:
            _require(isinstance(snapshot, dict), 'complete learned lifecycle requires retained snapshot')
        if snapshot is not None:
            _require(isinstance(snapshot, dict) and set(snapshot) == {'file', 'sha256'}, 'invalid snapshot metadata')
            path = Path(snapshot['file'])
            _require(not path.is_absolute() and '..' not in path.parts
                     and path.parent == Path('snapshots') / sequence['id'], 'invalid snapshot inventory binding')
            expected_snapshots.add(path.as_posix())
            snapshot_file = output / path
            _require(snapshot_file.is_file(), 'retained snapshot file missing')
            digest = 'sha256:' + hashlib.sha256(snapshot_file.read_bytes()).hexdigest()
            _require(digest == snapshot['sha256'] and snapshot_inventory.get(path.name) ==
                     {'bytes': snapshot_file.stat().st_size, 'sha256': digest.removeprefix('sha256:')},
                     'snapshot hash/inventory mismatch')
            for op in sequence['operations']:
                if op['name'] in {'save', 'restore'} and op['status'] == 'COMPLETE':
                    _require(op['response']['snapshotHash'] == digest
                             and op['response']['snapshot'] == str(Path(metadata['outputDirectoryAtExecution']) / path), 'saved/restored snapshot trace mismatch')
        _require(sequence['accounting'] == accounting, 'recomputed lifecycle accounting mismatch')
    files = _file_inventory(output)
    _require({p for p in files if p.endswith('.stderr.txt')} == expected_stderr, 'stderr inventory mismatch')
    _require({p for p in files if p.startswith('snapshots/')} == expected_snapshots, 'snapshot inventory mismatch')
    _require((output / 'rows.json').read_bytes() == canonical([r for s in sequences for r in s['rows']]), 'raw query inventory mismatch')
    _require(_load((output / 'summary.json').read_text()) == summarize(protocol, sequences), 'recomputed summary mismatch')
    _require(_load((output / 'paired-comparisons.json').read_text()) == paired_comparisons(protocol, sequences), 'recomputed comparisons mismatch')
    fixed = {'protocol.json', 'classpath-input.txt', 'metadata.json', 'runtime-inventory.json', 'sequences.json', 'rows.json',
             'summary.json', 'paired-comparisons.json', 'report.md', 'manifest.json', 'command-receipt.json'}
    expected_files = fixed | expected_stderr | expected_snapshots | {'runtime/' + name for name in runtime['files']}
    _require(set(files) == expected_files, 'unexpected or missing evidence file inventory')
    receipt_file = output / 'command-receipt.json'
    _require(receipt_file.is_file(), 'supervisor command receipt missing')
    if receipt_file.exists():
        receipt = _load(receipt_file.read_text())
        _require(receipt['manifestHash'] == 'sha256:' + hashlib.sha256((output / 'manifest.json').read_bytes()).hexdigest(), 'supervisor manifest binding mismatch')
        _require(receipt['wallScope'] == 'ENTIRE_EXECUTE_CHILD_THROUGH_REPORTS_AND_MANIFEST;EXCLUDES_THIS_RECEIPT_WRITE', 'supervisor boundary mismatch')
        _require(receipt.get('command') == expected_invocation, 'supervisor command does not match actual execute-child invocation')
        _require(type(receipt['returncode']) is int, 'supervisor return code missing')
        _require(natural(receipt['wallNanos']) >= sum(natural(s['wallNanos']) for s in sequences),
                 'supervisor wall omits recorded lifecycle time')
        natural(receipt['supervisorCpuNanos'])
        cpu = receipt['childProcessTreeCpu']
        _require(natural(cpu['totalNanos']) == natural(cpu['userNanos']) + natural(cpu['systemNanos'])
                 and cpu['scope'] == 'EXECUTE_CHILD_AND_REAPED_WORKER_DESCENDANTS;INCLUDES_CONTROLLER', 'supervisor CPU scope/partition mismatch')
    return summarize(protocol, sequences)


def supervise(protocol_path, classpath_path, output):
    _require(not output.exists(), 'output directory must be new')
    command = child_command(protocol_path.resolve(), classpath_path.resolve(), output.resolve(), sys.executable)
    start, cpu_start = time.perf_counter_ns(), time.process_time_ns()
    process = subprocess.Popen(command)
    _require(hasattr(os, 'wait4'), 'whole child accounting requires wait4')
    _, status, usage = os.wait4(process.pid, 0)
    process.returncode = os.waitstatus_to_exitcode(status)
    wall, supervisor_cpu = time.perf_counter_ns() - start, time.process_time_ns() - cpu_start
    user, system = round(usage.ru_utime*1e9), round(usage.ru_stime*1e9)
    manifest = output / 'manifest.json'
    receipt = {'command': command, 'returncode': process.returncode, 'wallNanos': wall,
               'supervisorCpuNanos': supervisor_cpu,
               'childProcessTreeCpu': {'userNanos': user, 'systemNanos': system, 'totalNanos': user+system,
                                       'scope': 'EXECUTE_CHILD_AND_REAPED_WORKER_DESCENDANTS;INCLUDES_CONTROLLER'},
               'manifestHash': 'sha256:' + hashlib.sha256(manifest.read_bytes()).hexdigest() if manifest.exists() else None,
               'wallScope': 'ENTIRE_EXECUTE_CHILD_THROUGH_REPORTS_AND_MANIFEST;EXCLUDES_THIS_RECEIPT_WRITE'}
    if output.is_dir():
        (output / 'command-receipt.json').write_bytes(canonical(receipt))
    return process.returncode


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--protocol', type=Path, default=Path(PROTOCOL_PATH))
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--verify', type=Path, metavar='DIRECTORY')
    parser.add_argument('--execute-child', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.verify is not None:
        print(json.dumps(verify(args.verify), indent=2)); return
    if args.classpath is None or args.output is None:
        parser.error('--classpath and --output are required for execution')
    if args.execute_child:
        summary = execute(args.protocol, args.classpath, args.output, invocation=sys.orig_argv)
        print(json.dumps(summary, indent=2))
        if any(v['completeLifecycles'] != v['lifecycles'] for v in summary.values()):
            raise SystemExit('technical/incomplete lifecycles retained; performance ratios withheld')
    else:
        raise SystemExit(supervise(args.protocol, args.classpath, args.output))


if __name__ == '__main__':
    main()
