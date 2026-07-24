# Export-Formate

`DefaultTransformationExportService` und `ExportFileService` schreiben:

| Format | Datei | Inhalt |
| --- | --- | --- |
| Markdown (`md`) | `discovered-transformations.md` | Inhaltsverzeichnis + Schritt-für-Schritt-Pfade + Score-Tabelle. |
| LaTeX (`tex`) | `discovered-transformations.tex` | Nummerierte `align*`-Umgebung mit Regel-ID rechts (\\text{...}). |
| Mermaid (`mmd`) | `transformation-graph.mmd` | Statusgefärbter Graph (Klassen `observed`, `validated`, `symbolic`, `formal`, `rejected`). |
| JSON | `discovered-transformations.json` | Vollständiges `ExportBundle` (Schema-Version 1.0). |
| Inventar | `rule-inventory.json` | Nur Inventar-Inhalt. |

Erklärservice `ExplanationService` produziert Schritte/Pfade in den Formen `SHORT`, `SCHOOL` (deutsche Klassenraum-Erklärung), `EXPERT`, `LATEX` und `JSON`.

Round-Trip: Export-Bundles können per `DefaultTransformationImportService.importJson` wieder eingelesen und über `RuleInventoryRepository.importBundle` importiert werden.
