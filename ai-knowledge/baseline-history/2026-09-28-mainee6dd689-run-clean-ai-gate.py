"""Run the unchanged pinned AI gate on a fresh exact-commit checkout, retaining raw evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("commit")
parser.add_argument("output", type=Path)
args = parser.parse_args()
if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
    raise SystemExit("An exact commit SHA is required")
task = Path(__file__).resolve().parent
repository = task.parents[2]
workspace = repository.parent
extractor = workspace / "ai-knowledge-extractor"
output = args.output.resolve()
source = output.parent / (output.name + "-source")
java = Path("/tmp/regelsuche-runtime/jdk-25.0.2+10")
adapter = task / "run-gradle25.cjs"
expected_extractor = "b409bed957c31d63ce7b6ef37205890f0f0ebd9a"
expected_extractor_tree = "0cb8e41b91c831705f9c86633580181372da6e14"

def git(root, *arguments):
    return subprocess.check_output(["git", *arguments], cwd=root, text=True).strip()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def status(root):
    return git(root, "status", "--porcelain", "--untracked-files=all")

if git(extractor, "rev-parse", "HEAD") != expected_extractor or git(extractor, "rev-parse", "HEAD^{tree}") != expected_extractor_tree or status(extractor):
    raise SystemExit("Extractor must be clean and exactly pinned")
if source.exists() or output.exists():
    raise SystemExit("Use new evidence/source paths; earlier evidence must remain unchanged")
output.mkdir(parents=True)
subprocess.run(["git", "worktree", "add", "--quiet", "--detach", str(source), args.commit], cwd=repository, check=True)
init = source / "ai-knowledge/baseline-history/2026-09-20-main81ea9ac7-java25-extractor.init.gradle"
command = ["node", str(adapter), "--no-daemon", "--no-configuration-cache", "--max-workers=2", "-I", str(init),
    "-PuseLocalAiKnowledgeExtractor=true", "-PaiKnowledgeExtractorCheckout=" + str(extractor), "aiKnowledgeCheck"]
receipt = {
    "sourceRoot": str(source), "sourceCommit": git(source, "rev-parse", "HEAD"),
    "sourceTree": git(source, "rev-parse", "HEAD^{tree}"), "sourceStatusBefore": status(source),
    "extractorCommit": expected_extractor, "extractorTree": expected_extractor_tree,
    "extractorTag": git(extractor, "describe", "--tags", "--exact-match"),
    "extractorStatusBefore": status(extractor), "command": command,
    "baselineSha256Before": digest(source / "ai-knowledge/complexity-baseline.json"),
    "gateBuildSha256": digest(source / "ai-knowledge-verification/build.gradle"),
    "initScriptSha256": digest(init), "adapterSha256": digest(adapter),
    "runnerSha256": digest(Path(__file__)), "javaReleaseSha256": digest(java / "release"),
    "javaExecutableSha256": digest(java / "bin/java"),
    "javaVersion": subprocess.run([str(java / "bin/java"), "-version"], text=True, capture_output=True, check=True).stderr.strip(),
    "compilerNote": "Existing initializer compiles unchanged pinned extractor with JDK25 and original release17 target; no gate, selector or threshold change.",
    "scopeNote": "Fresh detached exact-commit checkout, no orchestration notes or other source changes. Output evidence is outside measured checkout.",
}
if receipt["sourceStatusBefore"] or receipt["sourceCommit"] != args.commit:
    raise SystemExit("Measured source is not the clean requested commit")
receipt_path = output / "receipt.json"
receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
start = time.monotonic()
environment = dict(os.environ, AI_KNOWLEDGE_EXTRACTOR_ENABLED="true")
with (output / "aiKnowledgeCheck.log").open("w") as log:
    receipt["exitCode"] = subprocess.call(command, cwd=source, env=environment, stdout=log, stderr=subprocess.STDOUT)
receipt["wallSeconds"] = time.monotonic() - start
receipt["sourceStatusAfter"] = status(source)
receipt["extractorStatusAfter"] = status(extractor)
receipt["baselineSha256After"] = digest(source / "ai-knowledge/complexity-baseline.json")
receipt["artifacts"] = {}
for name in ["metrics-snapshot.json", "check.json", "trend.json", "complexity.json"]:
    artifact = source / "build/ai-knowledge" / name
    if artifact.is_file():
        shutil.copy2(artifact, output / name)
        receipt["artifacts"][name] = digest(output / name)
receipt["logSha256"] = digest(output / "aiKnowledgeCheck.log")
receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps({"exitCode": receipt["exitCode"], "receipt": str(receipt_path), "artifacts": list(receipt["artifacts"])}))
if receipt["sourceStatusAfter"] or receipt["extractorStatusAfter"] or receipt["baselineSha256After"] != receipt["baselineSha256Before"]:
    raise SystemExit("Source or baseline changed during measurement")
raise SystemExit(receipt["exitCode"])
