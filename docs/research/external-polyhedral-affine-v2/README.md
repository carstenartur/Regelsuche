# Original-Alpha-Vergleich für affine Reduktionen

Der Lauf verwendet Regelsuche `9b862e3169a120f17265475777c13b132975bdd8`, Dateibaum `d78d5aafd5ba6fa5239a08a0298877ade274d2b2`, mit sauberem Arbeitsbaum nach der Korrektur der Eingabebudgetierung. Alpha stammt unverändert aus `d91815edbfb0463c1b195173dbff46a4a2e52f39`. Das eingefrorene Protokoll bindet beide Compiler-JARs und alle sieben ursprünglichen Eingaben. Die drei Projektkontrollen und ihre Ergebnisse stehen in derselben Matrix. [Vergleichsbericht](../external-polyhedral-affine-v2.md) · [Protokoll](../../../config/benchmarks/external-polyhedral-v1.json)

Das Archiv enthält die vollständige Originalmatrix mit `manifest.json`, Alpha-Quellen und erzeugtem C, instrumentierten Kandidaten, Ausgaben, Prozessprotokollen und Java-Referenzwerten. Die Dateien behalten ihre im Manifest gebundenen Bytes. Sortierte Archivpfade und feste Tar- und Gzip-Metadaten machen die Verpackung derselben Messdaten reproduzierbar. Die Zeitmessungen gehören zu diesem konkreten Lauf.

| Datei | SHA-256 |
| --- | --- |
| [evidence.tar.gz](evidence.tar.gz) | `319e9cd6c9451b152a4116e4a5117a68676f8f2fd0f62b5876e327c51b428c6c` |
| [Ergebnismatrix (gzip)](results.json.gz) | `5023087006c3e3a2a4df61c8564ab3839a921fb5654868319c2aeff47075f756` |
| Entpackte Matrix `results.json` | `a01657a9ddc522ac08419b6d3d0b77c58825c9faa816b09c0f95a43c6b686369` |

## Archiv ohne Originalcompiler prüfen

Die Prüfung benötigt Java 25, Maven und bereits lokal vorhandene Maven-Abhängigkeiten. Sie liest die erfassten Ausgaben, prüft die Datei-Hashes und berechnet die Referenzwerte erneut aus den eingefrorenen Quellen. Die negativen Tests prüfen geänderte Alpha-Ausgaben, geänderte Java-Antworten und fehlende Manifesteinträge.

```sh
archive=docs/research/external-polyhedral-affine-v2/evidence.tar.gz
replay=$(mktemp -d)
tar -xzf "$archive" -C "$replay"
mvn -o -pl regelsuche-experiments -am test \
  -Dtest=ExternalPolyhedralComparisonTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexternalPolyhedralAudit="$replay/run"
```

Der Originalcompiler-Lauf bestand vier Tests. Die anschließende Prüfung aus einem neu entpackten Archiv bestand elf Tests einschließlich der negativen Kontrollen. Der zusätzliche Originalcompiler-Integrationstest blieb in diesem Offline-Lauf übersprungen.
