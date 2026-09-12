#!/usr/bin/env python3
"""Report capability working sets using the supplied extractor's actual model.

This is a diagnostic report, not an alternative quality gate. Run the normal
extraction/lifecycle first. The recomputed aggregate must match complexity.json.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


PROBE = r"""
package org.aiknowledge.core;
import java.nio.file.Path;
import java.util.*;
public final class ContextWorkingSetProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        RepositorySnapshot snapshot = new RepositorySnapshot();
        read(root, "modules", snapshot.modules);
        read(root, "classes", snapshot.classes);
        read(root, "tests", snapshot.tests);
        read(root, "docs", snapshot.docs);
        read(root, "capabilities", snapshot.capabilities);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("contextFootprint", ContextFootprintMetrics.calculate(snapshot));
        List<Object> capabilities = new ArrayList<>(snapshot.capabilities);
        List<Object> rows = new ArrayList<>();
        for (Object item : capabilities) {
            Map<?, ?> capability = (Map<?, ?>) item;
            snapshot.capabilities.clear();
            snapshot.capabilities.add(item);
            Map<String, Object> metric = ContextFootprintMetrics.calculate(snapshot);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", capability.get("id"));
            row.put("productionWorkingSetTokens", metric.get("medianCapabilityWorkingSetTokens"));
            row.put("measurementStatus", metric.get("measurementStatus"));
            rows.add(row);
        }
        report.put("capabilities", rows);
        System.out.println(JsonSupport.toJson(report));
    }
    private static void read(Path root, String name, List target) throws Exception {
        Map<?, ?> document = (Map<?, ?>) StrictJsonReader.read(root.resolve(name + ".json"));
        target.addAll((List<?>) document.get(name));
    }
}
"""


def report(root: Path, core_jar: Path) -> dict:
    with tempfile.TemporaryDirectory(prefix="ai-context-working-sets-") as temporary:
        work = Path(temporary)
        source = work / "ContextWorkingSetProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        subprocess.run([
            "javac", "--release", "17", "-cp", str(core_jar),
            "-d", str(work), str(source)], check=True)
        result = subprocess.run([
            "java", "-cp", os.pathsep.join((str(work), str(core_jar))),
            "org.aiknowledge.core.ContextWorkingSetProbe", str(root)],
            check=True, text=True, capture_output=True)
        measured = json.loads(result.stdout)
    complexity = json.loads((root / "complexity.json").read_text(encoding="utf-8"))
    if measured["contextFootprint"] != complexity["contextFootprint"]:
        raise ValueError("supplied extractor model does not reproduce the retained context footprint")
    index = json.loads((root / "context-packs/index.json").read_text(encoding="utf-8"))
    pack_index = {entry["id"]: entry for entry in index["contextPacks"]}
    for row in measured["capabilities"]:
        entry = pack_index[row["id"]]
        pack = json.loads((root / entry["file"]).read_text(encoding="utf-8"))
        row["contextTypeCount"] = len(pack["types"])
        row["contextTestCount"] = len(pack["tests"])
        row["serializedContextPackTokenEstimate"] = entry["tokenEstimate"]
    measured["extractorCoreJarSha256"] = hashlib.sha256(core_jar.read_bytes()).hexdigest()
    measured["artifactsSha256"] = {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*.json"))}
    return measured


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--extractor-core-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    measured = report(args.root.resolve(), args.extractor_core_jar.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(measured, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
