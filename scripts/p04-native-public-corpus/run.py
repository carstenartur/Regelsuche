#!/usr/bin/env python3
"""Public P03/native characterization, never a benchmark or P04 completion gate by itself."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
MODULES = ['regelsuche-core', 'regelsuche-egraph', 'regelsuche-search', 'regelsuche-validation',
           'regelsuche-math-algorithms', 'regelsuche-solver-ir', 'regelsuche-learning']
JARS = [('com.fasterxml.jackson.core', 'jackson-databind', '2.22.3'),
        ('com.fasterxml.jackson.core', 'jackson-core', '2.22.3'),
        ('com.fasterxml.jackson.core', 'jackson-annotations', '2.22'),
        ('com.fasterxml.jackson.dataformat', 'jackson-dataformat-yaml', '2.22.3'),
        ('com.fasterxml.jackson.datatype', 'jackson-datatype-jsr310', '2.22.3'),
        ('org.yaml', 'snakeyaml', '2.5')]
CODEC_OPERATIONS = {'EXPRESSION_ENCODE', 'EXPRESSION_DECODE', 'EXPRESSION_JSON_WRITE', 'EXPRESSION_JSON_READ',
                    'HISTORY_ENCODE', 'HISTORY_DECODE', 'HISTORY_HASH', 'EVIDENCE_JSON_WRITE'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + '\n')


def load(path):
    return json.loads(path.read_text())


def semantics(result, outcome=None):
    """Only declared revision-dependent work fields are excluded; retain complete proof metadata."""
    value = copy.deepcopy(result)
    for key in ('metrics', 'totalWork', 'stagedIncrementalExecution'):
        value.pop(key, None)
    if outcome is not None:
        value['outcome'] = outcome
        value['reached'] = outcome == 'TARGET_REACHED'
    for key in ('witness', 'events'):
        for entry in value.get(key, []):
            move = entry.get('move')
            if move:
                move.pop('generationCost', None)
                move.pop('verificationCost', None)
            check = entry.get('verification')
            if check:
                check.pop('work', None)
    for item in value.get('stateAssessments', []):
        item['assessment'].pop('searchWork', None)
        item['assessment'].pop('primitiveWork', None)
    return value


def differences(left, right, path=''):
    if isinstance(left, dict) and isinstance(right, dict):
        return [issue for key in sorted(set(left) | set(right))
                for issue in ([path + '/' + key + ':missing'] if key not in left or key not in right
                              else differences(left[key], right[key], path + '/' + key))]
    if isinstance(left, list) and isinstance(right, list):
        result = [path + ':length'] if len(left) != len(right) else []
        return result + [issue for index, pair in enumerate(zip(left, right))
                         for issue in differences(*pair, path + '/' + str(index))]
    return [] if left == right else [path + ':value']


def zero_codec(row, key='nativeInternalCodec'):
    counts = row.get(key)
    return isinstance(counts, dict) and set(counts) == CODEC_OPERATIONS and all(type(value) is int and value == 0 for value in counts.values())


def compare(directory, baseline):
    rows = []
    native_cases = {}
    for filename in ('cases.json', 'quality-cases.json'):
        for row in load(directory / filename):
            actual, legacy = row.get('nativeProjection'), row.get('currentLegacy')
            entry = {'id': row['id'], 'kind': filename, 'budgetMode': row['budgetMode'],
                     'errors': {k: v for k, v in row.items() if k.endswith('Error') or k == 'error'},
                     'zeroNativeCodec': zero_codec(row),
                     'accountingRemainsPartial': row.get('accountingComplete') is False and row.get('withinBudget') is False,
                     'searchUnchangedAfterExport': row.get('searchUnchangedAfterExport') is True}
            if actual is not None and legacy is not None:
                diff = differences(semantics(legacy), semantics(actual, row['nativeObservedOutcome']))
                entry.update(semanticParity=not diff and not entry['errors'], semanticProjectionEqual=not diff, semanticDifferences=diff,
                             observedNativeOutcome=row['nativeObservedOutcome'], legacyOutcome=legacy['outcome'])
            else:
                entry.update(semanticParity=False, semanticDifferences=['projection-unavailable'])
            if row['budgetMode'] == 'frozen-numeric' and legacy is not None:
                frozen = json.loads(row['frozen']['legacyResultJson' if filename == 'cases.json' else 'search'])
                entry['currentLegacyEqualsFrozenProjection'] = frozen == legacy
                entry['legacySemanticDifferencesFromFrozen'] = differences(semantics(frozen), semantics(legacy))
            if filename == 'quality-cases.json':
                entry['sameIncumbent'] = row.get('nativeIncumbent') == row.get('legacyIncumbent') and 'nativeIncumbent' in row
                entry['sameOutputScore'] = row.get('nativeOutputScore') == row.get('legacyOutputScore') and 'nativeOutputScore' in row
            rows.append(entry)
            native_cases[(row['id'], row['budgetMode'])] = row
    cursor = []
    frozen_eager = [move['transformation'] for move in load(baseline / 'cursor-eager-reference.json')['batch']['moves']]
    for row in load(directory / 'cursor-cases.json'):
        entry = {'id': row['id'], 'error': row.get('error'), 'zeroNativeCodec': zero_codec(row),
                 'replayZeroCodec': zero_codec(row, 'replayInternalCodec'), 'eagerZeroCodec': zero_codec(row, 'eagerInternalCodec'),
                 'stoppedWithinPullBound': row.get('stoppedWithinPullBound') is True,
                 'cursorWorkMonotonic': row.get('cursorWorkMonotonic') is True,
                 'declaredSnapshotsComplete': row.get('declaredSnapshotsComplete') is True,
                 'declaredPrepaidLedgerExact': row.get('declaredPrepaidLedgerExact') is True,
                 'prepaidCandidateNotDoubleCharged': row.get('prepaidCandidateNotDoubleCharged') is True,
                 'idempotentClose': row.get('idempotentClose') is True,
                 'postCloseUnchanged': row.get('postClosePullEmpty') is True and row.get('postCloseReceiptUnchanged') is True,
                 'sameOrderedTransformationsAsFrozen': row.get('transformations') == row['frozen']['transformations'],
                 'allReturnedCandidatesReplayed': 'replay' in row and all(x['accepted'] for x in row['replay'])}
        if row['id'].startswith('drain-'):
            entry['sameOrderedEagerTransformations'] = row.get('transformations') == row.get('eagerTransformations') and 'transformations' in row
        if row['id'].startswith('close-after-'):
            entry['zeroPullUnchanged'] = row.get('zeroPullEmpty') is True and row.get('zeroPullReceiptUnchanged') is True
            entry['phaseStopEmitsNothing'] = row.get('phaseStopEmitsNothing') is True
            entry['phaseStopMathematicsExact'] = row.get('phaseStopMathematicsExact') is True
        if row['id'] not in ('missing-assumptions', 'unsupported-variable-division'):
            entry['eagerEqualsFrozenOrderedReference'] = row.get('eagerTransformations') == frozen_eager
        cursor.append(entry)
    identities = [{'id': x['fixture']['id'], 'sameRelation': x.get('nativeSameReference') == x['fixture']['equal'],
                   'zeroNativeCodec': zero_codec(x), 'error': x.get('error')} for x in load(directory / 'identities.json')]
    negatives = [{'id': x['id'], 'rejected': x.get('accepted') is False, 'zeroNativeCodec': zero_codec(x),
                  'error': x.get('error')} for x in load(directory / 'negative-replay.json')]
    def projection(case):
        return native_cases.get((case, 'diagnostic-ample'), {}).get('nativeProjection', {})
    path = projection('continuation-path-sensitive-stateless')
    local = projection('continuation-declared-state-local')
    history = native_cases.get(('continuation-history-gated-path-sensitive', 'diagnostic-ample'), {})
    eager = native_cases.get(('quality-eager', 'diagnostic-ample'), {})
    lazy = native_cases.get(('quality-prepaid-incremental', 'diagnostic-ample'), {})
    continuation = {'sameStatelessClosure': bool(path) and bool(local) and
                    {s['expression'] for s in path['reachedStates']} == {s['expression'] for s in local['reachedStates']},
                    'localActuallyDominates': any(e['decision'] == 'DOMINATED' for e in local.get('events', [])),
                    'historySensitiveTargetReached': history.get('nativeObservedOutcome') == 'TARGET_REACHED'}
    quality = {'sameNativeIncumbent': 'nativeIncumbent' in eager and eager.get('nativeIncumbent') == lazy.get('nativeIncumbent'),
               'eagerGenerated': eager.get('nativeMetrics', {}).get('generatedSuccessors'),
               'lazyGenerated': lazy.get('nativeMetrics', {}).get('generatedSuccessors'),
               'claim': 'Diagnostic observed generation counts only; no economic or complete-budget qualification.'}
    summary = {}
    for mode in ('frozen-numeric', 'diagnostic-ample'):
        selected = [r for r in rows if r['budgetMode'] == mode]
        summary[mode] = {'attempted': len(selected), 'expected': 26,
                         'semanticParity': sum(r['semanticParity'] for r in selected),
                         'errorRows': [r['id'] for r in selected if r['errors']],
                         'semanticDifferences': [r['id'] for r in selected if not r['semanticParity']],
                         'nonzeroOrMissingCodec': [r['id'] for r in selected if not r['zeroNativeCodec']],
                         'partialFlagsNotPreserved': [r['id'] for r in selected if not r['accountingRemainsPartial']],
                         'unavailableOrChangedExport': [r['id'] for r in selected if not r['searchUnchangedAfterExport']]}
    return {'revision': 'public-native-semantic-projection/v1', 'searchAndQuality': rows, 'cursors': cursor,
            'identities': identities, 'negativeReplay': negatives,
            'continuationRelations': continuation, 'qualityGeneration': quality,
            'searchSummary': summary,
            'stateIdentities': [{'distinct': x['nativeStateEqual'] is False} for x in load(directory / 'state-identities.json')],
            'trainingModelEqualsFrozen': load(directory / 'training.json')['modelEqualsFrozen'],
            'corpusCoverage': load(directory / 'coverage.json'),
            'accountingComplete': False, 'withinBudget': False, 'economicComparison': False,
            'interpretation': 'Frozen-budget failures are retained. Ample diagnostic parity is not equivalent cost or P04 release acceptance.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--baseline', type=Path, required=True, help='Exact frozen PUBLIC baseline/results directory')
    parser.add_argument('--output', type=Path, required=True, help='New directory; prior evidence is never overwritten')
    parser.add_argument('--jdk', type=Path, default=Path('/tmp/regelsuche-runtime/jdk-25.0.2+10'))
    parser.add_argument('--cache', type=Path, default=Path('/workspace/scratch/4e87c3dfd845/gradle-home/caches/modules-2/files-2.1'))
    parser.add_argument('--diagnostic-work', type=int, default=1_000_000_000)
    parser.add_argument('--export-work', type=int, default=10_000_000)
    parser.add_argument('--compile-only', action='store_true')
    args = parser.parse_args()
    if args.diagnostic_work < 1 or args.export_work < 1:
        parser.error('diagnostic and export budgets must be positive finite integers')
    def git(*command):
        return subprocess.check_output(['git', '-C', str(args.repo), *command], text=True).strip()
    commit = git('rev-parse', 'HEAD')
    if commit != git('rev-parse', args.source_commit):
        raise SystemExit('Source HEAD differs from explicitly supplied commit')
    source_dirs = [m + '/src/main' for m in MODULES]
    if git('status', '--porcelain', '--untracked-files=all', '--', *source_dirs):
        raise SystemExit('Production source differs from pinned commit')
    binding = load(HERE / 'public-corpus-binding.json')
    generator = args.baseline.parent.parent / 'LegacyExprCorpus.java'
    def corpus_hashes():
        return {name: sha(args.baseline / name) for name in binding['resultsSha256']}
    if corpus_hashes() != binding['resultsSha256']:
        raise SystemExit('PUBLIC baseline bytes differ; no changed corpus accepted')
    if sha(generator) != binding['sourceGeneratorSha256']:
        raise SystemExit('PUBLIC legacy generator bytes differ; no changed generator accepted')
    args.output.mkdir(parents=True, exist_ok=False)
    # Synchronized evidence directories may receive transient/stale build files.
    # Keep executable outputs in a new local directory; retain its exact path.
    classes = Path(tempfile.mkdtemp(prefix='regelsuche-p04-corpus-classes-', dir='/tmp'))
    def production_hashes():
        return {str(p.relative_to(args.repo)): sha(p) for module in MODULES for kind in ('java', 'resources')
                for p in sorted((args.repo / module / 'src/main' / kind).rglob('*')) if p.is_file()}
    def driver_hashes():
        return {p.name: sha(p) for p in sorted(HERE.iterdir()) if p.is_file()}
    input_hashes = production_hashes()
    harness_hashes = driver_hashes()
    jars = []
    for group, artifact, version in JARS:
        matches = list((args.cache / group / artifact / version).glob('*/*.jar'))
        if len(matches) != 1: raise SystemExit(f'Need exactly one cached {group}:{artifact}:{version}; no network download attempted')
        jars.extend(matches)
    resources = [args.repo / module / 'src/main/resources' for module in MODULES if (args.repo / module / 'src/main/resources').is_dir()]
    service_sources = set()
    for resource in resources:
        for descriptor in (resource / 'META-INF/services').glob('*'):
            for line in descriptor.read_text().splitlines():
                name = line.split('#', 1)[0].strip()
                if name:
                    service_sources.update(p for module in MODULES
                                           if (p := args.repo / module / 'src/main/java' / (name.replace('.', '/') + '.java')).is_file())
    commands = []
    def run(command, logfile, timeout=300):
        commands.append(list(map(str, command))); write(args.output / 'commands.json', commands)
        with logfile.open('w') as log:
            try:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            except subprocess.TimeoutExpired:
                log.write(f'\nHarness process timeout after {timeout} seconds; all prior output retained.\n')
                return 124
        return completed.returncode
    def snapshot():
        if git('rev-parse', 'HEAD') != commit or input_hashes != production_hashes():
            raise SystemExit('Source changed during qualification')
        if harness_hashes != driver_hashes():
            raise SystemExit('Harness changed during qualification')
        if corpus_hashes() != binding['resultsSha256'] or sha(generator) != binding['sourceGeneratorSha256']:
            raise SystemExit('Frozen corpus changed during qualification')
    version = subprocess.run([args.jdk / 'bin/java', '-version'], capture_output=True, text=True, check=True)
    write(args.output / 'toolchain.json', {'version': version.stderr.strip(), 'java': sha(args.jdk / 'bin/java'),
          'javac': sha(args.jdk / 'bin/javac'), 'modules': sha(args.jdk / 'lib/modules'), 'release': sha(args.jdk / 'release'),
          'dependencies': {str(p): sha(p) for p in jars}, 'maxHeap': '768m', 'locale': 'en_US', 'encoding': 'UTF-8'})
    write(args.output / 'source-binding.json', {'commit': commit, 'tree': git('rev-parse', 'HEAD^{tree}'),
          'productionFiles': input_hashes, 'corpusFiles': binding['resultsSha256'],
          'driverFiles': harness_hashes, 'legacyGeneratorSha256': sha(generator),
          'isolatedClasses': str(classes),
          'diagnosticWork': args.diagnostic_work, 'exportWork': args.export_work,
          'note': 'Javac from pinned production sources and bound public driver; no stale repository build classes'})
    command = [args.jdk / 'bin/javac', '-J-XX:ActiveProcessorCount=2', '-encoding', 'UTF-8', '-proc:none',
               '-sourcepath', os.pathsep.join(str(args.repo / m / 'src/main/java') for m in MODULES),
               '-classpath', os.pathsep.join(map(str, jars)), '-d', classes, HERE / 'NativeExprCorpus.java', *sorted(service_sources)]
    code = run(command, args.output / 'compile.log')
    if code:
        write(args.output / 'status.json', {'compiled': False, 'compileExit': code, 'matrixExecuted': False, 'qualification': 'NOT_RUN'})
        raise SystemExit(code)
    snapshot()
    if args.compile_only:
        write(args.output / 'status.json', {'compiled': True, 'matrixExecuted': False, 'qualification': 'NOT_RUN'})
        return
    runtime = os.pathsep.join(map(str, [classes, *resources, *jars]))
    runs = []
    for index in (1, 2):
        destination = args.output / f'run-{index}'; destination.mkdir()
        code = run([args.jdk / 'bin/java', '-XX:ActiveProcessorCount=2', '-Xmx768m', '-Dfile.encoding=UTF-8',
                    '-Duser.language=en', '-Duser.country=US', '-cp', runtime, 'de.regelsuche.preparation.NativeExprCorpus',
                    args.baseline, destination, str(args.diagnostic_work), str(args.export_work)], args.output / f'run-{index}.log', 1200)
        if code:
            write(args.output / 'status.json', {'matrixExecuted': False, 'failedJvm': index, 'exit': code,
                  'qualification': 'INCOMPLETE', 'note': 'All partial rows and logs retained; no failed case removed.'})
            raise SystemExit(code)
        write(destination / 'comparison.json', compare(destination, args.baseline))
        runs.append({str(p.relative_to(destination)): sha(p) for p in sorted(destination.rglob('*.json'))})
        snapshot()
    write(args.output / 'verification.json', {'independentJvmRuns': 2, 'allOutputBytesIdentical': runs[0] == runs[1],
          'runs': runs, 'accountingComplete': False, 'withinBudget': False, 'qualification': 'DIAGNOSTIC_ONLY',
          'instruction': 'Inspect every comparison row; reproducibility alone is not semantic parity or resource qualification.'})
    if runs[0] != runs[1]: raise SystemExit('Fresh JVM output bytes differ; both attempts retained')


if __name__ == '__main__':
    main()
