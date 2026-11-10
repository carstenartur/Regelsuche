#!/usr/bin/env bash
# Supplemental source/resource check only; does not replace Maven qualification.
set -euo pipefail
if [[ $# != 3 ]]; then
  echo 'usage: run-mixed-v2-supplemental.sh JAVA_HOME BUNDLED_SDK_JAR NEW_OUTPUT_DIRECTORY' >&2
  exit 2
fi
TASK_JAVA_HOME=$(realpath "$1")
TASK_SDK_JAR=$(realpath "$2")
TASK_OUTPUT=$(realpath -m "$3")
TASK_REPOSITORY=$(git rev-parse --show-toplevel)
cd "$TASK_REPOSITORY"
mkdir "$TASK_OUTPUT"
mkdir "$TASK_OUTPUT/classes"
# Packaging/provenance only: mathematical operations remain in current Java sources.
python3 - "$TASK_SDK_JAR" "$TASK_OUTPUT" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys
sdk, output = Path(sys.argv[1]), Path(sys.argv[2])
with ZipFile(sdk) as source, ZipFile(output / 'external-only.jar', 'w', ZIP_DEFLATED) as target:
    for entry in source.infolist():
        if not entry.filename.startswith(('de/regelsuche/', 'META-INF/services/de.regelsuche.')):
            target.writestr(entry, source.read(entry.filename))
roots = sorted(Path('.').glob('*/src/main/java'))
(output / 'sourcepath.txt').write_text(':'.join(map(str, roots)))
entries = [Path('regelsuche-learning/src/main/java/de/regelsuche/evolution') / (name + '.java') for name in
    ('MixedTaskKnowledgeStudy', 'TaskKnowledgeLifecycleWorker')]
for module in ('regelsuche-search', 'regelsuche-learning'):
    for descriptor in sorted((Path(module) / 'src/main/resources/META-INF/services').glob('*')):
        for line in descriptor.read_text().splitlines():
            name = line.partition('#')[0].strip()
            if name:
                matches = [root / (name.replace('.', '/') + '.java') for root in roots]
                source = next((path for path in matches if path.exists()), None)
                if source is None:
                    raise RuntimeError('missing current provider source: ' + name)
                entries.append(source)
entries.extend(Path('regelsuche-learning/src/test/java/de/regelsuche/evolution') / (name + '.java') for name in
    ('TaskKnowledgeLifecycleCostContract', 'MixedTaskKnowledgeWorkloadContract'))
(output / 'sources.args').write_text('\n'.join(map(str, dict.fromkeys(entries))) + '\n')
PY
"$TASK_JAVA_HOME/bin/javac" -Xprefer:source -cp "$TASK_OUTPUT/external-only.jar" \
  -sourcepath "$(cat "$TASK_OUTPUT/sourcepath.txt")" -d "$TASK_OUTPUT/classes" \
  "@$TASK_OUTPUT/sources.args" > "$TASK_OUTPUT/compile.txt" 2>&1
TASK_CLASSPATH="$TASK_OUTPUT/classes:regelsuche-search/src/main/resources:regelsuche-learning/src/main/resources:$TASK_OUTPUT/external-only.jar"
"$TASK_JAVA_HOME/bin/java" -Xmx512m -cp "$TASK_CLASSPATH" de.regelsuche.evolution.TaskKnowledgeLifecycleCostContract > "$TASK_OUTPUT/cost-contract.txt" 2>&1
"$TASK_JAVA_HOME/bin/java" -Xmx512m -cp "$TASK_CLASSPATH" de.regelsuche.evolution.MixedTaskKnowledgeWorkloadContract > "$TASK_OUTPUT/workload-contract.txt" 2>&1
python3 - "$TASK_JAVA_HOME" "$TASK_SDK_JAR" "$TASK_OUTPUT" "$TASK_CLASSPATH" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import hashlib, json, resource, subprocess, sys, time
java_home, sdk, output, classpath = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
command = [str(java_home / 'bin/java'), '-Xmx512m', '-cp', classpath,
           'de.regelsuche.evolution.MixedTaskKnowledgeStudy', str(output / 'snapshots')]
usage_before = resource.getrusage(resource.RUSAGE_CHILDREN)
start = time.perf_counter_ns()
with (output / 'stdout.txt').open('wb') as stdout, (output / 'stderr.txt').open('wb') as stderr:
    completed = subprocess.run(command, stdout=stdout, stderr=stderr)
wall = time.perf_counter_ns() - start
usage = resource.getrusage(resource.RUSAGE_CHILDREN)
process = dict(exitCode=completed.returncode, wallNanos=wall,
    userCpuNanos=round((usage.ru_utime-usage_before.ru_utime)*1e9),
    systemCpuNanos=round((usage.ru_stime-usage_before.ru_stime)*1e9), peakRssKiB=usage.ru_maxrss,
    scope='one Java child, including startup/shutdown and report serialization')
(output / 'process.json').write_text(json.dumps(process, indent=2) + '\n')
completed.check_returncode()
lines = [line for line in (output / 'stdout.txt').read_text().splitlines() if line.startswith('{')]
if len(lines) != 1: raise RuntimeError('expected one complete report JSON line')
json.loads(lines[0])
(output / 'report.json').write_text(lines[0] + '\n')
roots = sorted(Path('.').glob('*/src/main/java'))
resource_files = sorted(path for module in ('regelsuche-search', 'regelsuche-learning')
    for path in (Path(module) / 'src/main/resources/META-INF/services').glob('*'))
source_files = sorted(path for root in roots for path in root.rglob('*.java'))
class_files = sorted((output / 'classes').rglob('*.class'))
with ZipFile(output / 'external-only.jar') as external:
    assert not any(name.startswith(('de/regelsuche/', 'META-INF/services/de.regelsuche.')) for name in external.namelist())
provenance = dict(qualification='SUPPLEMENTAL_ALL_CURRENT_PROJECT_SOURCES_AND_SERVICE_PROVIDERS_NOT_MAVEN',
    commit=subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip(),
    javaVersion=subprocess.run([str(java_home/'bin/java'), '-version'], capture_output=True, text=True).stderr,
    command=command, bundledSdkSha256=sha(sdk), externalOnlyJarSha256=sha(output/'external-only.jar'),
    excludedBundledPrefixes=['de/regelsuche/', 'META-INF/services/de.regelsuche.'],
    serviceResources={str(path): dict(sha256=sha(path), contents=path.read_text()) for path in resource_files},
    compiledClassCount=len(class_files),
    compiledClasses={str(path.relative_to(output/'classes')):sha(path) for path in class_files},
    javaSourceTreeSha256=hashlib.sha256('\n'.join(str(path)+' '+sha(path) for path in source_files).encode()).hexdigest(),
    reportSha256=sha(output/'report.json'), rawStdoutSha256=sha(output/'stdout.txt'),
    normalization='one complete JSON line extracted unchanged; raw stdout/stderr retained')
(output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print('Supplemental contracts and default study completed:', output)
PY
