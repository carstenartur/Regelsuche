"""Retain an unchanged pinned AI gate execution and its provenance."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)
extractor = Path('/workspace/scratch/7ff736a22947/ai-knowledge-extractor')
init_script = Path('/workspace/scratch/7ff736a22947/regelsuche-m0-evidence/ai-knowledge/baseline-history/2026-09-20-main81ea9ac7-java25-extractor.init.gradle')
java_home = Path('/tmp/taxonomy-toolchain/jdk-25.0.2+10')

def git(directory, *args):
    return subprocess.check_output(['git', *args], cwd=directory, text=True).strip()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

command = [str(root / 'gradlew'), '--offline', '--no-daemon',
    '--no-configuration-cache', '--max-workers=2', '-I', str(init_script),
    '-PuseLocalAiKnowledgeExtractor=true',
    '-PaiKnowledgeExtractorCheckout=' + str(extractor),
    '-Porg.gradle.java.installations.paths=' + str(java_home),
    'aiKnowledgeCheck']
receipt = {
    'sourceRoot': str(root), 'sourceCommit': git(root, 'rev-parse', 'HEAD'),
    'sourceTree': git(root, 'rev-parse', 'HEAD^{tree}'),
    'sourceStatusBefore': git(root, 'status', '--porcelain', '--untracked-files=all'),
    'extractorCommit': git(extractor, 'rev-parse', 'HEAD'),
    'extractorTree': git(extractor, 'rev-parse', 'HEAD^{tree}'),
    'extractorTag': git(extractor, 'describe', '--tags', '--exact-match'),
    'extractorStatusBefore': git(extractor, 'status', '--porcelain'),
    'command': command,
    'baselineSha256Before': digest(root / 'ai-knowledge/complexity-baseline.json'),
    'gateBuildSha256': digest(root / 'ai-knowledge-verification/build.gradle'),
    'initScriptSha256': digest(init_script),
    'runnerSha256': digest(Path(__file__)),
    'javaHome': str(java_home),
    'javaReleaseSha256': digest(java_home / 'release'),
    'javaExecutableSha256': digest(java_home / 'bin/java'),
    'javaVersion': subprocess.run([str(java_home / 'bin/java'), '-version'],
        text=True, capture_output=True).stderr.strip(),
    'compilerNote': 'Unchanged extractor sources compiled with JDK 25; original --release 17 retained.',
}
receipt_path = output / 'receipt.json'
receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')
environment = dict(os.environ, JAVA_HOME=str(java_home), AI_KNOWLEDGE_EXTRACTOR_ENABLED='true')
with (output / 'aiKnowledgeCheck.log').open('w') as log:
    receipt['exitCode'] = subprocess.call(command, cwd=root, env=environment,
        stdout=log, stderr=subprocess.STDOUT)
receipt['sourceStatusAfter'] = git(root, 'status', '--porcelain', '--untracked-files=all')
receipt['baselineSha256After'] = digest(root / 'ai-knowledge/complexity-baseline.json')
receipt['artifacts'] = {}
for name in ['metrics-snapshot.json', 'check.json', 'trend.json', 'complexity.json']:
    source = root / 'build/ai-knowledge' / name
    if source.is_file():
        shutil.copy2(source, output / name)
        receipt['artifacts'][name] = digest(output / name)
receipt['logSha256'] = digest(output / 'aiKnowledgeCheck.log')
receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'exitCode': receipt['exitCode'], 'receipt': str(receipt_path),
    'artifacts': list(receipt['artifacts'])}))
raise SystemExit(receipt['exitCode'])
