# Knowledge Packs

Knowledge Packs sind deklarative, kuratierte Sammlungen mathematischer Regeln und ihrer Herkunftsdaten. Die eingebauten Packs liegen unter:

```text
regelsuche-core/src/main/resources/rules/packs/*.rules.yaml
```

## Inhalt

Ein Pack kann für jede Regel unter anderem festhalten:

- Pattern und Ersetzungsstruktur,
- Domäne und Tags,
- Provenance und Quelle,
- Lizenz,
- Review-Status,
- Risikostufe,
- positive und negative Validierungsbeispiele.

Erweiterte SymPy-basierte Packs existieren für Polynom-, Trigonometrie-, rationale und logarithmische Identitäten.

## Registrierungsgrenze

Nur Regeln mit dem Status `VALIDATED` oder `REVIEWED` dürfen in das aktive Regelinventar übernommen werden. Kandidaten bleiben deaktiviert, bis die geforderte Prüfung abgeschlossen ist. Pack-Registrierung bedeutet dennoch nicht:

- formaler mathematischer Beweis,
- externe mathematische Neuheit,
- automatische Promotion in Public Evidence,
- vertrauenswürdige Herkunft allein aufgrund vorhandener Metadaten.

Proof-, Novelty-, Promotion- und Public-Evidence-Status bleiben getrennte Achsen.

## Abgrenzung zu anderen Erweiterungen

| Mechanismus | Zweck | Lebenszyklus |
|---|---|---|
| `.regelsuche`/`.rules` | benutzernahe DSL für Regeln, Makros und Aktivierungsprofile | `PluginRuntime`, Import/Export, Validierung und Hot Reload |
| Knowledge Pack | kuratierte Domänenregeln mit Review-, Risiko- und Provenance-Metadaten | Pack-Loader und statusabhängige Registrierung |
| Java-Plugin | ausführbarer Code für Registries, AST-Hooks, Suche, Renderer oder Parser | JAR, `ServiceLoader`, Kompatibilitäts- und Trust-Prüfung |
| gelernte Makroregel | aus realen Suchpfaden generalisierter Kandidat | Holdouts, Gegenbeispiele, Novelty, Proof und Promotion |

Knowledge Packs sind damit keine Java-Plugins und umgehen auch nicht den evidenzkontrollierten Discovery-Lifecycle. Eine Pack-Datei beschreibt kuratiertes Wissen; ein gelernter Kandidat muss seine eigene Provenance und Validierung behalten, bevor er in ein zukünftiges Pack übernommen werden könnte.

## Weiterführende Dokumentation

- [Plugins und Erweiterungsflächen](plugins.md)
- [Regeldateien](rule-files.md)
- [Plugin-API](plugin-api.md)
- [Gelernte Makroregeln](macro-rules.md)
- [Mathematische Algorithmen und Capabilities](mathematical-algorithms.md)
