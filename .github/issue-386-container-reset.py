#!/usr/bin/env python3
from pathlib import Path

path = Path('regelsuche-release/src/main/java/de/regelsuche/release/AutonomousDiscoveryWalkthroughRunner.java')
text = path.read_text(encoding='utf-8')
old = '''        try (var paths = Files.walk(outputDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
'''
new = '''        try (var paths = Files.walk(outputDirectory)) {
            paths.filter(path -> !path.equals(outputDirectory))
                .sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
'''
if text.count(old) != 1:
    raise SystemExit(f'expected exactly one reset block, found {text.count(old)}')
path.write_text(text.replace(old, new), encoding='utf-8')
