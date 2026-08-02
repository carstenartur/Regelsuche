# Dokumentationskonventionen

Diese Konventionen gelten für README, Handbücher, Architekturtexte,
Forschungsseiten, Betriebsdokumentation und generierte Berichte. Ziel ist eine
Dokumentation, die für neue Nutzer verständlich, für Entwickler verbindlich und
für wissenschaftliche Claims überprüfbar bleibt.

## Dokumenttypen

Jede Seite besitzt eine primäre Rolle. Mehrere Rollen dürfen verlinkt, aber
nicht auf derselben Seite vermischt werden.

### Projekt- und Einstiegseiten

**Beispiele:** Top-Level-README, `docs/README.md`.

Sie beantworten:

- Was ist das Projekt?
- Für wen ist es relevant?
- Wie starte ich den ersten unterstützten Ablauf?
- Wo finde ich Status, Architektur und Referenz?

Sie enthalten keine vollständigen Endpoint-, Schema- oder Issue-Kataloge.

### Nutzerhandbücher

**Beispiele:** Getting Started, Web-Workbench, User Workflows.

Sie beginnen mit der sichtbaren Oberfläche und beschreiben:

1. Einstiegspunkt;
2. Voraussetzungen;
3. Eingaben in den Begriffen der UI;
4. Aktion;
5. sichtbares Ergebnis;
6. leere, ladende, erfolgreiche und fehlerhafte Zustände;
7. nächsten sinnvollen Schritt.

Interne Klassen, Services und Statuskonstanten werden nur genannt, wenn die
Seite ausdrücklich eine Entwicklerreferenz ist.

### Architektur- und Entwicklerseiten

Sie erklären Verantwortung, Grenzen, Abhängigkeitsrichtungen, Trust-Modelle,
Ausführungsflüsse und Änderungskriterien. Sie sollen nicht zu einer Liste aller
Klassen oder Implementierungsdetails werden.

### Forschungs- und Statusseiten

Sie trennen:

- Konfiguration und Informationsgrenze;
- ausgeführte und nicht ausgeführte Arbeit;
- gemessene Ergebnisse;
- autorisierten Claim;
- Nicht-Claims und Coverage Gaps;
- Reproduktionsweg;
- Datumsstand.

Eine Forschungsseite darf ein negatives Ergebnis nicht sprachlich in einen
Erfolg umdeuten. `NOT_EVALUATED`, `BLOCKED`, `UNSUPPORTED` und Nullresultate
bleiben sichtbar.

### Betriebs- und Integrationsseiten

Sie beschreiben unterstützte Betriebsmodi, Voraussetzungen, Sicherheit,
Persistenz, Backup, Diagnose und Upgrade-Grenzen. Demo-Defaults werden nicht als
Produktionskonfiguration dargestellt.

### Referenzseiten

Referenzseiten katalogisieren stabile Verträge, Statusvokabular oder Optionen.
Sie erklären Bedeutung und Verweis, duplizieren aber keine maschinenlesbare
Quelle.

### Generierte Dokumente

Dateien unter `docs/generated/` und markierte Abschnitte werden aus Evidence
oder Source Contracts erzeugt. Sie werden nicht manuell korrigiert. Fehler
werden am Generator, an der Quelle oder am Verifier behoben.

## Quellen der Wahrheit

| Information | Verbindliche Quelle |
| --- | --- |
| sichtbare Bedienabläufe | Nutzerhandbuch und Browser-E2E |
| HTTP-Vertrag | OpenAPI 3.1 und lokale Swagger UI |
| aktueller Forschungsstand | `discovery-status.md` |
| Capability-/Claim-Status | generierte Capability-Matrix und Evidence |
| Modul- und Dependency-Grenzen | Gradle, `module-structure.md`, `dependency-rules.md` |
| Architekturentscheidungen | ADRs unter `docs/adr/` |
| JSON-Strukturen | versionierte Schemas unter `docs/schemas/` |
| Test- und CI-Semantik | Gradle, JUnit und `scripts/` im Checkout |

Eine Seite verweist auf die verbindliche Quelle, statt eine zweite, später
abweichende Kopie zu pflegen.

## GUI zuerst, Swagger für REST

Swagger/OpenAPI ist die einzige verbindliche REST-Referenz. Methoden, Pfade,
Parameter, Payloads, Responses, Statuscodes und technische Beispiele werden
nicht parallel in README und Nutzerhandbüchern gepflegt.

Markdown beschreibt den fachlichen Bedienvorgang. Eine knappe Zuordnung zu
Swagger-Tags oder `operationId` ist zulässig, wenn sie Entwicklung und Test
unterstützt.

Existiert für eine öffentlich nutzbare REST-Funktion keine grafische
Bedienmöglichkeit, wird dies als Produktlücke dokumentiert. Zusätzliche
Endpoint-Prosa ersetzt keine fehlende UI.

## Standardaufbau einer Seite

Nicht jede Seite benötigt jede Überschrift, aber die Reihenfolge soll erkennbar
bleiben:

1. **Titel und Zweck** — ein Satz, der Rolle und Inhalt festlegt.
2. **Zielgruppe oder Einsatzfall** — nur wenn nicht offensichtlich.
3. **Status oder Voraussetzungen** — bei zeitabhängigen oder ausführbaren
   Inhalten.
4. **Hauptinhalt** — aufgaben- oder systemorientiert strukturiert.
5. **Grenzen und Nicht-Claims** — bei Forschung, Sicherheit und Betrieb.
6. **Reproduktion oder Prüfung** — ausführbarer Befehl und erwartete Artefakte.
7. **Siehe auch** — wenige, gezielte nächste Seiten.

## Sprache und Stil

- Eine Seite verwendet überwiegend eine Sprache. Technische Bezeichner,
  Schema-IDs und Code bleiben unverändert.
- Kurze, vollständige Sätze sind langen Nominalketten vorzuziehen.
- Überschriften beschreiben Inhalte, nicht den Schreibprozess.
- Aussagen werden konkret formuliert: wer oder was tut was, unter welchen
  Voraussetzungen und mit welchem Ergebnis.
- Marketingbegriffe wie „revolutionär“, „überlegen“ oder „vollständig“ werden
  nur verwendet, wenn ein klar benannter Vertrag sie trägt.
- `READY`, `QUALIFIED`, `IMPLEMENTED`, `BLOCKED` und `NOT_EVALUATED` werden
  nicht als Synonyme verwendet.
- Projekt-Neuheit und externe mathematische Neuheit werden sprachlich immer
  getrennt.
- „Beweis“ wird nur für die tatsächlich erreichte Proof-Stufe verwendet.

## Mathematische Notation

- Fließtext und UI-nahe Darstellung verwenden lesbare Unicode- oder
  Markdown/LaTeX-Notation.
- Eingabesyntax steht in Codeblöcken.
- Innerhalb eines Feldes wird nicht zwischen `*` und `·` oder zwischen `x^2`
  und `x²` gewechselt.
- Annahmen und Definitionsbereiche stehen sichtbar bei der Aussage, die sie
  begrenzen.

## Claims und Messwerte

Jede quantitative oder wissenschaftliche Aussage nennt oder verlinkt:

- den Track beziehungsweise das Evidence Profile;
- den Informationszugriff;
- Corpus und Version;
- Ergebnis und Einheit;
- relevante Grenzen;
- Reproduktionsartefakt oder Verifier.

Ein einzelner Benchmark autorisiert keine allgemeine Systemrangfolge. Wandzeit
wird mit Umgebung und Messpolitik dokumentiert; kanonische Work-Metriken bleiben
davon getrennt.

## Links und Navigation

- Relative Links werden für Repositoryseiten verwendet.
- Eine Seite verlinkt bevorzugt auf den nächsten fachlichen Schritt, nicht auf
  jede entfernt verwandte Datei.
- Der zentrale Dokumentationsindex katalogisiert Seiten nach Zielgruppe.
- Schema-Einzellinks gehören in den [Schema-Katalog](schema-catalog.md), nicht
  in den Top-Level-README.
- Historische Seiten werden als historisch gekennzeichnet und nicht in aktuelle
  Nutzerpfade eingebaut.

## Bilder und Diagramme

- Bilder besitzen aussagekräftigen Alt-Text und eine erklärende Bildunterschrift
  oder umgebenden Text.
- Diagramme haben eine fachliche Aussage und ersetzen keine fehlende
  Beschreibung.
- UI-Screenshots werden aus Browser-E2E erzeugt und nicht händisch retuschiert.
- Ein Screenshot gilt nicht als Testbeleg, wenn der dargestellte Zustand nicht
  durch denselben Flow geprüft wird.

## Generierte Abschnitte

Generierte Bereiche verwenden stabile Marker, beispielsweise:

```markdown
<!-- capability-status:start -->
… generierter Inhalt …
<!-- capability-status:end -->
```

Manuelle Texte außerhalb der Marker dürfen die generierte Aussage nicht
widersprechen. Generatoren und Verifier müssen fehlende oder doppelte Marker
blockieren.

## Pflege bei Änderungen

| Änderung | Zu aktualisieren |
| --- | --- |
| sichtbarer UI-Flow | Nutzerhandbuch, Browser-E2E, gegebenenfalls Screenshots |
| REST-Vertrag | OpenAPI, Codec/Handler, Integrationstests |
| Modul- oder Trust-Grenze | Architektur, Dependency-Regeln, ADR falls grundlegend |
| neuer Evidence-Vertrag | Schema, Runtime-Codec, Verifier, Schema-Katalog, fachliche Seite |
| neuer Benchmark oder Claim | Forschungsseite, Reproduktion, generierte Statusquelle |
| Betriebsdefault | Getting Started, Betriebsseite, Compose-/Container-Tests |
| neues Glossarwort | Glossar und gegebenenfalls UI-Tooltip |

## Review-Regel

Eine Dokumentationsänderung ist erst vollständig, wenn:

1. die Seite eine eindeutige Rolle hat;
2. sie keine zweite Quelle der Wahrheit erzeugt;
3. Claims und Nicht-Claims korrekt getrennt sind;
4. alle Links und mathematischen Markierungen geprüft sind;
5. generierte Inhalte nicht manuell verfälscht wurden;
6. der passende checkout-eigene Verifikationstask grün ist.

Die operative Prüfliste steht in
[Documentation Quality Checklist](documentation-quality-checklist.md).
