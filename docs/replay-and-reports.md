# Replay and Reports

Die Discovery-UX besteht aus zwei Schichten:

1. **interaktives Browser-Replay** in der Web-Workbench
2. **automatische Report-Artefakte** für CI, Dokumentation und Reproduktion

## Replay

Das Replay zeigt pro Pfad:

- Vorher/Nachher-Schritte mit KaTeX/Layout
- Regelbegründung und Diff-Hervorhebung
- Domänenhinweise (z. B. Vergleichszeichen-Flip, Matrixkarte)
- Makrozüge inklusive atomarer Expansion und unterstützender Pfad-IDs

## Reports

Automatisch erzeugte Artefakte:

- `discovery-report.json`
- `discovery-report.html`
- `discovery-report.md`
- `discovery-replay.json`
- `discovery-summary.png`
- `discovery-replay.gif`

Browser-E2E und Doku-Assets:

```bash
./gradlew :app:e2eTest --tests de.regelsuche.e2e.BrowserDemoFlowTest
./gradlew :app:e2eTest -Pregelsuche.recordDocs=true
```

Siehe auch:

- [docs/replay-mode.md](replay-mode.md)
- [docs/testing.md](testing.md)
- [docs/demo-gallery.md](demo-gallery.md)
