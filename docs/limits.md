# Unterstützte Grenzen und bewusste Nicht-Ziele

Diese Seite beschreibt, was Regelsuche derzeit zuverlässig unterstützt und
welche Fähigkeiten bewusst begrenzt, optional oder noch nicht qualifiziert
sind. Sie ist keine Roadmap und kein globaler Projektstatus; aktuelle
Forschungsergebnisse stehen in
[Discovery- und Forschungsstand](discovery-status.md).

## Produkt- und Einsatzgrenze

Regelsuche ist eine Plattform für explizite symbolische Transformationsräume,
Suchpfade, Candidate Formation und reproduzierbare Evidence. Das Projekt ist
kein allgemeiner Ersatz für ein Computer-Algebra-System, einen Theorem-Prover
oder eine produktionsfertige Mehrnutzerplattform.

Ein erfolgreicher technischer Lauf autorisiert nur den Claim des ausgeführten
Vertrags. Insbesondere werden folgende Stufen nicht gleichgesetzt:

- Sucherfolg;
- exakte Produktrekonstruktion;
- Irreduzibilität in einem deklarierten Ring;
- vollständige Faktorisierung;
- formaler Beweis;
- externe mathematische Neuheit;
- Veröffentlichung.

## Unterstützte mathematische Grundlage

### Ausdrücke, Regeln und Vorbereitung

Unterstützt werden strukturierte AST-Ausdrücke mit Zahlen, Variablen, Summen,
Produkten, Potenzen und ausgewählten Funktionsformen. Transformationen werden
als konkrete Regelanwendungen an AST-Positionen ausgeführt.

Regeln können:

- Platzhalter binden;
- Annahmen und Nebenbedingungen emittieren;
- Herkunft, Tier, Pack und Lizenzinformationen tragen;
- als Kernel-, First-Party-, Regeldatei- oder Plugin-Beitrag aktiviert werden;
- in Pfaden, Makros und begrenzten `RewriteProgram`s komponiert werden;
- ein explizites Applicability-Schema für regelgerichtete Vorbereitung tragen.

Ein content-addressed Inventar bindet den tatsächlich aktiven Regelbestand an
einen Lauf.

Fast passende Regeln können über zwei begrenzte Schichten vorbereitet werden:

- native Exact-Spezialisten für Polynomquotienten, AC-Faktorexposition,
  gemeinsame Monomfaktoren, perfekte Quadrate und gemeinsame Nenner;
- eine bounded pattern-targeted local bridge über ein eingefrorenes Inventar
  äquivalenzbewahrender Vorbereitungsregeln.

Jeder native Spezialist ist an seine ausdrücklich registrierte Principal-ID
gebunden. Ein ähnlich aussehendes importiertes oder gelerntes Pattern erbt
keinen fremden Solververtrag. Der Unified Coordinator ist implementiert, aber
noch nicht als allgemeines Workbench-/CLI-Defaultprofil qualifiziert.

### Algebra

Der Schwerpunkt liegt auf rationalen und polynomialen Transformationen. Exakte
rationale Polynomnormalformen und mehrere lokale Faktorisierungs-, Kürzungs-
und Vereinfachungsregeln sind vorhanden.

Exakte univariate Polynomdivision ist implementiert, gehört aber zum
standardmäßig deaktivierten Pack `core-exact-polynomial-division`. Das
Default-Inventar des aktuellen targetfreien Vergleichs behält deshalb bewusst
den kubischen Verlustfall. Implementierte Fähigkeit und gemessene
Standardkonfiguration werden nicht nachträglich vermischt.

#### Domänenbewusste Polynomfaktorisierung

Implementiert ist ein allgemeiner typisierter Kern aus:

- exakten Integer- und Rational-Koeffizientendomänen;
- expliziten Primkörpern `PrimeField` mit deterministisch geprüfter Primzahl;
- Polynomringen mit geordneten Variablen und expliziter Monomordnung;
- kanonischen unveränderlichen Sparse-Polynomen;
- verlustfreier univariater Koeffizientenansicht;
- `FactorizationRequest` mit request-weiten Grenzen für Variablenzahl,
  Gesamtgrad, Termzahl, Quellkoeffizientenbitlänge, Kandidaten und Arbeit;
- exakter Inhalts- und Primitivteilnormalisierung für `Z[x]` und `Q[x]`;
- exakter Ableitung, Multiplikation, Polynomdivision und monischer Normierung;
- budgetiertem euklidischem Polynom-GGT über exakten Feldern;
- charakteristik-0-quadratfreier Zerlegung mit Multiplizitäten;
- deterministischer Berlekamp-Faktorisierung quadratfreier univariater
  Polynome über einem ausdrücklich deklarierten `F_p`;
- unabhängiger Produkt-, Koprimheits- und Rabin-/Frobenius-
  Irreduzibilitätsprüfung der Primkörperfaktoren;
- backendneutralem `FactorizationEngine`-SPI;
- untrusted Engine-Proposals und ausdrücklich retained Backend-Claims;
- unabhängiger Vertrags- und Produktprüfung durch `FactorizationVerifier`;
- stage-getrenntem, kanonisch geordnetem `PolynomialWorkLedger`.

Die Inhaltsnormalisierung erzeugt für `Z[x]`- und `Q[x]`-Quellen einen exakten
rationalen Skalar und einen primitiven ganzzahligen Teil mit positivem
Leitkoeffizienten. Sie prüft die ganzzahlige Zwischenform und die ursprüngliche
Quelle durch unabhängige Rückmultiplikation. Eine zusätzliche explizite
Zwischenkoeffizienten-Bitgrenze verhindert unbegrenztes Nenner-LCM- und
Koeffizientenwachstum. Das Arbeitsbudget bleibt Eigentum desselben
`FactorizationRequest` und kann zwischen Algorithmusstufen nicht zurückgesetzt
werden.

Die quadratfreie Zerlegung rekonstruiert das Quellpolynom und prüft für jeden
ausgegebenen Faktor `gcd(f, f') = 1`. Quadratfrei bedeutet noch nicht
irreduzibel.

#### Vollständige Faktorisierung im deklarierten Primkörper

`FiniteFieldFactorization` faktorisiert ein nichtkonstantes, quadratfreies
univariates Polynom vollständig in dem durch `PrimeField` deklarierten Ring
`F_p[x]`.

Der positive Abschluss umfasst:

1. Monisierung unter Beibehaltung des ursprünglichen Leitkoeffizienten als
   Einheit des Feldes;
2. exakte Quadratfreiheitsprüfung;
3. Konstruktion der Berlekamp-Matrix `Q - I`;
4. deterministische RREF- und Nullraumberechnung;
5. erneute Prüfung jedes Nullraumvektors gegen die unveränderte Matrix;
6. deterministisches Splitting nach Basisreihenfolge und Restklassen;
7. exakte Rekonstruktion des Ausgangspolynoms;
8. paarweise Koprimheitsprüfung;
9. Rabin-/Frobenius-Irreduzibilitätsprüfung jedes ausgegebenen Faktors;
10. Abgleich von Faktoranzahl und Berlekamp-Nullität.

Damit ist `COMPLETED` ein vollständiger Faktorisierungsabschluss **im
angegebenen Primkörper**. Daraus folgt kein vollständiger Claim für ein
ursprüngliches Polynom über `Z[x]` oder `Q[x]`.

Die erste Primkörperimplementierung besitzt bewusst enge Grenzen:

- der Modul wird als deterministisch geprüfte positive `int`-Primzahl
  angegeben;
- die Eingabe muss genau eine Variable besitzen und quadratfrei sein;
- die Anzahl enumerierter Restklassen wird durch
  `FiniteFieldFactorizationPolicy.maxEnumeratedFieldElements` begrenzt;
- die Peak-Größe der quadratischen Berlekamp-Matrix wird vor ihrer Allokation
  durch `maxBerlekampMatrixCells` begrenzt;
- sämtliche Arithmetik, Matrixreduktion, Splitting- und Verifikationsarbeit
  teilt ein nicht zurücksetzbares Requestbudget;
- Budget- oder Policy-Erschöpfung bleibt `BUDGET_INCONCLUSIVE` und wird nicht
  als Irreduzibilität ausgegeben.

Details stehen unter
[Deterministische Faktorisierung über Primkörpern](finite-field-factorization.md).

#### Noch offene vollständige `Z[x]`-/`Q[x]`-Pipeline

Die vorhandene Primkörperfaktorisierung ist ein notwendiger Baustein, aber noch
keine vollständige Faktorisierungsengine für ganzzahlige oder rationale
Quellen. Weiterhin offen sind:

- geeignete Primzahlauswahl mit retained Ablehnungsgründen für ungeeignete
  Primzahlen;
- Abbildung des primitiven ganzzahligen Polynoms in den ausgewählten
  Primkörper;
- Hensel-Lifting der modularen Faktoren;
- ganzzahlige Faktorrekomposition, zunächst etwa Zassenhaus;
- spätere LLL-/van-Hoeij-Rekombination, wenn sie qualifiziert ist;
- exakte rationale Faktorreassemblierung;
- Integration der vollständigen `Z[x]`-/`Q[x]`-Evidence in den allgemeinen
  Engine-/Verifier-Vertrag;
- beliebige unterstützte Grade und Faktorgradpartitionen unter eingefrorenen
  Qualifikationsbudgets;
- multivariate Faktorisierung.

Die weiterhin integrierte `BinaryQuarticFactorizationEngine` unterstützt
unabhängig davon exakt und begrenzt:

- Koeffizienten in `Z`;
- zwei strukturelle Atome;
- homogene Polynome vierten Grades;
- Faktorgradaufteilung `2 + 2`;
- begrenzte ganzzahlige Faktorkoeffizienten, Kandidaten und Work Units;
- begrenzte univariate Quartiken nach expliziter Homogenisierung mit einer
  strukturellen Einheit.

Jeder positive Engine-Kandidat wird im Quellring exakt zurückmultipliziert.
Daraus folgt eine verifizierte Zerlegung, aber ohne zusätzliche Evidence noch
kein Nachweis, dass alle Faktoren irreduzibel oder die Zerlegung vollständig
ist. Insbesondere gilt:

- `NO_CANDIDATE` ist kein Irreduzibilitätsbeweis;
- ein Backend-Claim erfüllt keinen `INDEPENDENT_COMPLETE`-Request;
- die Quartikengine autorisiert keinen Claim für andere Grade oder
  Faktorgradaufteilungen;
- die allgemeine rationale Inhaltsnormalisierung ist implementiert, eine
  vollständige `Q[x]`-Faktorisierungsengine jedoch noch nicht;
- ein vollständiger Abschluss in `F_p[x]` darf nicht als Abschluss in `Z[x]`
  oder `Q[x]` umetikettiert werden.

Weiterführende Seiten:

- [Domänenbewusste Polynomfaktorisierung](domain-aware-polynomial-factorization.md)
- [Univariate Polynomgrundlage, Inhalt und quadratfreie Zerlegung](univariate-polynomial-foundation.md)
- [Univariate Inhalts- und Primitivteilnormalisierung](univariate-content-normalization.md)
- [Deterministische Faktorisierung über Primkörpern](finite-field-factorization.md)
- [Semantische Polynomansicht und quartische Zerlegungsengine](polynomial-decomposition-synthesis.md)

### Gleichungssysteme, Matrizen und weitere Domänen

Für affine skalare Gleichungssysteme existiert ein exakter Objektpfad über
`A*x=b`, unabhängige Matrixblöcke, RREF und eindeutige, parametrisierte oder
inkonsistente Lösungsklassifikation. Symbolische Systeme können bei expliziten
Variablen- und Modellrollen als Eigenproblem erkannt werden. Namen allein
erzeugen keine physikalische Interpretation.

Daneben existieren begrenzte Regel- und Darstellungsunterstützung für:

- Gleichungen und Ungleichungen;
- grundlegende Analysis;
- trigonometrische, logarithmische und radikale Ausdrücke;
- lineare Algebra und kleine Matrixfälle;
- endliche Differenzen und exakte lineare Rekurrenzen in einer separaten
  Discovery-Domäne.

Diese Unterstützung ist domänenspezifisch und ersetzt keine vollständige
Symbolikbibliothek für Analysis, Trigonometrie, Matrizen, Operatoralgebra oder
nichtkommutative Algebra. Typisierte Repräsentationsbrücken nehmen noch nicht
direkt am Unified Preparation Coordinator teil.

## Annahmen und Definitionsbereiche

Regeln können Nichtnullheit, Positivität, Nichtnegativität und weitere
Voraussetzungen explizit modellieren. Applicability-Schemata binden typisierte
Required-Assumption-Templates; fehlende oder unbekannte Guards autorisieren
keinen Kandidaten.

Der allgemeine lokale Bridge-Pfad prüft Ausgangs- und durch verifizierte
Vorbereitungsschritte erzeugte Annahmen gemeinsam. Native Exact-Spezialisten
besitzen eigene fragmentbezogene Annahmen- und Zertifikatsverträge. Guarded
fremde Principal-Schemata verwenden den nativen Exact-Pfad noch nicht, solange
die Spezialisten keine terminalen Matcher-Bindungen exponieren.

Nicht unterstützt ist eine universelle, vollständige Annahmenlogik für alle
Ausdrucksarten und externen Backends. Insbesondere können externe Systeme nicht
jede zusammengesetzte Annahme in ihrer nativen Symbolkonfiguration abbilden.
Eine unabhängige annahmenbewusste Validierung jedes extern erzeugten
Simplifier-Outputs bleibt ein Coverage Gap.

## Suche und Equality Saturation

Unterstützt werden mehrere begrenzte Suchstrategien, ein vollständiger
begrenzter Reachability-Oracle für endliche Closures, regelgerichtete lokale
Bridges und Equality Saturation. Alle produktiven Profile besitzen harte
Grenzen für Tiefe, Zustände, Kandidaten, Zeit oder Arbeit.

Nicht unterstützt sind:

- unbeschränkte vollständige Exploration unendlicher Ausdrucksräume;
- eine allgemeine Garantie, dass die mathematisch „einfachste“ Form gefunden
  wird;
- ein universelles Vergleichsscore über unterschiedliche Informationsregime;
- vollständige exakte Nebenbedingungsprovenienz für jeden Equality-Saturation-
  Vergleichsfall;
- eine gemeinsame Multi-Principal-Frontier und eine einzige geteilte
  AST-/Value-Traversierung für alle Vorbereitungspfade;
- Serialisierung jedes beliebigen internen Frontier- oder MCTS-Zustands zur
  bytegenauen Fortsetzung.

Checkpoints können definierte fachliche Zustände binden; sie sind kein
allgemeiner JVM-Snapshot.

## Discovery, Lernen und Promotion

Regelsuche kann aus targetfreien Suchbeobachtungen Kandidaten bilden,
verallgemeinern und durch getrennte Evidence-Stufen führen. Eine qualifizierte
interne autonome Referenz-Campaign und eine separate Mehrdomänenqualifikation
liegen vor.

Rohe evolutionäre `CompiledGenomeRule`s sind ausführbar, bleiben aber bewusst
nicht äquivalenzbewahrend. Ein enger Promotionsmechanismus ist implementiert:
assumption-free gelernte Patternidentitäten können in einem begrenzten
kommutativen Polynomfragment exakt bewiesen und als neue
`PatternRewriteRule`-Identität mit Applicability-Schema und Promotion-Receipt
ausgegeben werden.

Diese Implementierung hat klare Grenzen:

- die referenzierten Validation-, Counterexample-, Holdout- und Leakage-Hashes
  werden gebunden, aber vom v1-Promoter nicht geladen oder semantisch geprüft;
- bedingte gelernte Regeln sind nicht unterstützt;
- Funktionen, Division und nicht exakte Koeffizienten liegen außerhalb des
  aktuellen Proof-Fragments;
- gelernte `RewriteProgram`s benötigen einen eigenen programmbasierten
  Applicability-/Replay-Vertrag;
- kein realer Produktions- oder Flagship-Kandidat wurde dadurch qualifiziert.

Daher bleibt der öffentliche Capability-Status `PROMOTION` `NOT_EVALUATED`.
Aus der vorhandenen Discovery- oder Promotionsmechanik folgt außerdem nicht:

- externe mathematische Neuheit;
- fachliche Interessantheit;
- formaler Beweis eines retained Produktionskandidaten außerhalb des eng
  unterstützten Fragments;
- automatische Aufnahme in einen autoritativen öffentlichen Regelbestand;
- Public Evidence oder Veröffentlichung.

Das stärkere Flagship-Experiment zur erlernten proof-carrying
Selbstverbesserung ist technisch vorbereitet, aber noch nicht mit realem
VALIDATION- und FINAL-TEST-Material ausgeführt.

## Counterexample Search und Validation

Counterexample Search ist ein Angriffsmechanismus. Ein reproduzierbarer
Gegenfall widerlegt die betroffene allgemeine Behauptung innerhalb seines
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
- unterschiedliche Solver-Certificate-Stärken bleiben sichtbar;
- der exakte Polynom-Pattern-Verifier beweist nur sein ausdrücklich begrenztes
  kommutatives Fragment und keine beliebigen mathematischen Regeln.

## Vergleichende Benchmarks

Vergleiche sind track-spezifisch. Zielgerichtete Suche, targetfreie
Simplification, Equality Validation, Rediscovery, Open-Target Discovery und
Campaign-Steuerung dürfen nicht in einem universellen Leaderboard
zusammengezogen werden.

Im aktuellen targetfreien Simplification-Track erreicht Regelsuche mit dem
Default-Inventar sechs von sieben gepinnten Referenzformen, SymPy sieben von
sieben. Der Track bleibt als negatives Ergebnis retained. Siehe
[Comparative Discovery Benchmarks](discovery-benchmarks.md).

Die getrennte SymPy-Amplifikationsmatrix zeigt vier zusätzliche lokal
vorbereitete Anwendungen über drei Regelfamilien. Sie ist ein begrenzter
Applicability-Nachweis und keine allgemeine Leistungsrangfolge.

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
- [Domänenbewusste Polynomfaktorisierung](domain-aware-polynomial-factorization.md)
- [Univariate Polynomgrundlage, Inhalt und quadratfreie Zerlegung](univariate-polynomial-foundation.md)
- [Univariate Inhalts- und Primitivteilnormalisierung](univariate-content-normalization.md)
- [Deterministische Faktorisierung über Primkörpern](finite-field-factorization.md)
- [ADR: Domänenbewusster Polynomkern statt Quartik-API](adr/domain-aware-polynomial-factorization.md)
- [Sicherer Regelvorbereitungskoordinator](safe-rule-preparation-coordinator.md)
- [Promotion gelernter Pattern-Regeln](learned-pattern-rule-promotion.md)
- [Architektur](architecture.md)
- [Comparative Discovery Benchmarks](discovery-benchmarks.md)
- [Proof Bridge](proof-bridge.md)
- [Persistenz](persistence.md)
- [Erweiterungssystem](extension-system.md)
