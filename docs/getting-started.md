# Getting Started

## 30 Sekunden zur ersten Demo

```bash
docker build -t regelsuche .
docker run --rm -p 8080:8080 regelsuche
```

Anschließend [http://localhost:8080/](http://localhost:8080/) öffnen. Im
Demo-Bereich stehen neun geführte Einstiege bereit — von der **Binomischen
Formel** über **Bruchkürzung**, **Trigonometrie**, **Polynom-Expansion** und
**Makroregel-Lernen** bis zu den vier mathematischen Domänen **Gleichung**,
**Ungleichung**, **Ableitung** und **Matrix**.

Ein Klick führt den vollständigen Produktfluss aus: Die Workbench übernimmt die
Eingabe, startet die Suche, öffnet die relevanten Ergebnisbereiche und stellt
Suchgraph, Rechenweg, Replay, erkannte Struktur sowie Exportmöglichkeiten bereit.

Eine kuratierte Übersicht der Demos mit echten Screenshots steht in der
[Demo-Gallery](demo-gallery.md).

## Ohne Docker (lokaler Gradle-Lauf)

```bash
./gradlew run
```

Erfordert JDK 21. Der Server bindet auf
`http://127.0.0.1:8080/` und benutzt eine lokale JSON-Datei unter
`./data/` für die Persistenz.

## Erste Schritte in der Web-Workbench

1. **Demo auswählen oder eigenen Ausdruck eingeben.** Für einen eigenen Lauf im
   Tab **Workbench** Ausdruck, Typ, Profil, Ziel und Regel-Domänen festlegen und
   **Suche starten** wählen.
2. **Rechenwege prüfen.** Im Tab **Pfade** die gefundenen Alternativen laden,
   eine Darstellung auswählen und einen Pfad für die Detail-Erklärung öffnen.
3. **Suchraum verstehen.** Im Tab **Graph** zwischen semantischer Erklärung,
   Hauptpfad, Complexity Map und Rohgraph wechseln. Filter und Schalter blenden
   Alternativen, triviale Schritte und Varianten gezielt ein oder aus.
4. **Schritte nachvollziehen.** Im Tab **Replay** den gewählten Rechenweg
   schrittweise abspielen und hervorgehobene Änderungen sowie Annahmen prüfen.
5. **Ergebnisse bewerten.** Die Tabs **Regelkandidaten**, **Identitäten**,
   **Dashboard**, **Benchmark**, **Vergleich** und **Suchgedächtnis** zeigen
   wiederkehrende Strukturen, Qualitätsmerkmale und alternative Ergebnisse.
6. **Weiterverwenden.** Über **Inventar**, **Proof-Jobs**, **Exporte**,
   **Didaktik** und **Rule-IDE** lassen sich Regeln verwalten, Beweisaufträge
   ausführen, Ergebnisse herunterladen und fachliche Erweiterungen bearbeiten.

Der ausführliche, rollenbezogene Ablauf steht unter
[User Workflows](user-workflows.md); alle Bedienelemente beschreibt das
[Benutzerhandbuch der Web-Workbench](web-ui-user-guide.md).

## REST-Integration

Die technische REST-Referenz wird ausschließlich über Swagger/OpenAPI gepflegt.
Dort stehen Methoden, Pfade, Request- und Response-Schemata sowie Statuscodes.
Dieses Getting-Started-Dokument dupliziert diese Angaben bewusst nicht, sondern
beschreibt den grafischen Einstieg in dieselben Funktionen.

Die verbindliche Trennung ist in der
[Dokumentationskonvention](documentation-conventions.md) festgehalten.

## Optional: Full Mode mit Neo4j

```bash
docker compose up --build
```

Startet App + Neo4j 5 Community + persistentes Volume und setzt
`NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` automatisch. Die App schaltet
dann den `REMOTE_NEO4J`-Persistenzmodus aktiv (siehe
[Architektur](architecture.md)).

## Nächste Schritte

- [Web-Workbench](web-workbench.md) – Bedienbereiche, sichtbare Ergebnisse und technische Zuordnung.
- [User Workflows](user-workflows.md) – Geführte Abläufe für Lernen, Discovery, Vergleich, Proof und Export.
- [Demo-Gallery](demo-gallery.md) – Visuelle Übersicht aller Flows.
- [Architektur](architecture.md) – Leitplanken und Überblick.
- [Modulstruktur](module-structure.md) – Logische Module und Paketmapping.
- [Dependency-Regeln](dependency-rules.md) – Erlaubte Abhängigkeitsrichtungen.
- [Such-Intelligenz](search-intelligence-roadmap.md) – Strategien, Beam, A\*, Equality Saturation.
- [Math-Domains](math-domains.md) – Gleichungen, Ungleichungen, Ableitung, Lineare Algebra.
- [Proof-Bridge](proof-bridge.md) – Vom Suchpfad zum formalen Beweis.
- [Macro-Rules](macro-rules.md) – Wie das System eigene Regeln lernt.
- [Testing](testing.md) – Unit-, Integrations- und Browser-E2E-Tests.
- [Testing-Strategie](testing-strategy.md) – Schichtung für schnelle Core-Tests und stabile E2E-Layer.
