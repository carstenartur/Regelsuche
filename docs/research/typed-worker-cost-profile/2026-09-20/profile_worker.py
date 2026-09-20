"""One bounded diagnostic session per profile; not a speedup benchmark."""
from __future__ import annotations
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parent
JAVA = Path('/workspace/scratch/fcfe4cbdbc35/runtime/jdk-25.0.2+10/bin/java')
sys.path.insert(0, str(ROOT / 'source/scripts'))
from external_polynomial_comparison.run import Session, judge
from external_polynomial_comparison.run_typed import check_setup, check_response

PROFILES = ('BASE', 'LEARNED_NAIVE')
CASE_IDS = ('two-sites', 'simple-control', 'near-miss', 'two-near-misses',
            'offset', 'outer-product', 'sum-composition', 'compound-base',
            'scaled-site', 'nested-factor', 'double-factor', 'negative-site')
TARGET_QUERY_SECONDS = 30
MIN_CYCLES = 10
MAX_CYCLES = 120

def write_json(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n')

def proc_receipt(pid):
    result = {'epochNanos': time.time_ns()}
    try:
        lines = Path(f'/proc/{pid}/status').read_text().splitlines()
        for line in lines:
            if line.startswith(('VmRSS:', 'VmHWM:', 'VmSize:', 'Threads:')):
                key, value = line.split(':', 1)
                result[key] = int(value.split()[0]) * (1024 if key != 'Threads' else 1)
        stat = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        ticks = os.sysconf('SC_CLK_TCK')
        result.update(userCpuNanos=round(int(stat[11]) * 1e9 / ticks),
                      systemCpuNanos=round(int(stat[12]) * 1e9 / ticks))
    except (OSError, ValueError):
        result['unavailable'] = True
    return result

def gracefully_reap(worker):
    worker.process.stdin.close()
    started = time.monotonic()
    while True:
        pid, status, usage = os.wait4(worker.process.pid, os.WNOHANG)
        if pid:
            worker.process.returncode = os.waitstatus_to_exitcode(status)
            result = {'exitCode': worker.process.returncode,
                      'userCpuNanos': round(usage.ru_utime * 1e9),
                      'systemCpuNanos': round(usage.ru_stime * 1e9),
                      'maxResidentBytes': usage.ru_maxrss * 1024,
                      'minorFaults': usage.ru_minflt, 'majorFaults': usage.ru_majflt,
                      'voluntaryContextSwitches': usage.ru_nvcsw,
                      'involuntaryContextSwitches': usage.ru_nivcsw}
            worker.close()
            return result
        if time.monotonic() - started > 45:
            worker.close()
            raise RuntimeError('worker did not exit gracefully within 45 seconds')
        time.sleep(0.01)

def run_profile(profile, protocol, cases):
    output = ROOT / profile
    output.mkdir(exist_ok=False)
    command = [str(JAVA), '-Xmx512m', '-Xlog:jfr=warning', '-Xlog:jfr+startup=off:stdout',
               '-XX:FlightRecorderOptions=stackdepth=128',
               f'-XX:StartFlightRecording=settings={ROOT / "diagnostic.jfc"},filename={output / "worker.jfr"},dumponexit=true',
               '-cp', (ROOT / 'classpath.txt').read_text().strip(),
               'de.regelsuche.evolution.TypedExternalPolynomialComparisonWorker']
    start_epoch, start_wall, start_cpu = time.time_ns(), time.perf_counter_ns(), time.process_time_ns()
    meta = {'profile': profile, 'cacheMode': 'BASELINE_UNCACHED', 'command': command,
            'startEpochNanos': start_epoch, 'phases': [], 'rows': 0,
            'diagnosticOnly': True, 'hostContentionPossible': True}
    stop = threading.Event()
    worker = None
    memory = []
    request_times, native_cpu, native_wall, judge_cpu, judge_wall = [], [], [], [], []
    valid = improved = overruns = 0
    cases_summary = {}
    try:
        worker = Session(command, output / 'stderr.txt', 120)
        meta.update(ready=worker.ready, startupNanos=worker.startup_nanos,
                    pid=worker.process.pid)
        meta['phases'].append({'phase': 'startup', 'startEpochNanos': start_epoch,
                               'endEpochNanos': time.time_ns()})
        def sample_memory():
            while not stop.wait(.02):
                memory.append(proc_receipt(worker.process.pid))
        sampler = threading.Thread(target=sample_memory, daemon=True)
        sampler.start()
        memory.append(proc_receipt(worker.process.pid))
        before = time.time_ns()
        training, elapsed = worker.request({'op': 'initialize', 'profile': profile}, 120)
        after = time.time_ns()
        meta['phases'].append({'phase': 'initialize', 'startEpochNanos': before,
                               'endEpochNanos': after})
        meta.update(training=training, trainingRequestNanos=elapsed)
        model_hash = check_setup(protocol, profile, training)
        (output / 'model.json').write_text(training['model'])
        query_start_epoch, query_start = time.time_ns(), time.perf_counter_ns()
        with gzip.open(output / 'rows.jsonl.gz', 'wt', encoding='utf-8', compresslevel=1) as raw:
            for cycle in range(MAX_CYCLES):
                for case in cases:
                    request = {'op': 'run', 'profile': profile, 'source': case['source']}
                    sent = time.time_ns()
                    response, elapsed = worker.request(request, 30)
                    received = time.time_ns()
                    check_response(profile, response, model_hash, protocol['workBudget'])
                    judge_start_cpu = time.process_time_ns()
                    judgment = judge(case['source'], response, elapsed, 30 * 10**9)
                    verification_cpu = time.process_time_ns() - judge_start_cpu
                    row = {'profile': profile, 'cycle': cycle, 'case': case['id'],
                           'source': case['source'], 'sentEpochNanos': sent,
                           'receivedEpochNanos': received, 'requestWallNanos': elapsed,
                           'verificationCpuNanos': verification_cpu, 'response': response,
                           'judgment': judgment}
                    raw.write(json.dumps(row, separators=(',', ':'), allow_nan=False) + '\n')
                    request_times.append(elapsed)
                    native_cpu.append(response.get('nativeCpuNanos') or 0)
                    native_wall.append(response.get('nativeWallNanos') or 0)
                    judge_cpu.append(verification_cpu)
                    judge_wall.append(judgment['verificationWallNanos'])
                    valid += judgment['valid']; improved += judgment['improved']
                    overruns += response.get('internalWorkWithinBudget') is False
                    meta['rows'] += 1
                    case_summary = cases_summary.setdefault(case['id'], {'rows': 0, 'valid': 0,
                        'verdicts': {}, 'outputCosts': {}, 'totalWork': [], 'requestWallNanos': []})
                    case_summary['rows'] += 1; case_summary['valid'] += judgment['valid']
                    verdict = judgment['verdict']
                    case_summary['verdicts'][verdict] = case_summary['verdicts'].get(verdict, 0) + 1
                    cost = str(judgment.get('outputCost'))
                    case_summary['outputCosts'][cost] = case_summary['outputCosts'].get(cost, 0) + 1
                    case_summary['totalWork'].append(response.get('totalWork'))
                    case_summary['requestWallNanos'].append(elapsed)
                meta['cycles'] = cycle + 1
                query_elapsed = (time.perf_counter_ns() - query_start) / 1e9
                if (cycle + 1) % 10 == 0:
                    print(json.dumps({'profile': profile, 'cycles': cycle + 1,
                                      'rows': meta['rows'], 'querySeconds': query_elapsed}), flush=True)
                if cycle + 1 >= MIN_CYCLES and query_elapsed >= TARGET_QUERY_SECONDS:
                    break
        meta['phases'].append({'phase': 'queries', 'startEpochNanos': query_start_epoch,
                               'endEpochNanos': time.time_ns()})
        meta['queriesElapsedNanos'] = time.perf_counter_ns() - query_start
        memory.append(proc_receipt(worker.process.pid))
        before_exit = time.time_ns()
        meta['processReceipt'] = gracefully_reap(worker)
        meta['phases'].append({'phase': 'shutdown', 'startEpochNanos': before_exit,
                               'endEpochNanos': time.time_ns()})
        meta.update(validRows=valid, improvedRows=improved, internalWorkOverrunRows=overruns,
                    totalRequestWallNanos=sum(request_times), medianRequestWallNanos=statistics.median(request_times),
                    reportedNativeQueryCpuNanos=sum(native_cpu), reportedNativeQueryWallNanos=sum(native_wall),
                    independentVerificationCpuNanos=sum(judge_cpu), independentVerificationWallNanos=sum(judge_wall),
                    controllerCpuNanos=time.process_time_ns()-start_cpu, lifecycleWallNanos=time.perf_counter_ns()-start_wall)
        for summary in cases_summary.values():
            summary['distinctTotalWork'] = sorted(set(summary.pop('totalWork')))
            summary['medianRequestWallNanos'] = statistics.median(summary.pop('requestWallNanos'))
        meta['cases'] = cases_summary
    except BaseException as error:
        meta['error'] = repr(error)
        raise
    finally:
        stop.set()
        if worker is not None and worker.process.returncode is None:
            try: meta['processReceipt'] = gracefully_reap(worker)
            except Exception as error: meta['shutdownError'] = repr(error)
        write_json(output / 'metadata.json', meta)
        write_json(output / 'process-samples.json', memory)
    print(json.dumps({'profile': profile, 'rows': meta['rows'], 'valid': valid,
                      'cpuSeconds': (meta['processReceipt']['userCpuNanos'] + meta['processReceipt']['systemCpuNanos'])/1e9,
                      'maxResidentMiB': meta['processReceipt']['maxResidentBytes']/1024**2}), flush=True)
    return meta

def main():
    protocol = json.loads((ROOT / 'source/config/benchmarks/typed-external-polynomial-v1.json').read_text())
    by_id = {case['id']: case for case in protocol['cases']}
    cases = [by_id[key] for key in CASE_IDS]
    plan = {'profiles': PROFILES, 'cases': cases, 'cacheMode': 'BASELINE_UNCACHED',
            'minimumCycles': MIN_CYCLES, 'maximumCycles': MAX_CYCLES,
            'queryTargetSeconds': TARGET_QUERY_SECONDS, 'stopRule': 'Finish balanced cycle after 30s and >=10 cycles, or 120 cycles',
            'classification': 'Bounded diagnostic sampling; no speedup or CI timing qualification',
            'java': subprocess.check_output([str(JAVA), '-version'], stderr=subprocess.STDOUT, text=True),
            'platform': platform.platform(), 'controllerPython': sys.version}
    write_json(ROOT / 'sampling-plan.json', plan)
    summaries = [run_profile(profile, protocol, cases) for profile in PROFILES]
    write_json(ROOT / 'all-metadata.json', summaries)

if __name__ == '__main__': main()
