# Erweiterungssystem

Regelsuche besitzt mehrere Erweiterungsflächen mit unterschiedlichen Aufgaben,
Ausführungsrechten und Vertrauensgrenzen. Sie sind absichtlich nicht zu einem
universellen Plugin-Mechanismus zusammengefasst.

## Auswahl auf einen Blick

| Bedarf | Passende Erweiterung |
| --- | --- |
| einzelne Transformation ohne Java | Regeldatei |
| feste mehrstufige Strategie deklarieren | deklaratives Makro |
| kuratierten First-Party-Regelbestand beitragen | Knowledge Pack |
| eigene Java-Logik oder neue Suchkomponente | Java-Plugin |
| Hypothesenkandidaten aus Beobachtungen erzeugen | Discovery-Operator |
| externen Validator oder Solver anbinden | mathematische Capability |
| wiederkehrenden Suchpfad automatisch wiederverwenden | gelernter Kandidat nach eigenen Gates |
| reproduzierbares Experimentinventar variieren | Regelprofil und Pack-Ablation |

## Erweiterungsflächen

| Fläche | Kann Suchkanten beitragen? | Aktivierung | Maßgebliche Grenze |
| --- | ---: | --- | --- |
| Java-Plugin | ja | lokal installiertes, kompatibles Plugin | Artefaktvertrauen, API-Kompatibilität und Laufzeitdiagnose |
| Regeldatei | ja | Parser und Aktivierungsprofil | Syntax, Konflikte, Zyklen und deklarierte Annahmen |
| Knowledge Pack | ja | kuratierter Reviewstatus und Regelprofil | Provenienz, Lizenz, Risiko und Validierungsbeispiele |
| deklaratives Makro | ja | Regeldatei und Profil | explizit vom Autor vorgegebene Strategie |
| gelernter Kandidat / Makro | erst nach eigenem Gate | Qualification und gegebenenfalls Promotion | Holdouts, Counterexamples, Novelty, Proof und Utility bleiben getrennt |
| Discovery-Operator | erzeugt Kandidaten | begrenzte Operator-Registry | keine automatische Wahrheit oder Promotion |
| mathematische Capability | üblicherweise nein | capability-aware Auswahl | validiert oder beweist eine vorhandene Obligation |
| Autopilot-Capability | nein | Research Brief | erlaubt Arbeit, lädt aber keinen Code und autorisiert keinen Claim |

## Regel-Tiers und reproduzierbare Inventare

Eingebaute Regeln werden nach ihrer Rolle klassifiziert:

- **Kernel:** minimaler, stabiler Regelkern;
- **First-Party-Packs:** kuratierte, bewusst aktivierbare Fähigkeiten;
- **Plugins und Regeldateien:** externe oder lokale Erweiterungen;
- **gelernte Kandidaten:** zunächst quarantänisierte Ergebnisse.

Profile wie `minimal-kernel`, `core` und `full` bestimmen den aktiven Bestand.
Zusätzliche Packs können explizit aktiviert oder deaktiviert werden. Ein
content-addressed Regelinventar-Manifest bindet Profil, Pack-Zuordnung und
konkrete Regelidentitäten an den Lauf.

Damit lassen sich Ablationen durchführen, ohne ein Ergebnis nachträglich durch
einen veränderten Regelbestand umzudeuten. Siehe
[Regel-Tiers und Ablation](rule-tiers.md).

## Java-Plugins

Java-Plugins werden über den dokumentierten Plugin-Vertrag und `ServiceLoader`
geladen. Unterstützte Beiträge umfassen unter anderem Regeln, Transformationen,
Suchstrategien, Heuristiken, Kostenfunktionen, Renderer, Erklärungen,
Parser-Erweiterungen und Beispiele.

Nicht jede interne Registry ist automatisch ein öffentlicher Plugin-Endpunkt.
Discovery-, Proof-, Solver- und Release-Komponenten sind nur extern erweiterbar,
wenn dafür ein eigener dokumentierter Vertrag existiert.

Details: [Plugins](plugins.md) und [Plugin-API](plugin-api.md).

## Regeldateien und deklarative Makros

Die `.regelsuche`-/`.rules`-DSL ist der niedrigschwellige Weg für lokale Regeln
und feste Makros. Sie bietet:

- typisiertes Parsing und verständliche Diagnosen;
- Prioritäten, Tags und Aktivierungsprofile;
- Whitelist und Blacklist;
- Konflikt- und Zykluserkennung;
- Import, Export und Debug-Ausgabe.

Ein deklaratives Makro ist eine vom Autor vorgegebene Strategie. Es ist nicht
mit einer aus Suchbeobachtungen gelernten und anschließend qualifizierten Regel
gleichzusetzen.

Details: [Regeldateien](rule-files.md) und [Makros](macros.md).

## Knowledge Packs

Knowledge Packs sind kuratierte First-Party-Pakete mit zusätzlichen
Governance-Metadaten:

- Herkunft und Autorenschaft;
- Lizenz;
- Reviewstatus;
- Risikoklassifikation;
- Validierungsbeispiele;
- Pack- und Tier-Zuordnung.

Sie eignen sich für reviewbare Domänenfähigkeiten, die nicht zwingend zum
minimalen Kernel gehören. Eine vorhandene Implementierung wird nicht allein
durch ihre Existenz Teil des gemessenen Standardinventars.

Details: [Knowledge Packs](knowledge-packs.md).

## Gelernte Kandidaten und Makros

Regelsuche kann wiederkehrende Pfade oder Strukturen zu parametrisierten
Kandidaten verdichten. Candidate Formation ist jedoch nur der Beginn eines
eigenen Evidence-Zweigs.

Mögliche nachgelagerte Stufen sind:

1. strukturelle Generalisierung und Lineage-Prüfung;
2. positive und negative Holdouts;
3. Counterexample Search;
4. Projekt-Novelty;
5. Proof-Evidence;
6. gepaarter Suchnutzen;
7. Qualification, Promotion und Public Evidence.

Keine erfolgreiche Stufe ersetzt die anderen. Ein Kandidat darf erst nach dem
für seinen Einsatz erforderlichen Gate in einen autoritativen aktiven Bestand
zurückkehren.

Details: [Makroregeln und emergente Identitäten](macro-rules.md) und
[Von Umformungen zu mathematischen Entdeckungen](from-transformations-to-discovery.md).

## Discovery-Operatoren

Discovery-Operatoren erzeugen begrenzte Hypothesenkandidaten aus Ausdrücken,
Suchgraphen oder retained Observations. Sie sind weder Wahrheitsorakel noch
automatische Promotion.

Jeder Kandidat durchläuft die normalen Validierungs-, Falsifikations-, Novelty-
und Proof-Grenzen. Ein Operator muss seine Inputs, Bounds und Herkunft
explizit machen.

## Mathematische Capabilities und Solver

Solver und mathematische Algorithmen werden anhand deklarierter Fähigkeiten und
unterstützter Domänen ausgewählt. Sie erhalten eine bereits formulierte,
versionierte Obligation und liefern ein strukturiertes Ergebnis.

Ein Validator oder Prover darf nicht unbemerkt die zu bewertende Hypothese oder
die erwartete Antwort konstruieren. Candidate Formation und Beurteilung bleiben
getrennte Informationsrollen.

Details: [Mathematical Algorithms](mathematical-algorithms.md),
[Solver-neutrale IR](solver-neutral-ir.md) und
[Solver-Portfolio](solver-portfolio.md).

## Autopilot-Capabilities

Ein Research Brief benennt erlaubte Domänen, Generatoren, Capabilities und
Budgets. Diese Angaben begrenzen eine Campaign; sie installieren keinen Code
und sind kein Trust-, Proof- oder Novelty-Status.

Planner-Entscheidungen und Ressourcenallokation sind operative Evidence, aber
keine mathematische Bestätigung. Siehe [Autopilot](autopilot-planner.md).

## Artefakt- und Indexvertrauen

Die lokale Trust-Grundlage ist implementiert:

- Detached-Ed25519-Manifeste binden Artefaktbytes, Publisher und Key;
- ein Publisher-Trust-Store modelliert aktive, rotierte und widerrufene Keys;
- JAR-Bytes werden begrenzt und symlink-sicher gelesen;
- geladen werden exakt die zuvor verifizierten Bytes;
- ein unveränderlicher Artifact Index bindet Versionen, Kompatibilität,
  Abhängigkeiten, Provenienz und Content-Hashes;
- Indexrevisionen werden durch Curator-Signaturen authentisiert;
- Trust-State-Revisionen bilden eine signierte monotone Hashkette mit lokalem
  Checkpoint gegen Replay, Lücken und Forks;
- Verifikation und Gate-Entscheidungen erzeugen kanonische Evidence.

Diese Fähigkeiten trennen Artefaktvertrauen von mathematischer Korrektheit. Ein
korrekt signiertes Plugin ist nicht automatisch mathematisch richtig; eine
mathematisch gültige Regel ist nicht automatisch aus einer vertrauenswürdigen
Quelle installiert.

Details:

- [Plugin Artifact Trust](plugin-artifact-trust.md)
- [Plugin Artifact Index](plugin-artifact-index.md)
- [Plugin Trust Store Revisions](plugin-trust-store-revisions.md)

## Was noch nicht als öffentliches Ökosystem qualifiziert ist

`PUBLIC_PLUGIN_DISTRIBUTION` bleibt `BLOCKED`. Noch fehlen insbesondere:

- ein real betriebener kuratierter oder föderierter Indextransport;
- authentisierter Client-Abruf von Index- und Trust-State-Revisionen;
- transaktional rollback-geschützte Übernahme lokaler Checkpoints;
- Download exakt der indexgebundenen Bytes unter Zeit-, Größen- und
  Redirect-Grenzen;
- atomare Installation, Update, Entfernung und Rollback;
- unabhängig baubare externe Beispielprojekte gegen veröffentlichte API-
  Artefakte;
- vollständige Publishing-, Review-, Incident- und Revocation-Prozesse.

Die vorhandenen lokalen kryptografischen Verträge dürfen durch diese spätere
Verteilungsschicht nicht permissiver werden.

## Entscheidungsregel

Wähle die kleinste Erweiterungsfläche, die die gewünschte Verantwortung trägt.
Eine neue mathematische Regel benötigt nicht automatisch ein Java-Plugin; ein
neuer Solver ist nicht automatisch eine Suchkante; ein gelernter Kandidat ist
nicht automatisch aktives Wissen.
