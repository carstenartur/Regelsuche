# Unterstützte Grenzen und bewusste Nicht-Ziele

Diese Seite beschreibt, was Regelsuche derzeit zuverlässig unterstützt und
welche Fähigkeiten bewusst begrenzt, optional oder noch nicht qualifiziert
sind. Sie ist keine Roadmap und kein globaler Projektstatus; aktuelle
Forschungsergebnisse stehen in [Discovery- und Forschungsstand](discovery-status.md).

## Produkt- und Einsatzgrenze

Regelsuche ist eine Plattform für explizite symbolische Transformationsräume,
Suchpfade, Candidate Formation und reproduzierbare Evidence. Das Projekt ist
nicht als allgemeiner Ersatz für ein Computer-Algebra-System, einen
Theorem-Prover oder eine produktionsfertige Mehrnutzerplattform positioniert.

## Unterstützte mathematische Grundlage

### Ausdrücke und Regeln

Unterstützt werden strukturierte AST-Ausdrücke mit Zahlen, Variablen, Summen,
Produkten, Potenzen und ausgewählten Funktionsformen. Transformationen werden
als konkrete Regelanwendungen an AST-Positionen ausgeführt.

Regeln können:

- Platzhalter binden;
- Annahmen und Nebenbedingungen emittieren;
- Herkunft, Tier, Pack und Lizenzinformationen tragen;
- als Kernel-, First-Party-, Regeldatei- oder Plugin-Beitrag aktiviert werden;
- in Pfaden, Makros und begrenzten `RewriteProgram`s komponiert werden.

Ein content-addressed Inventar bindet den tatsächlich aktiven Regelbestand an
einen Lauf.

### Algebra

Der Schwerpunkt liegt auf rationalen und polynomialen Transformationen. Exakte
rationale Polynomnormalformen und mehrere lokale Faktorisierungs-, Kürzungs-
und Vereinfachungsregeln sind vorhanden.

Exakte univariate Polynomdivision ist implementiert, gehört aber zum
standardmäßig deaktivierten Pack `core-exact-polynomial-division`. Das
Default-Inventar des aktuellen targetfreien Vergleichs behält deshalb bewusst
den kubischen Verlustfall. Implementierte Fähigkeit und gemessene
Standardkonfiguration werden nicht nachträglich vermischt.

### Weitere Domänen

Es existieren begrenzte Regel- und Darstellungsunterstützung für:

- Gleichungen und Ungleichungen;
- grundlegende Analysis;
- trigonometrische, logarithmische und radikale Ausdrücke;
- lineare Algebra und kleine Matrixfälle;
- endliche Differenzen und exakte lineare Rekurrenzen in einer separaten
  Discovery-Domäne.

Diese Unterstützung ist domänenspezifisch und ersetzt keine vollständige
Symbolikbibliothek für Analysis, Trigonometrie, Matrizen oder nichtkommutative
Algebra.

## Annahmen und Definitionsbereiche

Regeln können Nichtnullheit, Positivität, Nichtnegativität und andere
Voraussetzungen explizit modellieren. Retained Pfade und entsprechende
Benchmarkfälle prüfen emittierte Nebenbedingungen gegen den Fallvertrag.

Nicht unterstützt ist eine universelle, vollständige Annahmenlogik für alle
Ausdrucksarten und externen Backends. Insbesondere können externe Systeme nicht
jede zusammengesetzte Annahme in ihrer nativen Symbolkonfiguration abbilden.
Eine unabhängige annahmenbewusste Validierung jedes extern erzeugten
Simplifier-Outputs bleibt im aktuellen Vergleich ein Coverage Gap.

## Suche und Equality Saturation

Unterstützt werden mehrere begrenzte Suchstrategien und Equality Saturation.
Alle produktiven Profile besitzen harte Grenzen für Tiefe, Zustände,
Kandidaten, Zeit oder Arbeit.

Nicht unterstützt sind:

- unbeschränkte vollständige Exploration unendlicher Ausdrucksräume;
- eine allgemeine Garantie, dass die mathematisch „einfachste“ Form gefunden
  wird;
- ein universelles Vergleichsscore über unterschiedliche Informationsregime;
- vollständige exakte Nebenbedingungsprovenienz für jeden Equality-Saturation-
  Vergleichsfall;
- Serialisierung jedes beliebigen internen Frontier- oder MCTS-Zustands zur
  bytegenauen Fortsetzung.

Checkpoints können definierte fachliche Zustände binden; sie sind kein
allgemeiner JVM-Snapshot.

## Discovery und Lernen

Regelsuche kann aus targetfreien Suchbeobachtungen Kandidaten bilden,
verallgemeinern und durch getrennte Evidence-Stufen führen. Eine qualifizierte
interne autonome Referenz-Campaign und eine separate Mehrdomänenqualifikation
liegen vor.

Daraus folgt nicht:

- externe mathematische Neuheit;
- fachliche Interessantheit;
- formaler Beweis des retained Produktionskandidaten;
- automatische Promotion in einen autoritativen Regelbestand;
- Public Evidence oder Veröffentlichung.

Das stärkere Flagship-Experiment zur erlernten proof-carrying
Selbstverbesserung ist technisch vorbereitet, aber noch nicht mit realem
VALIDATION- und FINAL-TEST-Material ausgeführt.

## Counterexample Search und Validation

Counterexample Search ist ein Angriffsmechanismus. Ein reproduzierbarer
Gegenfall widerlegt die betroffene allgemeine Behauptung innerhalb ihres
Vertrags. Ein begrenzter Nicht-Fund ist kein Beweis.

Mögliche Ergebnisse bleiben getrennt:

- Gegenbeispiel gefunden;
- im Budget kein Gegenbeispiel gefunden;
- technisch oder semantisch inconclusive;
- Backend nicht verfügbar;
- Fall unsupported.

Validation gilt nur für die gebundene Suite und den gebundenen
Informationszugriff. Sie ist keine Garantie allgemeiner mathematischer Wahrheit.

## Proof und Solver

Regelsuche kann solver-neutrale Obligationen sowie Lean- und SMT-Artefakte
erzeugen. Z3 und cvc5 stehen im Proof-Image bereit; Lean kann optional ergänzt
werden.

Ein Proof-Status wird nur aus dem tatsächlich ausgeführten Backend-Ergebnis
abgeleitet. Folgende Grenzen gelten:

- eine erzeugte Proof-Datei ist noch kein bestätigter Beweis;
- fehlende Werkzeuge, Timeout und Prozessfehler sind technische Ergebnisse;
- ein Lean-Artefakt mit offenen Platzhaltern autorisiert keinen formalen Status;
- numerische Relationssuche oder heuristische Symbolic Regression erzeugt
  Hypothesen, keine Proofs;
- unterschiedliche Solver-Certificate-Stärken bleiben sichtbar.

## Vergleichende Benchmarks

Vergleiche sind track-spezifisch. Zielgerichtete Suche, targetfreie
Simplification, Equality Validation, Rediscovery, Open-Target Discovery und
Campaign-Steuerung dürfen nicht in einem universellen Leaderboard
zusammengezogen werden.

Im aktuellen targetfreien Simplification-Track erreicht Regelsuche mit dem
Default-Inventar sechs von sieben gepinnten Referenzformen, SymPy sieben von
sieben. Der Track bleibt als negatives Ergebnis retained. Siehe
[Comparative Discovery Benchmarks](discovery-benchmarks.md).

Wandzeit und Durchsatz sind umgebungsabhängige Engineering-Metriken. Die
kanonische mathematische Arbeitsbilanz verwendet getrennte Work-Zähler und
Budgets.

## Persistenz und Betrieb

Unterstützte Modi:

- lokale Dateipersistenz für die Standarddemo;
- PostgreSQL, Hibernate ORM und Hibernate Search im Full Mode;
- optionale Neo4j-Provenienz;
- dateibasierte Evidence-, Proof- und Exportartefakte.

Der Full Mode ist eine technische Referenzintegration, keine zugesicherte
hochverfügbare Produktionsplattform. Backup, Restore, Migration, Monitoring,
Kapazitätsplanung und Datenschutz müssen für einen konkreten Betrieb separat
bewertet werden.

## Web-Sicherheit

Die Standarddemo bindet lokal und verwendet HTTP ohne Anmeldung. Die Workbench
besitzt begrenzte Härtungsoptionen, ersetzt aber keine vollständige
Mehrnutzer-Sicherheitsarchitektur.

Nicht als integrierte Komplettlösung zugesichert sind insbesondere:

- OAuth/OIDC und feingranulare Rollenmodelle;
- vollständiger CSRF- und Abuse-Schutz;
- verteiltes Rate Limiting und Quoten;
- Mandantentrennung;
- WAF, zentrale Secret-Verwaltung und Audit-Backend;
- öffentliche Bereitstellung mit automatisch sicherer Konfiguration.

Extern erreichbare Installationen benötigen authentifiziertes TLS, eigene
Credentials, Netzwerk- und Ressourcengrenzen sowie ein Betriebskonzept. Siehe
[Web-Workbench Security](web-workbench-security.md).

## Plugins und externe Verteilung

Lokale kryptografische Artefakt-, Index- und Trust-State-Verträge sind
implementiert. Dazu gehören Signaturprüfung, Publisher-Trust, Widerruf,
authentisierte Indexrevisionen und Replay-/Fork-Schutz.

Nicht qualifiziert ist ein öffentliches End-to-End-Ökosystem für:

- gehosteten Indextransport;
- Download, Installation und atomare Aktivierung;
- Update, Entfernung und Rollback;
- unabhängige Community-Publishing-Prozesse;
- produktiven Incident- und Revocation-Betrieb.

`PUBLIC_PLUGIN_DISTRIBUTION` bleibt deshalb `BLOCKED`. Siehe
[Erweiterungssystem](extension-system.md).

## Externe mathematische Neuheit und Review

Projekt-Novelty vergleicht einen Kandidaten mit dem gebundenen internen
Inventar. Sie ist implementiert und darf nicht als globale Neuheit formuliert
werden.

Eine positive externe Neuheitsentscheidung benötigt mindestens:

- eingefrorenen Kandidaten und Evidence-Roots;
- dokumentierte Literatur- und Datenbanksuche;
- vollständige Treffer- und Zugriffsbilanz;
- unabhängige qualifizierte Reviewer;
- konservative Entscheidung mit Suchumfang und Restunsicherheit.

Diese reale Prüfung ist noch nicht abgeschlossen; das Profil bleibt `BLOCKED`.

## Bewusste Nicht-Ziele

- Erfolg durch versteckte Targets oder nachträgliche Corpus-Anpassung;
- Entfernen negativer Ergebnisse aus veröffentlichten Evidence-Bundles;
- Gleichsetzen von Search, Validation, Proof, Novelty und Promotion;
- wissenschaftliche Claims allein aus CI-Grün oder Schema-Validität;
- Ersetzen lokaler Reproduzierbarkeit durch GitHub-spezifische Workflowlogik;
- Aktivieren einer Optimierung ohne semantische und Work-Accounting-Parität.

## Siehe auch

- [Discovery- und Forschungsstand](discovery-status.md)
- [Architektur](architecture.md)
- [Comparative Discovery Benchmarks](discovery-benchmarks.md)
- [Proof Bridge](proof-bridge.md)
- [Persistenz](persistence.md)
- [Erweiterungssystem](extension-system.md)
