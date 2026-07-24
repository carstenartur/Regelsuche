# Capability- und Claim-Status

Diese Seite beschreibt den Veröffentlichungsvertrag für öffentliche Capability- und
Claim-Aussagen. Die eigentliche Statusmatrix wird nicht manuell gepflegt, sondern aus
kanonischer Release- und Domain-Evidence sowie hashgebundenen
Implementierungsverträgen erzeugt.

## Autoritative Eingaben

Der Generator `scripts/generate-capability-status.py` konsumiert:

- `regelsuche.release-readiness-matrix/v1`;
- `regelsuche.release-readiness-run/v1`;
- `regelsuche.domain-generic-discovery-qualification/v1`;
- `regelsuche.domain-generic-discovery-qualification-run/v1`;
- für reine `IMPLEMENTED`-Aussagen die explizit aufgeführten Source-, Schema- und
  Workflow-Verträge des Plugin-Trust-Stacks.

Der Generator prüft die Run-/Report-Wurzelbindungen, den vollständigen erwarteten
Release-Profile-Satz und die Claim-Grenzen des domänengenerischen Profils. Fehlt ein
Pflichtvertrag oder widersprechen sich zwei Wurzeln, wird keine Statusausgabe erzeugt.

## Kontrolliertes Statusvokabular

| Status | Bedeutung |
|---|---|
| `IMPLEMENTED` | Die benannten Software-, Schema- und CI-Verträge sind vorhanden und hashgebunden. Das ist keine weitergehende Service- oder Release-Qualification. |
| `QUALIFIED` | Das benannte Evidence Profile ist für genau seinen gespeicherten Claim `READY`. |
| `EXPERIMENTAL` | Eine Implementierung existiert, aber die zugehörige Qualification ist nicht vollständig. |
| `BLOCKED` | Pflicht-Evidence fehlt oder ein Gate hat blockiert. Die Blocker bleiben sichtbar. |
| `NOT_EVALUATED` | Das Gate wurde für den betrachteten Kandidaten oder Lauf nicht ausgeführt. |
| `OUT_OF_SCOPE` | Die Capability gehört absichtlich nicht zum betrachteten Artefakt. |

Ein erfolgreicher niedrigerer Status darf nie in einen stärkeren Claim umformuliert
werden. Insbesondere bleiben folgende Achsen getrennt:

- Derivation Trace;
- Validation Certificate;
- symbolische Verifikation;
- formaler Beweis;
- Projekt-Neuheit;
- externe mathematische Neuheit;
- Interessantheit;
- Promotion;
- Public Evidence.

## Erzeugte Dateien

- [`generated/capability-status.json`](generated/capability-status.json) —
  kanonische maschinenlesbare Matrix;
- [`generated/capability-status.md`](generated/capability-status.md) —
  vollständige lesbare Matrix;
- [`schemas/regelsuche-capability-status-v1.schema.json`](schemas/regelsuche-capability-status-v1.schema.json)
  — struktureller Vertrag.

README und `docs/discovery-status.md` enthalten nur einen vom selben Generator
verwalteten Kurzblock. Manuelle Änderungen innerhalb der Marker werden von der
repositoryeigenen Verifikation abgewiesen.

## Lokale Reproduktion und Verifikation

Der vollständige Vertrag läuft aus einem normalen Checkout mit einem Befehl:

```bash
bash scripts/run-capability-status-verification.sh
```

Der Runner:

1. erzeugt die autoritative qualifizierte Release- und domänengenerische Evidence neu;
2. lässt `generate-capability-status.py` JSON, Markdown und die verwalteten
   Dokumentationsblöcke gegen den Checkout prüfen;
3. verwendet ein Build-lokales Python-Venv mit `jsonschema==4.25.1`;
4. validiert das Statusdokument gegen sein Draft-2020-12-Schema;
5. berechnet den kanonischen `contentHash` unabhängig neu;
6. verlangt den exakten, eindeutig sortierten Satz von 13 Capabilities und deren
   zulässige Status-, Evidence- und Blockerbeziehungen.

Die Logs liegen unter `build/logs/capability-status-*.log`. GitHub Actions ruft nur
diesen Befehl auf und veröffentlicht die bereits lokal erzeugten Reports.

Zum bewussten Aktualisieren der generierten Dateien werden die Evidence-Artefakte
zunächst wie im Runner erzeugt. Anschließend wird `generate-capability-status.py` ohne
`--check` und `--check-docs`, aber mit `--rewrite-docs` aufgerufen. Die resultierenden
Änderungen an JSON, Markdown, README und Discovery-Status müssen gemeinsam mit den
Evidence-Änderungen reviewt werden.

## Sicherheits- und Trust-Grenze

Die Matrix belegt lokale kryptografische Plugin-Verifikation als `IMPLEMENTED`, aber
keinen öffentlichen Plugin-Marktplatz. Gehosteter Transport, Download, Installation,
Update, Entfernung und Rollback bleiben so lange `BLOCKED`, bis ein eigenes
End-to-End-Artefakt diese Schritte bindet.

Ebenso autorisiert `AUTONOMOUS_CAMPAIGN=QUALIFIED` keine externe mathematische
Neuheit. `EXTERNAL_NOVELTY_REVIEW`, formaler Beweis, Promotion und Public Evidence
behalten ihre unabhängigen Gates.
