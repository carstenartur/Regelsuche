# Developer Guide

Dieser Leitfaden beschreibt den unterstützten Entwicklungsablauf für
Regelsuche. Ziel ist nicht nur funktionierender Code, sondern ein aus einem
normalen Checkout reproduzierbarer fachlicher und wissenschaftlicher Vertrag.

## Voraussetzungen

Für die meisten Änderungen:

- JDK 25;
- Git;
- der eingecheckte Gradle Wrapper.

Für Browser-, Container- und vollständige Evidence-Tests zusätzlich:

- Docker;
- Python 3 mit `venv`;
- auf Linux die Playwright-Hostbibliotheken;
- für strikte Solver-Verträge die dokumentierte Z3-Version.

Die genaue Zuordnung von Tasks und Voraussetzungen steht in
[Testing](testing.md).

## Repository-Struktur

```text
regelsuche-core/                  AST, Parser, Regeln, kanonische Identität
regelsuche-egraph/                Equality Saturation
regelsuche-search/                Strategien, Budgets, Scoring, Memory
regelsuche-validation/            Äquivalenz- und Validierungsverträge
regelsuche-solver-ir/             solver-neutrale Obligationen und Ergebnisse
regelsuche-solver-portfolio/      Backend-Auswahl und Solver-Ausführung
regelsuche-learning/              Mining und Rewrite-Program-Lernen
regelsuche-discovery/             Discovery- und Handoff-Verträge
regelsuche-experiments/           Experiment- und Corpus-Primitiven
regelsuche-benchmarks/            vergleichende Benchmarkausführung
regelsuche-persistence*/          Ports und Hibernate-Adapter
regelsuche-autopilot/             Campaign-Orchestrierung
regelsuche-release/               Qualification und Reproduktion
regelsuche-cli/                   CLI-Primitiven
app/                              Web, konkrete CLI und Runtime-Wiring
gradle/                           checkout-eigene Verifikationslogik
scripts/                          unabhängige Verifier und Renderer
docs/                             Handbücher, Architektur und Forschung
research/                         eingefrorene Experimentquellen
paper/                            evidenzgebundene Manuskriptartefakte
.github/workflows/                dünne Plattformadapter
```

`settings.gradle` ist das autoritative Modulverzeichnis. Fachliche
Abhängigkeitsrichtungen stehen in [Dependency-Regeln](dependency-rules.md).

## Empfohlener Arbeitsablauf

1. **Aufgabe und Claim-Grenze klären.** Handelt es sich um Produktfunktion,
   mathematische Semantik, Infrastruktur, Benchmark oder Evidence-Vertrag?
2. **Kleinsten passenden Modulbereich wählen.** Fachliche Logik nicht
   vorschnell in `app` oder einen Infrastrukturadapter legen.
3. **Positive und negative Semantik zuerst charakterisieren.** Insbesondere
   Annahmen, Fehlerzustände, Budgets, Nullresultate und unsupported cases.
4. **Fokussierten Task ausführen.** Kurze lokale Schleife statt sofortigem
   `ciCheck`.
5. **Dokumentation und externe Verträge aktualisieren.** Nur die jeweils
   maßgebliche Quelle ändern.
6. **Repositoryvertrag ausführen.** Vor dem Merge mindestens `check`, für
   relevante Änderungen `fullCheck` oder `ciCheck`.
7. **Checkout-Sauberkeit prüfen.** Ein normaler Verifikationslauf darf keine
   tracked Dateien als Nebeneffekt verändern.

## Häufige Befehle

| Zweck | Befehl |
| --- | --- |
| Modul kompilieren | `./gradlew :<modul>:compileJava` |
| Modultests | `./gradlew :<modul>:test` |
| Anwendungstests | `./gradlew :app:test` |
| Browser-E2E | `./gradlew :app:e2eTest` |
| Container-E2E | `./gradlew :app:dockerE2eTest` |
| alle Gradle-Testschichten | `./gradlew test` |
| Tests plus Vertragsprüfung | `./gradlew check` |
| Docker-/Solver-/Reproduktionsverträge | `./gradlew fullCheck` |
| autoritativer CI-Lebenszyklus | `./gradlew --no-configuration-cache ciCheck` |
| Anwendung lokal starten | `./gradlew run` |
| Distribution bauen | `./gradlew :app:installDist` |
| Standardimage bauen | `docker build -t regelsuche .` |
| Proof-Image bauen | `docker build -f Dockerfile.proof -t regelsuche-proof .` |

## Architekturregeln

### Innerer Kern

- mathematische Kernmodule bleiben frei von Web-, Datenbank-, Container- und
  GitHub-Abhängigkeiten;
- neue Infrastruktur wird über Ports und äußere Adapter angebunden;
- eine neue Abhängigkeit muss fachlich gerichtete Verantwortung ausdrücken;
- zyklische oder nur aus Bequemlichkeit eingeführte Modulabhängigkeiten sind
  nicht zulässig.

### Rewrite-Semantik

- eine Regel beschreibt einen begrenzten, nachvollziehbaren mathematischen
  Schritt;
- mehrstufige Strategien werden als Pfad, Makro oder `RewriteProgram`
  modelliert, nicht als benchmarkgeformte Spezialregel;
- Annahmen und Nebenbedingungen bleiben explizit;
- Regelidentität, Herkunft und aktive Packs werden im Inventar gebunden;
- Änderungen an kanonischer Identität oder Normalisierung benötigen eine
  Migrations- und Evidence-Auswirkungsanalyse.

### Evidence und Claims

- Ergebnisobjekte enthalten keine stärkere Aussage als ihre Evidence trägt;
- Validierung, Proof, Novelty, Interestingness, Utility, Promotion und Public
  Evidence bleiben separate Achsen;
- fehlende oder technische nicht ausgeführte Arbeit wird nicht als Erfolg
  interpretiert;
- negative Ergebnisse und Nullresultate bleiben erhalten;
- Wandzeit darf keine kanonische Work-Bilanz ersetzen.

### GitHub Actions

Verifikationssemantik gehört in Gradle, JUnit oder `scripts/`. Workflows dürfen:

- Runner und Werkzeuge provisionieren;
- den checkout-eigenen Einstiegspunkt ausführen;
- bereits erzeugte Artefakte veröffentlichen;
- GitHub-spezifische Releaseoperationen durchführen.

Workflows dürfen keine parallelen Fixtures, Assertions, Testmatrizen oder
alternativen fachlichen Erfolgsregeln definieren.

## Änderungen nach Typ

### Neue Transformationsregel

1. fachlichen Gültigkeitsbereich und Annahmen definieren;
2. Regel als atomare AST-Transformation implementieren;
3. positive, negative und Annahmenfälle testen;
4. Pack, Tier, Provenienz und Aktivierungsprofil festlegen;
5. Inventar- und Ablationsevidence aktualisieren;
6. Nutzer- oder Referenzdokumentation ergänzen, falls sichtbar.

### Neue Suchstrategie oder Heuristik

1. den bestehenden Search-Port implementieren;
2. deterministische Tie-Breaks und Budgetsemantik festlegen;
3. Work Accounting und Trace-Ereignisse erhalten;
4. gegen Referenzstrategien mit gleichem Informations- und Ressourcenvertrag
   messen;
5. keine universelle Rangfolge aus einem einzelnen Track ableiten.

### Neue REST-Funktion

1. fachliche Operation und Berechtigungs-/Größengrenzen definieren;
2. Handler und Fehlervertrag implementieren;
3. OpenAPI 3.1 aktualisieren;
4. fokussierte HTTP-Integrationstests ergänzen;
5. sichtbaren GUI-Ablauf und Browser-E2E aktualisieren oder die fehlende GUI
   ausdrücklich als Produktlücke dokumentieren;
6. keine parallele Endpoint-Tabelle in README oder Nutzerhandbuch anlegen.

### Neue UI-Funktion

1. Einstiegspunkt, Voraussetzungen und sichtbare Zustände entwerfen;
2. leere, ladende, erfolgreiche und fehlerhafte Zustände testen;
3. Browser-E2E und bei Bedarf testgenerierte Screenshots aktualisieren;
4. Benutzerhandbuch und Workflowseite anpassen;
5. interne Klassennamen und Statuskonstanten nicht ungefiltert anzeigen.

### Neuer Evidence- oder Schema-Vertrag

1. fachliche Autorität und Claim-Grenze definieren;
2. kanonische Identität und Versionierung festlegen;
3. striktes Schema und Runtime-Codec implementieren;
4. Substitution, Duplikate, unbekannte Felder, Hash- und Ressourcenfehler
   negativ testen;
5. unabhängigen Verifier ergänzen;
6. [Schema-Katalog](schema-catalog.md) und fachliche Dokumentation aktualisieren.

### Neue Persistenzimplementierung

1. bestehenden Port verwenden oder begründet erweitern;
2. atomare Schreib- und Fehlersemantik definieren;
3. In-Memory-/Datei-Charakterisierung als Referenz erhalten;
4. reale Integration mit Testcontainers aus einem Checkout testen;
5. Migration, Backups, Suchindex und Betriebsgrenzen dokumentieren.

## Dokumentation und generierte Artefakte

- Nutzerabläufe stehen in Markdown; REST-Verträge in OpenAPI.
- Aktueller Forschungsstand steht ausschließlich in `discovery-status.md`.
- Generierte Status- und Ergebnisdateien unter `docs/generated/` werden nicht
  manuell editiert.
- Screenshots und Videos werden durch Browser-E2E bewusst neu erzeugt:

```bash
./gradlew :app:e2eTest -Pregelsuche.recordDocs=true
```

- Ein normaler Build oder `ciCheck` darf den Source Tree nicht verändern.
- Für jede Dokumentänderung gelten
  [Dokumentationskonventionen](documentation-conventions.md) und
  [Qualitätscheckliste](documentation-quality-checklist.md).

## Fehleranalyse

1. den ersten fachlichen Fehler suchen, nicht nur den letzten Gradle-Stacktrace;
2. zugehörige JUnit-, Verifier- und Containerartefakte unter `build/` prüfen;
3. den kleinsten fehlschlagenden Task lokal reproduzieren;
4. bei Evidence-Fehlern Eingaben, kanonische Hashes und Vollständigkeitsbilanz
   vergleichen;
5. externe Infrastrukturfehler getrennt von mathematischen Ergebnissen
   klassifizieren;
6. keine Schranke abschwächen, bevor die Ursache verstanden ist.

## Pull-Request-Checkliste

- [ ] Verantwortung und Modulgrenze sind klar.
- [ ] Positive, negative, Fehler- und Budgetfälle sind getestet.
- [ ] Annahmen und Claim-Grenzen bleiben sichtbar.
- [ ] Externe Verträge sind versioniert und dokumentiert.
- [ ] Der fokussierte Test und der erforderliche Repository-Lifecycle sind grün.
- [ ] Der Verifikationslauf hinterlässt einen sauberen Checkout.
- [ ] Nutzer-, Architektur-, Betriebs- oder Forschungsdokumentation ist
      entsprechend aktualisiert.
- [ ] Generierte Artefakte wurden durch ihre Generatoren und nicht manuell
      verändert.

## Weiterführende Referenz

- [Architektur](architecture.md)
- [Modulstruktur](module-structure.md)
- [Dependency-Regeln](dependency-rules.md)
- [Testing](testing.md)
- [Dokumentationsindex](README.md)
